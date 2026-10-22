package pe.ccpip.compra;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.carrito.CarritoModelos.Carrito;
import pe.ccpip.carrito.CarritoModelos.EstadoItem;
import pe.ccpip.carrito.CarritoModelos.Item;
import pe.ccpip.carrito.CarritoService;
import pe.ccpip.carrito.Tarifas.Tarifa;
import pe.ccpip.comision.ComisionModelos.ComisionPedido;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.compra.CompraModelos.EventoPedido;
import pe.ccpip.compra.CompraModelos.IntentoPago;
import pe.ccpip.compra.CompraModelos.LineaPedido;
import pe.ccpip.compra.CompraModelos.Pedido;
import pe.ccpip.compra.CompraModelos.PuntoRecojo;
import pe.ccpip.compra.CompraModelos.ResumenPedido;
import pe.ccpip.entrega.PersonaDeRecojo;

/**
 * CCPIP-157: confirmar la compra para recojo en un punto de acopio con existencias elegibles, y
 * CCPIP-64: consultar el pedido, CCPIP-112: la lista de compras del comprador y CCPIP-70 y CCPIP-340:
 * su estado actual y su historial. El pedido nace en PENDIENTE_PAGO y no toca el stock: se confirma y
 * descuenta recien cuando un pago sale aprobado (ver {@link PagoService}).
 */
@Service
public class CompraService {

    static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    private final NamedParameterJdbcTemplate jdbc;
    private final CarritoService carrito;

    public CompraService(NamedParameterJdbcTemplate jdbc, CarritoService carrito) {
        this.jdbc = jdbc;
        this.carrito = carrito;
    }

    /**
     * Puntos de acopio donde hay existencias vigentes para todo el carrito, cada linea completa. No es
     * de solo lectura: consultar el carrito lo vacia si vencio (CCPIP-142).
     */
    @Transactional
    public List<PuntoRecojo> puntosElegibles(long usuarioId) {
        return puntos(comprables(usuarioId), null);
    }

