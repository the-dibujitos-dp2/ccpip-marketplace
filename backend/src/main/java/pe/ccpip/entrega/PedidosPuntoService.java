package pe.ccpip.entrega;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.entrega.EntregaModelos.Cambio;
import pe.ccpip.entrega.EntregaModelos.Linea;
import pe.ccpip.entrega.EntregaModelos.PedidoPunto;
import pe.ccpip.entrega.EntregaModelos.PedidosPunto;

/**
 * Pedidos para recoger en el punto de acopio (Figma A-05, ADR-17). Cada administrador opera solo
 * los pedidos de los puntos que tiene en usuario_ubicacion; un pedido de otro punto no existe para
 * el (404, RNF-05). Cambiar el estado no toca el inventario: el descuento ya ocurrio al confirmar
 * la compra (CCPIP-158).
 */
@Service
public class PedidosPuntoService {

    static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /** Los pedidos de los puntos del administrador. */
    private static final String DEL_PUNTO = """
            FROM pedido pe
            WHERE pe.ubicacion_origen_id IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = :usuario)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;

    public PedidosPuntoService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
    }

    /** Columnas de A-05 en Sprint 0: confirmados, listos para recojo y entregados hoy (hora de Lima). */
    @Transactional(readOnly = true)
    public PedidosPunto listar(long usuarioId) {
        String ubicacion = puntos.nombres(usuarioId);
        List<Long> ids = jdbc.queryForList("SELECT pe.id " + DEL_PUNTO + """
                  AND (pe.estado IN ('CONFIRMADO', 'LISTO_PARA_RECOJO')
                       OR pe.estado = 'ENTREGADO' AND (pe.actualizado_en AT TIME ZONE 'America/Lima')::date = %s)
                ORDER BY CASE pe.estado WHEN 'CONFIRMADO' THEN 0 WHEN 'LISTO_PARA_RECOJO' THEN 1 ELSE 2 END,
                         pe.confirmado_en, pe.id""".formatted(HOY),
                new MapSqlParameterSource("usuario", usuarioId), Long.class);
        return new PedidosPunto(ubicacion, pedidos(ids));
    }

    /**
     * CCPIP-159 (RF-128): el pedido confirmado queda listo para recojo, con responsable y fecha en su
     * historial. Solo desde CONFIRMADO: marcarlo dos veces, o un pedido ya entregado, es un 409.
     */
    @Transactional
    public PedidoPunto marcarListo(long usuarioId, String numero) {
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("numero", numero);
        // El bloqueo ordena dos clics simultaneos sobre el mismo pedido
        List<Map<String, Object>> fila = jdbc.queryForList(
                "SELECT pe.id, pe.estado " + DEL_PUNTO + " AND upper(pe.numero) = :numero FOR UPDATE", p);
        if (fila.isEmpty()) {
            throw ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe en tu punto de acopio.");
        }
        long id = ((Number) fila.get(0).get("id")).longValue();
        String estado = (String) fila.get(0).get("estado");
        if (!"CONFIRMADO".equals(estado)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "ESTADO_INVALIDO", switch (estado) {
                case "LISTO_PARA_RECOJO" -> "Este pedido ya está listo para recojo.";
                case "ENTREGADO" -> "Este pedido ya fue entregado.";
                case "CANCELADO" -> "Este pedido fue cancelado.";
                default -> "Este pedido no se puede marcar como listo ahora.";
            });
        }
        p.addValue("id", id);
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO', actualizado_en = now() WHERE id = :id", p);
        jdbc.update("""
                INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id)
                VALUES (:id, 'LISTO_PARA_RECOJO', :usuario)""", p);
        return pedidos(List.of(id)).get(0);
    }

    /** Arma los pedidos con sus lineas e historial, en el orden de {@code ids}. */
    private List<PedidoPunto> pedidos(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids);
        Map<Long, List<Linea>> lineas = new HashMap<>();
        jdbc.query("""
                SELECT i.pedido_id, pr.nombre, coalesce(po.nombre_tienda, po.comunidad) AS comunidad, i.cantidad
                FROM item_pedido i JOIN producto pr ON pr.id = i.producto_id JOIN productor po ON po.id = i.productor_id
                WHERE i.pedido_id IN (:ids) ORDER BY i.id""", p,
                rs -> {
                    lineas.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                            .add(new Linea(rs.getString("nombre"), rs.getString("comunidad"), rs.getBigDecimal("cantidad")));
                });
        // El responsable es el personal del punto; el comprador que confirmo no se nombra (RNF-05)
        Map<Long, List<Cambio>> historial = new HashMap<>();
        jdbc.query("""
                SELECT h.pedido_id, h.estado, h.registrado_en,
                       CASE WHEN r.codigo = 'ADMIN_PUNTO' THEN u.nombre END AS responsable
                FROM pedido_estado_historial h
                LEFT JOIN usuario u ON u.id = h.usuario_id LEFT JOIN rol r ON r.id = u.rol_id
                WHERE h.pedido_id IN (:ids) ORDER BY h.registrado_en, h.id""", p,
                rs -> {
                    historial.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                            .add(new Cambio(rs.getString("estado"), rs.getObject("registrado_en", Timestamp.class).toInstant(),
                                    rs.getString("responsable")));
                });
        Map<Long, PedidoPunto> porId = new HashMap<>();
        jdbc.query("SELECT id, numero, estado, confirmado_en, actualizado_en, total FROM pedido WHERE id IN (:ids)", p,
                rs -> {
                    long id = rs.getLong("id");
                    porId.put(id, new PedidoPunto(rs.getString("numero"), rs.getString("estado"),
                            rs.getObject("confirmado_en", Timestamp.class).toInstant(),
                            rs.getObject("actualizado_en", Timestamp.class).toInstant(), rs.getBigDecimal("total"),
                            lineas.getOrDefault(id, List.of()), historial.getOrDefault(id, List.of())));
                });
        return ids.stream().map(porId::get).toList();
    }
}
