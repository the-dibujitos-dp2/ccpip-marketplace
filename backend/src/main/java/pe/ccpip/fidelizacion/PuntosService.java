package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.fidelizacion.FidelizacionModelos.Campana;
import pe.ccpip.fidelizacion.FidelizacionModelos.PuntosPedido;

/**
 * CCPIP-86 (RF-92): el comprador acumula puntos cuando la entrega de su pedido se completa, sea a domicilio
 * (CCPIP-319) o en el punto de acopio (CCPIP-71). Reglas (decision del 13/11):
 * <ul>
 *   <li>se calculan sobre el total de productos congelado al comprar, sin el envio;</li>
 *   <li>rige la campana que estaba vigente cuando se confirmo el pedido, si el pedido llega a su minimo; si no,
 *       la regla base;</li>
 *   <li>un pedido que no se completa no da puntos, y los ganados nunca se quitan (aunque despues haya un producto
 *       malogrado o un reembolso): el movimiento es solo de insercion.</li>
 * </ul>
 */
@Service
public class PuntosService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CampanasService campanas;

    public PuntosService(NamedParameterJdbcTemplate jdbc, CampanasService campanas) {
        this.jdbc = jdbc;
        this.campanas = campanas;
    }

    /**
     * Acredita los puntos de un pedido recien entregado. Va dentro de la transaccion que registra la entrega, despues
     * del cambio de estado; registrarla de nuevo no acredita dos veces.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acreditarEntrega(long pedidoId) {
        record Pedido(long usuarioId, BigDecimal total, Instant confirmadoEn) {
        }
        Pedido pedido = jdbc.query("""
                        SELECT usuario_id, total, confirmado_en FROM pedido
                        WHERE id = :id AND estado = 'ENTREGADO' AND confirmado_en IS NOT NULL""",
                        new MapSqlParameterSource("id", pedidoId),
                        (rs, row) -> new Pedido(rs.getLong("usuario_id"), rs.getBigDecimal("total"),
                                rs.getTimestamp("confirmado_en").toInstant()))
                .stream().findFirst().orElse(null);
        if (pedido == null) {
            return;
        }
        Optional<Campana> campana = campanas.vigenteAl(pedido.confirmadoEn())
                .filter(c -> pedido.total().compareTo(c.montoMinimo()) >= 0);
        ReglaPuntos regla = campana.map(Campana::regla).orElse(ReglaPuntos.BASE);
        int puntos = regla.puntosDe(pedido.total());
        if (puntos == 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO movimiento_puntos (usuario_id, pedido_id, puntos, monto_base, campana_id, regla_puntos,
                                               regla_cada_soles)
                VALUES (:usuario, :pedido, :puntos, :monto, :campana, :reglaPuntos, :reglaCadaSoles)
                ON CONFLICT (pedido_id, tipo) DO NOTHING""", new MapSqlParameterSource("usuario", pedido.usuarioId())
                .addValue("pedido", pedidoId).addValue("puntos", puntos).addValue("monto", pedido.total())
                .addValue("campana", campana.map(Campana::id).orElse(null))
                .addValue("reglaPuntos", regla.puntos()).addValue("reglaCadaSoles", regla.cadaSoles()));
    }

    /** Los puntos que gano un pedido, si ya se entrego y le dio alguno. */
    @Transactional(readOnly = true)
    public Optional<PuntosPedido> delPedido(long pedidoId) {
        return jdbc.query("""
                        SELECT m.puntos, c.nombre FROM movimiento_puntos m
                        LEFT JOIN campana_puntos c ON c.id = m.campana_id
                        WHERE m.pedido_id = :id AND m.tipo = 'ACUMULACION'""", new MapSqlParameterSource("id", pedidoId),
                        (rs, row) -> new PuntosPedido(rs.getInt("puntos"), rs.getString("nombre")))
                .stream().findFirst();
    }
}