    /**
     * Revalida precio, oferta, stock y cuenta bancaria del carrito (via {@link CarritoService}) y crea el
     * pedido en PENDIENTE_PAGO con los precios copiados. Un pedido anterior que se quedo esperando su
     * pago se cancela: sin pago no desconto nada, asi que no hay stock que devolver.
     */
    @Transactional
    public Pedido confirmar(long usuarioId, long puntoId, String personaAutorizada, String celular) {
        String celularContacto = PersonaDeRecojo.celular(celular).orElseThrow(() ->
                ErrorNegocio.invalido("CELULAR_INVALIDO", "Ingresa un celular de 9 dígitos que empiece con 9."));
        String autorizada = personaAutorizada == null || personaAutorizada.isBlank() ? null : personaAutorizada.strip();

        List<Item> items = comprables(usuarioId);
        if (puntos(items, puntoId).isEmpty()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PUNTO_SIN_EXISTENCIAS",
                    "Este punto de acopio ya no tiene existencias para todo tu carrito. Elige otro punto.");
        }

        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId);
        jdbc.update("""
                WITH cancelados AS (
                    UPDATE pedido pe SET estado = 'CANCELADO', actualizado_en = now()
                    WHERE pe.usuario_id = :usuario AND pe.estado = 'PENDIENTE_PAGO'
                      AND NOT EXISTS (SELECT 1 FROM pago pa WHERE pa.pedido_id = pe.id AND pa.estado = 'PENDIENTE')
                    RETURNING pe.id)
                INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id)
                SELECT id, 'CANCELADO', :usuario FROM cancelados""", p);

        long id = jdbc.getJdbcTemplate().queryForObject("SELECT nextval(pg_get_serial_sequence('pedido', 'id'))", Long.class);
        BigDecimal total = items.stream().map(Item::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        p.addValue("id", id).addValue("numero", "CCP-%06d".formatted(id)).addValue("punto", puntoId)
                .addValue("total", total).addValue("autorizada", autorizada).addValue("celular", celularContacto);
        jdbc.update("""
                INSERT INTO pedido (id, numero, usuario_id, modalidad, ubicacion_origen_id, estado, total, confirmado_en,
                                    persona_autorizada, celular_contacto)
                VALUES (:id, :numero, :usuario, 'RECOJO', :punto, 'PENDIENTE_PAGO', :total, NULL, :autorizada, :celular)""", p);
        for (Item item : items) {
            jdbc.update("""
                    INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal, tarifa)
                    VALUES (:id, :producto, :productor, :cantidad, :precio, :subtotal, :tarifa)""",
                    new MapSqlParameterSource("id", id).addValue("producto", item.productoId())
                            .addValue("productor", item.tienda().id()).addValue("cantidad", item.cantidad())
                            .addValue("precio", item.precio()).addValue("subtotal", item.subtotal())
                            // CCPIP-345: B2B o B2C, segun la tarifa que resolvio el carrito para esta cantidad
                            .addValue("tarifa", item.tarifa() == null ? Tarifa.MINORISTA.name() : item.tarifa().name()));
        }
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id) VALUES (:id, 'PENDIENTE_PAGO', :usuario)", p);
        return pedido(usuarioId, id);
    }

    /** CCPIP-64 y CCPIP-163: el pedido del comprador con sus lineas e intentos de pago. Ajeno = no existe (RNF-05). */
    @Transactional(readOnly = true)
    public Pedido pedido(long usuarioId, long pedidoId) {
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("id", pedidoId);
        List<Pedido> encontrados = jdbc.query("""
                SELECT pe.id, pe.numero, pe.estado, pe.modalidad, pe.total, pe.creado_en, pe.confirmado_en,
                       pe.persona_autorizada, pe.celular_contacto,
                       pe.comision_porcentaje, pe.comision_base, pe.comision_monto,
                       u.id AS u_id, u.nombre AS u_nombre, u.departamento, u.provincia, u.distrito, u.direccion
                FROM pedido pe JOIN ubicacion u ON u.id = pe.ubicacion_origen_id
                WHERE pe.id = :id AND pe.usuario_id = :usuario""", p,
                (rs, row) -> new Pedido(rs.getLong("id"), rs.getString("numero"), rs.getString("estado"),
                        rs.getString("modalidad"), rs.getBigDecimal("total"), instante(rs, "creado_en"),
                        instante(rs, "confirmado_en"), punto(rs, "u_"), rs.getString("persona_autorizada"),
                        rs.getString("celular_contacto"), lineas(pedidoId), pagos(pedidoId),
                        historial(List.of(pedidoId)).getOrDefault(pedidoId, List.of()), comision(rs)));
        return encontrados.stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "No encontramos ese pedido."));
    }

    /**
     * CCPIP-112: las compras del comprador, la mas reciente primero, con el total que pago y su
     * historial de estados. Solo las propias (RNF-05): la consulta filtra por la sesion.
     */
    @Transactional(readOnly = true)
    public List<ResumenPedido> misPedidos(long usuarioId) {
        List<ResumenPedido> sinHistorial = jdbc.query("""
                SELECT pe.id, pe.numero, pe.estado, pe.modalidad, pe.total, pe.creado_en, pe.confirmado_en,
                       u.id AS u_id, u.nombre AS u_nombre, u.departamento, u.provincia, u.distrito, u.direccion,
                       (SELECT count(*) FROM item_pedido i WHERE i.pedido_id = pe.id) AS productos,
                       (SELECT count(DISTINCT i.productor_id) FROM item_pedido i WHERE i.pedido_id = pe.id) AS comunidades
                FROM pedido pe JOIN ubicacion u ON u.id = pe.ubicacion_origen_id
                WHERE pe.usuario_id = :usuario
                ORDER BY pe.creado_en DESC, pe.id DESC""", new MapSqlParameterSource("usuario", usuarioId),
                (rs, row) -> new ResumenPedido(rs.getLong("id"), rs.getString("numero"), rs.getString("estado"),
                        rs.getString("modalidad"), rs.getBigDecimal("total"), instante(rs, "creado_en"),
                        instante(rs, "confirmado_en"), punto(rs, "u_"), rs.getInt("productos"),
                        rs.getInt("comunidades"), List.of()));
        if (sinHistorial.isEmpty()) {
            return sinHistorial;
        }
        Map<Long, List<EventoPedido>> historiales = historial(sinHistorial.stream().map(ResumenPedido::id).toList());
        return sinHistorial.stream().map(r -> new ResumenPedido(r.id(), r.numero(), r.estado(), r.modalidad(), r.total(),
                r.creadoEn(), r.confirmadoEn(), r.punto(), r.productos(), r.comunidades(),
                historiales.getOrDefault(r.id(), List.of()))).toList();
    }

    /** CCPIP-340: los estados de cada pedido en orden cronologico, sin quien los registro. */
    private Map<Long, List<EventoPedido>> historial(List<Long> pedidos) {
        Map<Long, List<EventoPedido>> porPedido = new HashMap<>();
        jdbc.query("""
                SELECT pedido_id, estado, registrado_en FROM pedido_estado_historial
                WHERE pedido_id IN (:ids) ORDER BY registrado_en, id""", new MapSqlParameterSource("ids", pedidos),
                rs -> {
                    porPedido.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                            .add(new EventoPedido(rs.getString("estado"), instante(rs, "registrado_en")));
                });
        return porPedido;
    }

    private List<LineaPedido> lineas(long pedidoId) {
        return jdbc.query("""
                SELECT i.producto_id, i.productor_id, p.nombre, coalesce(pr.nombre_tienda, pr.comunidad) AS tienda,
                       i.cantidad, i.precio_unitario, i.subtotal
                FROM item_pedido i
                JOIN producto p ON p.id = i.producto_id
                JOIN productor pr ON pr.id = i.productor_id
                WHERE i.pedido_id = :id ORDER BY i.id""", new MapSqlParameterSource("id", pedidoId),
                (rs, row) -> new LineaPedido(rs.getLong("producto_id"), rs.getLong("productor_id"), rs.getString("nombre"), rs.getString("tienda"),
                        rs.getBigDecimal("cantidad"), rs.getBigDecimal("precio_unitario"), rs.getBigDecimal("subtotal")));
    }

    private List<IntentoPago> pagos(long pedidoId) {
        return jdbc.query("""
                SELECT id, estado, monto, marca_tarjeta, referencia_pasarela, mensaje_respuesta, creado_en, resuelto_en
                FROM pago WHERE pedido_id = :id ORDER BY creado_en, id""", new MapSqlParameterSource("id", pedidoId),
                (rs, row) -> new IntentoPago(rs.getLong("id"), rs.getString("estado"), rs.getBigDecimal("monto"),
                        rs.getString("marca_tarjeta"), rs.getString("referencia_pasarela"),
                        rs.getString("mensaje_respuesta"), instante(rs, "creado_en"), instante(rs, "resuelto_en")));
    }

    /**
     * El carrito revalidado ahora mismo. Para confirmar, todo lo que tiene debe poder comprarse: lo
     * agotado, pausado o con menos stock se ajusta o se quita antes, en el carrito.
     */
    private List<Item> comprables(long usuarioId) {
        Carrito actual = carrito.consultar(usuarioId);
        if (actual.items().isEmpty()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CARRITO_VACIO", "Tu carrito está vacío.");
        }
        if (actual.items().stream().anyMatch(i -> i.estado() != EstadoItem.DISPONIBLE)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CARRITO_CON_PROBLEMAS",
                    "Algunos productos de tu carrito no se pueden comprar como están. Ajústalos o quítalos para continuar.");
        }
        return actual.items();
    }

    /**
     * Puntos de acopio activos donde cada linea tiene lotes vigentes suficientes. El pedido sale de un
     * solo punto, el mismo del que luego descuenta {@code DescuentoExistenciasService}.
     */
    private List<PuntoRecojo> puntos(List<Item> items, Long soloPunto) {
        MapSqlParameterSource p = new MapSqlParameterSource("punto", soloPunto);
        String lineas = IntStream.range(0, items.size()).mapToObj(i -> {
            p.addValue("producto" + i, items.get(i).productoId()).addValue("cantidad" + i, items.get(i).cantidad());
            return "(CAST(:producto%d AS bigint), CAST(:cantidad%d AS numeric))".formatted(i, i);
        }).collect(Collectors.joining(", "));
        return jdbc.query("""
                SELECT u.id, u.nombre, u.departamento, u.provincia, u.distrito, u.direccion
                FROM ubicacion u
                WHERE u.tipo = 'PUNTO_ACOPIO' AND u.activo
                  AND (CAST(:punto AS bigint) IS NULL OR u.id = :punto)
                  AND NOT EXISTS (
                    SELECT 1 FROM (VALUES %s) AS pedida(producto_id, cantidad)
                    WHERE pedida.cantidad > (
                        SELECT coalesce(sum(l.cantidad_actual), 0) FROM lote l
                        WHERE l.producto_id = pedida.producto_id AND l.ubicacion_id = u.id
                          AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= %s)))
                ORDER BY u.nombre""".formatted(lineas, HOY), p, (rs, row) -> punto(rs, ""));
    }

    private static PuntoRecojo punto(ResultSet rs, String prefijo) throws SQLException {
        return new PuntoRecojo(rs.getLong(prefijo + "id"), rs.getString(prefijo + "nombre"), rs.getString("departamento"),
                rs.getString("provincia"), rs.getString("distrito"), rs.getString("direccion"));
    }

    /** CCPIP-167: la comision copiada al confirmar; nula si no hay copia. */
    private static ComisionPedido comision(ResultSet rs) throws SQLException {
        BigDecimal porcentaje = rs.getBigDecimal("comision_porcentaje");
        return porcentaje == null ? null
                : new ComisionPedido(porcentaje, rs.getString("comision_base"), rs.getBigDecimal("comision_monto"));
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
