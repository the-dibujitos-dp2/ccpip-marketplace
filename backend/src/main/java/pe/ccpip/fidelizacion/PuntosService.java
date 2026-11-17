package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.fidelizacion.BeneficiosModelos.Nivel;
import pe.ccpip.fidelizacion.FidelizacionModelos.Campana;
import pe.ccpip.fidelizacion.FidelizacionModelos.PuntosPedido;

/**
 * CCPIP-86 (RF-92): el comprador acumula puntos cuando la entrega de su pedido se completa, sea a domicilio
 * (CCPIP-319) o en el punto de acopio (CCPIP-71). Reglas (decision del 13/11):
 * <ul>
 *   <li>se calculan sobre el total de productos congelado al comprar, sin el envio y menos su descuento (CCPIP-88/89);</li>
 *   <li>rige la campana que estaba vigente cuando se confirmo el pedido, si el pedido llega a su minimo; si no,
 *       la regla base;</li>
 *   <li>CCPIP-88: el nivel del comprador, por sus pedidos entregados (este incluido), multiplica los puntos;</li>
 *   <li>un pedido que no se completa no da puntos, y los ganados nunca se quitan (aunque despues haya un producto
 *       malogrado o un reembolso): el movimiento es solo de insercion.</li>
 * </ul>
 */
@Service
public class PuntosService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CampanasService campanas;
    private final NivelesService niveles;

    public PuntosService(NamedParameterJdbcTemplate jdbc, CampanasService campanas, NivelesService niveles) {
        this.jdbc = jdbc;
        this.campanas = campanas;
        this.niveles = niveles;
    }

    /**
     * Acredita los puntos de un pedido recien entregado. Va dentro de la transaccion que registra la entrega, despues
     * del cambio de estado; registrarla de nuevo no acredita dos veces.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acreditarEntrega(long pedidoId) {
        Pedido pedido = pedido(pedidoId, "estado = 'ENTREGADO'", 0);
        if (pedido == null) {
            return;
        }
        Calculo c = calcular(pedido.base(), pedido.confirmadoEn(), pedido.entregados());
        if (c.puntos() == 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO movimiento_puntos (usuario_id, pedido_id, puntos, monto_base, campana_id, regla_puntos,
                                               regla_cada_soles, nivel, multiplicador)
                VALUES (:usuario, :pedido, :puntos, :monto, :campana, :reglaPuntos, :reglaCadaSoles, :nivel, :multiplicador)
                ON CONFLICT (pedido_id, tipo) DO NOTHING""", new MapSqlParameterSource("usuario", pedido.usuarioId())
                .addValue("pedido", pedidoId).addValue("puntos", c.puntos()).addValue("monto", pedido.base())
                .addValue("campana", c.campana() == null ? null : c.campana().id())
                .addValue("reglaPuntos", c.regla().puntos()).addValue("reglaCadaSoles", c.regla().cadaSoles())
                .addValue("nivel", c.nivel() == null ? null : c.nivel().nombre()).addValue("multiplicador", c.multiplicador()));
    }

    /**
     * Cuantos puntos ganaria el comprador con una compra de {@code base} soles en productos (sin envio y despues del
     * descuento) confirmada ahora, para mostrarlo en el carrito y al pagar. Es una estimacion: se acreditan al entregarse.
     */
    @Transactional(readOnly = true)
    public PuntosPedido estimar(long usuarioId, BigDecimal base) {
        Integer entregados = jdbc.queryForObject("SELECT count(*) FROM pedido WHERE usuario_id = :usuario AND estado = 'ENTREGADO'",
                new MapSqlParameterSource("usuario", usuarioId), Integer.class);
        return calcular(base, Instant.now(), (entregados == null ? 0 : entregados) + 1).resumen();
    }

    /** Los puntos que dara un pedido ya pagado cuando se entregue; vacio si ya se entrego, se cancelo o no dara puntos. */
    @Transactional(readOnly = true)
    public Optional<PuntosPedido> porGanar(long pedidoId) {
        Pedido pedido = pedido(pedidoId, "estado NOT IN ('ENTREGADO', 'CANCELADO', 'PENDIENTE_PAGO')", 1);
        return Optional.ofNullable(pedido).map(p -> calcular(p.base(), p.confirmadoEn(), p.entregados()).resumen())
                .filter(p -> p.puntos() > 0);
    }

    /** La base: lo que se pago por los productos, sin envio y despues del descuento. */
    private record Pedido(long usuarioId, BigDecimal base, Instant confirmadoEn, int entregados) {
    }

    /**
     * El pedido confirmado que cumple {@code condicion}, con los pedidos entregados de su comprador mas {@code extra}
     * (1 si este aun no se entrega: el nivel se cuenta con el).
     */
    private Pedido pedido(long pedidoId, String condicion, int extra) {
        return jdbc.query("""
                        SELECT usuario_id, total - descuento_monto AS base, confirmado_en,
                               (SELECT count(*) FROM pedido o WHERE o.usuario_id = p.usuario_id AND o.estado = 'ENTREGADO')
                                   AS entregados
                        FROM pedido p
                        WHERE id = :id AND confirmado_en IS NOT NULL AND %s""".formatted(condicion),
                        new MapSqlParameterSource("id", pedidoId),
                        (rs, row) -> new Pedido(rs.getLong("usuario_id"), rs.getBigDecimal("base"),
                                rs.getTimestamp("confirmado_en").toInstant(), rs.getInt("entregados") + extra))
                .stream().findFirst().orElse(null);
    }

    /**
     * Lo que da una compra: la campana que regia en el momento (si llega a su minimo) y el nivel por las entregas del
     * comprador; {@code campana} y {@code nivel} son nulos si no hubo.
     */
    private record Calculo(int puntos, Campana campana, ReglaPuntos regla, Nivel nivel, BigDecimal multiplicador) {
        PuntosPedido resumen() {
            return new PuntosPedido(puntos, campana == null ? null : campana.nombre());
        }
    }

    /** El unico calculo de puntos de una compra: lo usan la acreditacion y las estimaciones. */
    private Calculo calcular(BigDecimal base, Instant momento, int entregados) {
        Optional<Campana> campana = campanas.vigenteAl(momento).filter(c -> base.compareTo(c.montoMinimo()) >= 0);
        ReglaPuntos regla = campana.map(Campana::regla).orElse(ReglaPuntos.BASE);
        Optional<Nivel> nivel = niveles.nivelPara(entregados);
        BigDecimal multiplicador = nivel.map(Nivel::multiplicador).orElse(BigDecimal.ONE);
        return new Calculo(regla.puntosDe(base, multiplicador), campana.orElse(null), regla, nivel.orElse(null), multiplicador);
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
