package pe.ccpip.productor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.imagenes.ImagenService;
import pe.ccpip.productor.PedidosProductorModelos.Cambio;
import pe.ccpip.productor.PedidosProductorModelos.Estado;
import pe.ccpip.productor.PedidosProductorModelos.Linea;
import pe.ccpip.productor.PedidosProductorModelos.Lote;
import pe.ccpip.productor.PedidosProductorModelos.Pedido;
import pe.ccpip.productor.PedidosProductorModelos.Pedidos;

/**
 * CCPIP-66 (RF-48): los pedidos que incluyen publicaciones del productor y su estado (Figma PW-07).
 * Todo se filtra por item_pedido.productor_id, copiado al confirmar: un pedido mixto muestra solo
 * las lineas propias y sus importes, nunca las de otro productor ni los datos del comprador (RNF-05).
 * Es solo consulta; marcar listo para despacho y confirmar envios llegan con sus historias.
 */
@Service
public class PedidosProductorService {

    /** Los mas recientes primero; el historial completo llega con la paginacion de reportes. */
    static final int LIMITE = 200;

    /** Pagados: un pedido PENDIENTE_PAGO, o cancelado antes de pagarse, no es una venta. */
    private static final String SUS_PEDIDOS = """
            FROM pedido pe
            WHERE pe.confirmado_en IS NOT NULL AND pe.estado <> 'PENDIENTE_PAGO'
              AND EXISTS (SELECT 1 FROM item_pedido i WHERE i.pedido_id = pe.id AND i.productor_id = :productor)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PedidosProductorService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Pedidos listar(long productorId, Estado estado) {
        MapSqlParameterSource p = new MapSqlParameterSource("productor", productorId)
                .addValue("estado", estado == null ? null : estado.name())
                .addValue("limite", LIMITE);
        Map<Estado, Integer> conteos = new EnumMap<>(Estado.class);
        for (Estado e : Estado.values()) {
            conteos.put(e, 0);
        }
        jdbc.query("SELECT pe.estado, count(*) AS n " + SUS_PEDIDOS + "GROUP BY pe.estado", p,
                rs -> {
                    conteos.put(Estado.valueOf(rs.getString("estado")), rs.getInt("n"));
                });
        List<Long> ids = jdbc.queryForList("SELECT pe.id " + SUS_PEDIDOS + """
                  AND (CAST(:estado AS varchar) IS NULL OR pe.estado = :estado)
                ORDER BY pe.confirmado_en DESC, pe.id DESC
                LIMIT :limite""", p, Long.class);
        return new Pedidos(conteos, pedidos(productorId, ids));
    }

    /** Arma los pedidos con sus lineas propias e historial, en el orden de {@code ids}. */
    private List<Pedido> pedidos(long productorId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids).addValue("productor", productorId);
        Map<Long, List<Lote>> lotes = new HashMap<>();
        jdbc.query("""
                SELECT m.item_pedido_id, l.codigo, m.cantidad, l.fecha_vencimiento
                FROM movimiento_inventario m JOIN lote l ON l.id = m.lote_id
                JOIN item_pedido i ON i.id = m.item_pedido_id
                WHERE m.pedido_id IN (:ids) AND m.tipo = 'SALIDA_VENTA' AND i.productor_id = :productor
                ORDER BY l.fecha_vencimiento NULLS LAST, m.id""", p,
                rs -> {
                    lotes.computeIfAbsent(rs.getLong("item_pedido_id"), k -> new ArrayList<>())
                            .add(new Lote(rs.getString("codigo"), rs.getBigDecimal("cantidad"),
                                    rs.getObject("fecha_vencimiento", LocalDate.class)));
                });
        Map<Long, List<Linea>> lineas = new HashMap<>();
        jdbc.query("""
                SELECT i.id, i.pedido_id, i.producto_id, pr.nombre, i.cantidad, i.precio_unitario, i.subtotal,
                       i.comision_monto, i.neto,
                       (SELECT im.id FROM imagen im WHERE im.producto_id = i.producto_id AND im.principal) AS imagen
                FROM item_pedido i JOIN producto pr ON pr.id = i.producto_id
                WHERE i.pedido_id IN (:ids) AND i.productor_id = :productor ORDER BY i.id""", p,
                rs -> {
                    UUID imagen = rs.getObject("imagen", UUID.class);
                    lineas.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                            .add(new Linea(rs.getLong("producto_id"), rs.getString("nombre"),
                                    imagen == null ? null : ImagenService.urlMiniatura(imagen),
                                    rs.getBigDecimal("cantidad"), rs.getBigDecimal("precio_unitario"),
                                    rs.getBigDecimal("subtotal"), rs.getBigDecimal("comision_monto"), rs.getBigDecimal("neto"),
                                    lotes.getOrDefault(rs.getLong("id"), List.of())));
                });
        Map<Long, List<Cambio>> historial = new HashMap<>();
        jdbc.query("""
                SELECT h.pedido_id, h.estado, h.registrado_en FROM pedido_estado_historial h
                WHERE h.pedido_id IN (:ids) AND h.estado <> 'PENDIENTE_PAGO' ORDER BY h.registrado_en, h.id""", p,
                rs -> {
                    historial.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                            .add(new Cambio(rs.getString("estado"), rs.getObject("registrado_en", Timestamp.class).toInstant()));
                });
        Map<Long, Pedido> porId = new HashMap<>();
        jdbc.query("""
                SELECT pe.id, pe.numero, pe.estado, pe.modalidad, u.nombre AS punto, pe.confirmado_en, pe.actualizado_en
                FROM pedido pe JOIN ubicacion u ON u.id = pe.ubicacion_origen_id WHERE pe.id IN (:ids)""", p,
                rs -> {
                    long id = rs.getLong("id");
                    List<Linea> propias = lineas.getOrDefault(id, List.of());
                    porId.put(id, new Pedido(rs.getString("numero"), rs.getString("estado"), rs.getString("modalidad"),
                            rs.getString("punto"), rs.getObject("confirmado_en", Timestamp.class).toInstant(),
                            rs.getObject("actualizado_en", Timestamp.class).toInstant(),
                            sumar(propias, Linea::subtotal), sumar(propias, Linea::comision), sumar(propias, Linea::neto),
                            propias, historial.getOrDefault(id, List.of())));
                });
        return ids.stream().map(porId::get).toList();
    }

    /** Suma un importe de las lineas; nulo si alguna no lo tiene (pedido anterior a la comision). */
    private static BigDecimal sumar(List<Linea> lineas, Function<Linea, BigDecimal> importe) {
        BigDecimal total = BigDecimal.ZERO;
        for (Linea l : lineas) {
            BigDecimal v = importe.apply(l);
            if (v == null) {
                return null;
            }
            total = total.add(v);
        }
        return total;
    }
}
