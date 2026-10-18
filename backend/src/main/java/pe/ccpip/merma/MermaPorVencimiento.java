package pe.ccpip.merma;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCPIP-75 (RF-60): al dia siguiente de vencer, todo el saldo de un lote pasa a merma con la causa del
 * sistema (Vencimiento), en el almacen del productor y en los puntos. Los vencidos ya no eran vendibles
 * (Sprint 0); ahora ademas salen del inventario con su movimiento.
 *
 * <ul>
 *   <li>Idempotente: una sola merma automatica por lote (indice unico parcial); la revision puede
 *       correr las veces que sea.</li>
 *   <li>Cada lote va en su propia transaccion, bloqueado (SKIP LOCKED: si alguien lo esta moviendo,
 *       se toma en la siguiente revision). Lote, stock y movimiento cambian juntos (RNF-15).</li>
 *   <li>No se edita ni se borra (RNF-16); no tiene responsable: la registra el sistema.</li>
 * </ul>
 */
@Component
public class MermaPorVencimiento {

    private static final Logger LOG = LoggerFactory.getLogger(MermaPorVencimiento.class);

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    private record Vencido(long id, long productoId, long ubicacionId, BigDecimal cantidad) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MermaPorVencimiento(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${ccpip.merma.revision:PT1H}", initialDelayString = "${ccpip.merma.revision-inicial:PT5M}")
    public void revisarProgramado() {
        try {
            int lotes = revisar();
            if (lotes > 0) {
                LOG.info("Merma automatica por vencimiento: {} lotes", lotes);
            }
        } catch (RuntimeException e) {
            LOG.error("Fallo la merma automatica por vencimiento: {}", e.getMessage(), e);
        }
    }

    /** Pasa a merma los lotes vencidos con saldo. Devuelve cuantos lotes paso. */
    public int revisar() {
        List<Long> vencidos = jdbc.getJdbcTemplate().queryForList("""
                SELECT l.id FROM lote l
                WHERE l.cantidad_actual > 0 AND l.fecha_vencimiento < %s
                  AND NOT EXISTS (SELECT 1 FROM merma m WHERE m.lote_id = l.id AND m.automatica)
                ORDER BY l.fecha_vencimiento, l.id""".formatted(HOY), Long.class);
        int pasados = 0;
        for (Long id : vencidos) {
            Boolean paso = tx.execute(s -> pasar(id));
            if (Boolean.TRUE.equals(paso)) {
                pasados++;
            }
        }
        return pasados;
    }

    private boolean pasar(long loteId) {
        MapSqlParameterSource p = new MapSqlParameterSource("lote", loteId);
        Vencido lote = jdbc.query("""
                SELECT l.id, l.producto_id, l.ubicacion_id, l.cantidad_actual FROM lote l
                WHERE l.id = :lote AND l.cantidad_actual > 0 AND l.fecha_vencimiento < %s
                  AND NOT EXISTS (SELECT 1 FROM merma m WHERE m.lote_id = l.id AND m.automatica)
                FOR UPDATE OF l SKIP LOCKED""".formatted(HOY), p,
                (rs, row) -> new Vencido(rs.getLong("id"), rs.getLong("producto_id"), rs.getLong("ubicacion_id"),
                        rs.getBigDecimal("cantidad_actual")))
                .stream().findFirst().orElse(null);
        if (lote == null) {
            return false;
        }
        p.addValue("cantidad", lote.cantidad()).addValue("producto", lote.productoId()).addValue("ubicacion", lote.ubicacionId());
        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE id = :lote", p);
        // El stock agregado acompana al lote; nunca queda negativo
        jdbc.update("""
                UPDATE stock SET cantidad = greatest(cantidad - :cantidad, 0), actualizado_en = now()
                WHERE producto_id = :producto AND ubicacion_id = :ubicacion""", p);
        Long movimiento = jdbc.queryForObject("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion)
                VALUES (:producto, :ubicacion, :lote, 'MERMA', :cantidad, 'Merma automática por vencimiento') RETURNING id""",
                p, Long.class);
        jdbc.update("""
                INSERT INTO merma (movimiento_id, lote_id, motivo_id, motivo_nombre, motivo_nombre_en, automatica)
                SELECT :movimiento, :lote, id, nombre, nombre_en, true FROM motivo_merma WHERE sistema""",
                p.addValue("movimiento", movimiento));
        return true;
    }
}
