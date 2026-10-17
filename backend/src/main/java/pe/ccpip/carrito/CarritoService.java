package pe.ccpip.carrito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.carrito.CarritoModelos.Carrito;
import pe.ccpip.carrito.CarritoModelos.EstadoItem;
import pe.ccpip.carrito.CarritoModelos.Grupo;
import pe.ccpip.carrito.CarritoModelos.Item;
import pe.ccpip.carrito.Tarifas.Resolucion;
import pe.ccpip.carrito.VigenciaCarritoService.Vigencia;
import pe.ccpip.catalogo.CatalogoModelos.TiendaResumen;
import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.catalogo.CatalogoRepository.Vendible;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.imagenes.ImagenService;

/**
 * Carrito persistido del comprador (CCPIP-58, CCPIP-155, CCPIP-268, CCPIP-156). Agregar solo consulta la disponibilidad: no
 * reserva ni descuenta existencias (RNF-35, ADR-11). El descuento ocurre al confirmar la compra. La tarifa
 * minorista o mayorista de cada oferta se resuelve aqui segun su modalidad y la cantidad (CCPIP-134, RF-108).
 * El carrito se muestra agrupado por productor (CCPIP-63, RF-44) y se conserva durante la vigencia que
 * configura la Camara, contada desde su ultimo cambio (CCPIP-142, RF-109): vencido, se vacia antes de
 * leerlo o cambiarlo.
 */
@Service
public class CarritoService {

    /** Resultado de agregar: cuanto hay de esa oferta en el carrito y cuantas ofertas distintas tiene. */
    public record Agregado(long productoId, BigDecimal cantidad, int articulos) {
    }

    private final JdbcTemplate jdbc;
    private final CatalogoRepository catalogo;
    private final VigenciaCarritoService vigencia;

