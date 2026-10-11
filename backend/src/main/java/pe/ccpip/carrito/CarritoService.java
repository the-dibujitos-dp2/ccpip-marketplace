package pe.ccpip.carrito;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.carrito.CarritoModelos.Carrito;
import pe.ccpip.carrito.CarritoModelos.EstadoItem;
import pe.ccpip.carrito.CarritoModelos.Item;
import pe.ccpip.catalogo.CatalogoModelos.TiendaResumen;
import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.catalogo.CatalogoRepository.Vendible;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.imagenes.ImagenService;

/**
 * Carrito persistido del comprador (CCPIP-58, CCPIP-155, CCPIP-268, CCPIP-156). Agregar solo consulta la disponibilidad: no
 * reserva ni descuenta existencias (RNF-35, ADR-11). El descuento ocurre al confirmar la compra.
 */
@Service
public class CarritoService {

    /** Resultado de agregar: cuanto hay de esa oferta en el carrito y cuantas ofertas distintas tiene. */
    public record Agregado(long productoId, BigDecimal cantidad, int articulos) {
    }

    private final JdbcTemplate jdbc;
    private final CatalogoRepository catalogo;

    public CarritoService(JdbcTemplate jdbc, CatalogoRepository catalogo) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
    }

    /**
     * CCPIP-155: el carrito persistido del comprador con precio y disponibilidad recalculados ahora
     * mismo (RF-124). Sobrevive al cierre de sesion porque vive en la base, y consultarlo no reserva.
     */
    @Transactional(readOnly = true)
    public Carrito consultar(long usuarioId) {
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
        List<Item> comprables = items.stream().filter(i -> i.estado() == EstadoItem.DISPONIBLE).toList();
        BigDecimal total = comprables.stream().map(Item::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        long comunidades = comprables.stream().map(i -> i.tienda().id()).distinct().count();
        return new Carrito(items, unidades, comunidades, total);
    }

    private record ItemGuardado(long productoId, String nombre, String contenido, String unidad, String imagen,
                                String linea, LocalDate cosecha, TiendaResumen tienda, BigDecimal precio, BigDecimal cantidad) {
    }

    private static Item revalidar(ItemGuardado g, Vendible oferta) {
        EstadoItem estado;
        BigDecimal disponibles = null;
        if (oferta == null) {
            estado = EstadoItem.NO_DISPONIBLE;
        } else if ("MAYORISTA".equals(oferta.modalidad()) || g.precio() == null) {
            estado = EstadoItem.SOLO_MAYORISTA;
        } else if (oferta.enVenta().signum() == 0) {
            estado = EstadoItem.AGOTADO;
        } else if (g.cantidad().compareTo(oferta.enVenta()) > 0) {
            estado = EstadoItem.STOCK_INSUFICIENTE;
            disponibles = oferta.enVenta();
        } else {
            estado = EstadoItem.DISPONIBLE;
        }
        BigDecimal subtotal = estado == EstadoItem.DISPONIBLE
                ? g.precio().multiply(g.cantidad()).setScale(2, RoundingMode.HALF_UP)
                : null;
        return new Item(g.productoId(), g.nombre(), g.contenido(), g.unidad(), g.imagen(), g.linea(), g.cosecha(), g.tienda(),
                g.precio(), g.cantidad(), subtotal, estado, disponibles);
    }

    /**
     * La oferta debe estar visible, venderse al por menor (la tarifa mayorista llega con CCPIP-134)
     * y tener saldo vendible para lo que ya estaba en el carrito mas lo nuevo (RNF-09).
     */
    @Transactional
    public Agregado agregar(long usuarioId, long productoId, BigDecimal cantidad) {
        Vendible oferta = catalogo.vendible(productoId).orElseThrow(() ->
                ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o ya no está disponible"));
        exigirComprable(oferta);

        BigDecimal enCarrito = jdbc.query("""
                SELECT i.cantidad FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id
                WHERE c.usuario_id = ? AND i.producto_id = ?""",
                (rs, row) -> rs.getBigDecimal("cantidad"), usuarioId, productoId)
                .stream().findFirst().orElse(BigDecimal.ZERO);
        if (enCarrito.add(cantidad).compareTo(oferta.enVenta()) > 0) {
            String quedan = oferta.enVenta().stripTrailingZeros().toPlainString();
            throw new ErrorNegocio(HttpStatus.CONFLICT, "STOCK_INSUFICIENTE", enCarrito.signum() == 0
                    ? "Solo quedan " + quedan + " unidades disponibles."
                    : "Solo quedan " + quedan + " unidades y ya tienes "
                            + enCarrito.stripTrailingZeros().toPlainString() + " en tu carrito.");
        }

        Long carritoId = jdbc.queryForObject("""
                INSERT INTO carrito (usuario_id) VALUES (?)
                ON CONFLICT (usuario_id) DO UPDATE SET actualizado_en = now()
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
     * stock en ese momento (RF-141), y devuelve el carrito recalculado. No reserva ni descuenta (RNF-35).
     * Una oferta que ya no se puede comprar no se modifica: se quita (CCPIP-156).
     */
    @Transactional
    public Carrito modificar(long usuarioId, long productoId, BigDecimal cantidad) {
        Long itemId = jdbc.query("""
                SELECT i.id FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id
                WHERE c.usuario_id = ? AND i.producto_id = ?""",
                (rs, row) -> rs.getLong("id"), usuarioId, productoId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("ITEM_NO_ENCONTRADO", "Este producto ya no está en tu carrito."));
        Vendible oferta = catalogo.vendible(productoId).orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT,
                "NO_DISPONIBLE", "Este producto ya no está disponible. Quítalo de tu carrito."));
        exigirComprable(oferta);
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
        int quitados = jdbc.update("""
                DELETE FROM item_carrito i USING carrito c
                WHERE c.id = i.carrito_id AND c.usuario_id = ? AND i.producto_id = ?""", usuarioId, productoId);
        if (quitados == 0) {
            throw ErrorNegocio.noEncontrado("ITEM_NO_ENCONTRADO", "Este producto ya no está en tu carrito.");
        }
        jdbc.update("UPDATE carrito SET actualizado_en = now() WHERE usuario_id = ?", usuarioId);
        return consultar(usuarioId);
    }

    /** Reglas comunes para agregar y modificar: por ahora solo al por menor y con saldo vendible. */
    private static void exigirComprable(Vendible oferta) {
        if ("MAYORISTA".equals(oferta.modalidad())) {
            throw ErrorNegocio.invalido("SOLO_MAYORISTA", "La compra mayorista estará disponible pronto.");
        }
        if (oferta.enVenta().signum() == 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "AGOTADO", "Este producto está agotado por ahora.");
        }
    }
}