    public CarritoService(JdbcTemplate jdbc, CatalogoRepository catalogo, VigenciaCarritoService vigencia) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
        this.vigencia = vigencia;
    }

    /**
     * CCPIP-155: el carrito persistido del comprador con precio y disponibilidad recalculados ahora
     * mismo (RF-124). Sobrevive al cierre de sesion porque vive en la base, y consultarlo no reserva.
     * Tambien revalida que cada productor pueda vender (CCPIP-269): la confirmacion de la compra
     * debe volver a consultarlo justo antes del pago y aceptar solo items DISPONIBLE (RF-142).
     * <p>
     * CCPIP-142: si vencio se vacia aqui mismo, sin tocar el inventario, y se informa {@code vencidoEn};
     * un carrito vigente se retoma revalidado. Consultar no renueva la vigencia: solo un cambio la renueva.
     */
    @Transactional
    public Carrito consultar(long usuarioId) {
        Vigencia vida = vigencia.vigente();
        vigencia.vencerSiCorresponde(usuarioId, vida);
        List<ItemGuardado> guardados = jdbc.query("""
                SELECT i.producto_id, i.cantidad, p.nombre, p.contenido, p.precio, um.nombre AS unidad,
                       (SELECT im.id FROM imagen im WHERE im.producto_id = p.id AND im.principal) AS imagen,
                       l.codigo AS linea,
                       (SELECT max(lt.fecha_produccion) FROM lote lt JOIN ubicacion u ON u.id = lt.ubicacion_id
                        WHERE lt.producto_id = p.id AND lt.cantidad_actual > 0 AND u.tipo = 'PUNTO_ACOPIO'
                          AND (lt.fecha_vencimiento IS NULL
                               OR lt.fecha_vencimiento >= (now() AT TIME ZONE 'America/Lima')::date)) AS cosecha,
                       pr.id AS pr_id, coalesce(pr.nombre_tienda, pr.comunidad) AS pr_nombre, pr.pueblo, pr.comunidad,
                       pr.region, pr.localidad, pr.origen_verificado
                FROM item_carrito i
                JOIN carrito c ON c.id = i.carrito_id
                JOIN producto p ON p.id = i.producto_id
                JOIN productor pr ON pr.id = p.productor_id
                LEFT JOIN unidad_medida um ON um.id = p.unidad_medida_id
                LEFT JOIN linea_producto l ON l.id = p.linea_id
                WHERE c.usuario_id = ?
                ORDER BY i.agregado_en, i.id""",
                (rs, row) -> {
                    UUID imagen = rs.getObject("imagen", UUID.class);
                    return new ItemGuardado(rs.getLong("producto_id"), rs.getString("nombre"), rs.getString("contenido"),
                            rs.getString("unidad"), imagen == null ? null : ImagenService.urlMiniatura(imagen),
                            rs.getString("linea"), rs.getObject("cosecha", LocalDate.class),
                            new TiendaResumen(rs.getLong("pr_id"), rs.getString("pr_nombre"), rs.getString("pueblo"),
                                    rs.getString("comunidad"), rs.getString("region"), rs.getString("localidad"),
                                    rs.getBoolean("origen_verificado")),
                            rs.getBigDecimal("precio"), rs.getBigDecimal("cantidad"));
                }, usuarioId);

        Map<Long, Vendible> vendibles = catalogo.vendibles(guardados.stream().map(ItemGuardado::productoId).toList());
        List<Item> items = guardados.stream().map(g -> revalidar(g, vendibles.get(g.productoId()))).toList();

        BigDecimal unidades = items.stream().map(Item::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Grupo> grupos = agrupar(items);
        // CCPIP-63: el total es la suma de los grupos, sin otro redondeo (cada subtotal ya va a 2 decimales)
        BigDecimal total = grupos.stream().map(Grupo::subtotal).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        long comunidades = grupos.stream().filter(g -> g.items().stream().anyMatch(CarritoService::comprable)).count();

        record Estado(Timestamp actualizadoEn, Timestamp vencidoEn) {
        }
        Estado estado = jdbc.query("SELECT actualizado_en, vencido_en FROM carrito WHERE usuario_id = ?",
                (rs, row) -> new Estado(rs.getTimestamp("actualizado_en"), rs.getTimestamp("vencido_en")), usuarioId)
                .stream().findFirst().orElse(new Estado(null, null));
        Instant hasta = items.isEmpty() || estado.actualizadoEn() == null
                ? null : VigenciaCarritoService.vigenteHasta(estado.actualizadoEn(), vida);
        Instant vencido = items.isEmpty() && estado.vencidoEn() != null ? estado.vencidoEn().toInstant() : null;
        return new Carrito(items, grupos, unidades, comunidades, total, hasta, vida.horas(), vencido);
    }

    private static boolean comprable(Item item) {
        return item.estado() == EstadoItem.DISPONIBLE;
    }

    /**
     * CCPIP-63 (RF-44): un grupo por productor, en el orden en que aparecio su primera oferta. Se agrupa
     * por el id del productor, no por el nombre de la comunidad: dos productores de la misma comunidad
     * venden ofertas e inventarios distintos (CCPIP-172).
     */
    private static List<Grupo> agrupar(List<Item> items) {
        Map<Long, List<Item>> porProductor = new LinkedHashMap<>();
        items.forEach(i -> porProductor.computeIfAbsent(i.tienda().id(), id -> new ArrayList<>()).add(i));
        return porProductor.values().stream().map(delProductor -> new Grupo(delProductor.get(0).tienda(),
                List.copyOf(delProductor),
                delProductor.stream().filter(CarritoService::comprable).map(Item::subtotal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP)))
                .toList();
    }

    private record ItemGuardado(long productoId, String nombre, String contenido, String unidad, String imagen,
                                String linea, LocalDate cosecha, TiendaResumen tienda, BigDecimal precio, BigDecimal cantidad) {
    }

    private static Item revalidar(ItemGuardado g, Vendible oferta) {
        if (oferta == null) {
            // Ya no esta visible: se muestra con su ultimo precio, sin tarifa, para que se quite
            return new Item(g.productoId(), g.nombre(), g.contenido(), g.unidad(), g.imagen(), g.linea(), g.cosecha(),
                    g.tienda(), g.precio(), g.cantidad(), null, EstadoItem.NO_DISPONIBLE, null, null, null, null, null, null);
        }
        // CCPIP-134: la tarifa se recalcula con la cantidad actual en cada consulta
        Resolucion tarifa = tarifa(oferta, g.cantidad());
        EstadoItem estado;
        BigDecimal disponibles = null;
        if (!oferta.habilitada()) {
            estado = EstadoItem.VENTA_PAUSADA;
        } else if (tarifa.precio() == null) {
            estado = EstadoItem.NO_DISPONIBLE;
        } else if (!tarifa.comprable()) {
            estado = EstadoItem.SOLO_MAYORISTA;
        } else if (oferta.enVenta().signum() == 0) {
            estado = EstadoItem.AGOTADO;
        } else if (g.cantidad().compareTo(oferta.enVenta()) > 0) {
            estado = EstadoItem.STOCK_INSUFICIENTE;
            disponibles = oferta.enVenta();
        } else {
            estado = EstadoItem.DISPONIBLE;
        }
        BigDecimal precio = tarifa.precio() == null ? g.precio() : tarifa.precio();
        BigDecimal subtotal = estado == EstadoItem.DISPONIBLE
                ? precio.multiply(g.cantidad()).setScale(2, RoundingMode.HALF_UP)
                : null;
        boolean minorista = !"MAYORISTA".equals(oferta.modalidad());
        boolean mayorista = !"MINORISTA".equals(oferta.modalidad());
        return new Item(g.productoId(), g.nombre(), g.contenido(), g.unidad(), g.imagen(), g.linea(), g.cosecha(), g.tienda(),
                precio, g.cantidad(), subtotal, estado, disponibles, tarifa.tarifa(),
                minorista ? oferta.precio() : null, mayorista ? oferta.precioMayorista() : null,
                mayorista ? oferta.minimoMayorista() : null, tarifa.faltante());
    }

    private static Resolucion tarifa(Vendible oferta, BigDecimal cantidad) {
        return Tarifas.resolver(oferta.modalidad(), oferta.precio(), oferta.precioMayorista(), oferta.minimoMayorista(),
                cantidad);
    }

    /**
     * La oferta debe estar visible, tener tarifa para la cantidad que quedaria en el carrito (una solo
     * mayorista, desde su minimo: CCPIP-134) y saldo vendible para lo que ya estaba mas lo nuevo (RNF-09).
     */
    @Transactional
    public Agregado agregar(long usuarioId, long productoId, BigDecimal cantidad) {
        // CCPIP-142: lo de un carrito vencido no se suma a lo nuevo
        vigencia.vencerSiCorresponde(usuarioId, vigencia.vigente());
        Vendible oferta = catalogo.vendible(productoId).orElseThrow(() ->
                ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o ya no está disponible"));
        BigDecimal enCarrito = jdbc.query("""
                SELECT i.cantidad FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id
                WHERE c.usuario_id = ? AND i.producto_id = ?""",
                (rs, row) -> rs.getBigDecimal("cantidad"), usuarioId, productoId)
                .stream().findFirst().orElse(BigDecimal.ZERO);
        exigirComprable(oferta, enCarrito.add(cantidad));

        if (enCarrito.add(cantidad).compareTo(oferta.enVenta()) > 0) {
            String quedan = oferta.enVenta().stripTrailingZeros().toPlainString();
            throw new ErrorNegocio(HttpStatus.CONFLICT, "STOCK_INSUFICIENTE", enCarrito.signum() == 0
                    ? "Solo quedan " + quedan + " unidades disponibles."
                    : "Solo quedan " + quedan + " unidades y ya tienes "
                            + enCarrito.stripTrailingZeros().toPlainString() + " en tu carrito.");
        }

        Long carritoId = jdbc.queryForObject("""
                INSERT INTO carrito (usuario_id) VALUES (?)
                ON CONFLICT (usuario_id) DO UPDATE SET actualizado_en = now(), vencido_en = NULL
                RETURNING id""", Long.class, usuarioId);
        // Un producto aparece una sola vez por carrito: agregarlo de nuevo suma cantidad
        BigDecimal total = jdbc.queryForObject("""
                INSERT INTO item_carrito (carrito_id, producto_id, cantidad) VALUES (?, ?, ?)
                ON CONFLICT (carrito_id, producto_id) DO UPDATE SET cantidad = item_carrito.cantidad + EXCLUDED.cantidad
                RETURNING cantidad""", BigDecimal.class, carritoId, productoId, cantidad);
        Integer articulos = jdbc.queryForObject("SELECT count(*) FROM item_carrito WHERE carrito_id = ?",
                Integer.class, carritoId);
        return new Agregado(productoId, total, articulos == null ? 0 : articulos);
    }

    /**
     * CCPIP-268: reemplaza la cantidad de una oferta que ya esta en el carrito, revalidando precio y
     * stock en ese momento (RF-141), y devuelve el carrito recalculado con la tarifa que corresponde a la
     * nueva cantidad (CCPIP-134). No reserva ni descuenta (RNF-35).
     * Una oferta que ya no se puede comprar no se modifica: se quita (CCPIP-156).
     */
    @Transactional
    public Carrito modificar(long usuarioId, long productoId, BigDecimal cantidad) {
        // CCPIP-142: en un carrito vencido la oferta ya no esta y se responde ITEM_NO_ENCONTRADO
        vigencia.vencerSiCorresponde(usuarioId, vigencia.vigente());
        Long itemId = jdbc.query("""
                SELECT i.id FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id
                WHERE c.usuario_id = ? AND i.producto_id = ?""",
                (rs, row) -> rs.getLong("id"), usuarioId, productoId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("ITEM_NO_ENCONTRADO", "Este producto ya no está en tu carrito."));
        Vendible oferta = catalogo.vendible(productoId).orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT,
                "NO_DISPONIBLE", "Este producto ya no está disponible. Quítalo de tu carrito."));
        exigirComprable(oferta, cantidad);
        if (cantidad.compareTo(oferta.enVenta()) > 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "STOCK_INSUFICIENTE",
                    "Solo quedan " + oferta.enVenta().stripTrailingZeros().toPlainString() + " unidades disponibles.");
        }
        jdbc.update("UPDATE item_carrito SET cantidad = ? WHERE id = ?", cantidad, itemId);
        jdbc.update("UPDATE carrito SET actualizado_en = now() WHERE usuario_id = ?", usuarioId);
        return consultar(usuarioId);
    }

    /**
     * CCPIP-156: quita una oferta del carrito, este o no comprable (es la salida para lo agotado o
     * retirado), y devuelve el carrito recalculado. Sin movimiento de inventario (RNF-35).
     */
    @Transactional
    public Carrito quitar(long usuarioId, long productoId) {
        vigencia.vencerSiCorresponde(usuarioId, vigencia.vigente());
        int quitados = jdbc.update("""
                DELETE FROM item_carrito i USING carrito c
                WHERE c.id = i.carrito_id AND c.usuario_id = ? AND i.producto_id = ?""", usuarioId, productoId);
        if (quitados == 0) {
            throw ErrorNegocio.noEncontrado("ITEM_NO_ENCONTRADO", "Este producto ya no está en tu carrito.");
        }
        jdbc.update("UPDATE carrito SET actualizado_en = now() WHERE usuario_id = ?", usuarioId);
        return consultar(usuarioId);
    }

    /**
     * Reglas comunes para agregar y modificar: el productor puede vender (CCPIP-269), hay tarifa para la
     * cantidad que quedaria en el carrito (CCPIP-134: el minimo mayorista es obligatorio para una oferta
     * solo mayorista) y hay saldo vendible.
     */
    private static void exigirComprable(Vendible oferta, BigDecimal cantidad) {
        if (!oferta.habilitada()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "VENTA_PAUSADA",
                    "La venta de este producto está pausada por ahora. Puedes verlo, pero aún no comprarlo.");
        }
        Resolucion tarifa = tarifa(oferta, cantidad);
        if (tarifa.precio() == null) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "NO_DISPONIBLE", "Este producto ya no está disponible.");
        }
        if (!tarifa.comprable()) {
            throw ErrorNegocio.invalido("MINIMO_MAYORISTA", "Este producto se vende solo por mayor: mínimo "
                    + oferta.minimoMayorista() + " und. Te faltan " + tarifa.faltante().stripTrailingZeros().toPlainString()
                    + " und para poder comprarlo.");
        }
        if (oferta.enVenta().signum() == 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "AGOTADO", "Este producto está agotado por ahora.");
        }
    }
}
