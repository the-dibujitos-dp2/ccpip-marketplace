package pe.ccpip.inventario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-158 (RF-127): descuenta las existencias de un pedido confirmado. Lo llama la confirmacion
 * de la compra (CCPIP-157) dentro de su propia transaccion, de modo que pedido y descuento se
 * guardan juntos o no se guarda ninguno (RNF-10).
 *
 * <ul>
 *   <li>Cada linea sale de los lotes vigentes del punto de recojo, primero el que vence antes, con
 *       un movimiento SALIDA_VENTA por lote. Lote, stock y movimiento cambian juntos (RNF-15, ADR-10).</li>
 *   <li>Sin sobreventa ni negativos (RNF-09): los lotes y el stock se bloquean y se revisan antes de
 *       escribir nada; si algo no alcanza, se rechaza el pedido entero sin descontar a medias.</li>
 *   <li>Idempotente: si el pedido ya desconto, devuelve esos movimientos sin volver a restar.</li>
 *   <li>El carrito no descuenta (RNF-35) y el recojo (CCPIP-71, CCPIP-159) no debe volver a hacerlo:
 *       la venta ya quedo explicada aqui.</li>
 * </ul>
 */
@Service
public class DescuentoExistenciasService {

    static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /** Un movimiento SALIDA_VENTA: cuanto salio de que lote para que linea del pedido. */
    public record Descuento(long movimientoId, long itemPedidoId, long productoId, long loteId, BigDecimal cantidad) {
    }

    private record Item(long id, long productoId, String nombre, BigDecimal cantidad) {
    }

    private record Lote(long id, BigDecimal cantidad) {
    }

    private record Asignacion(Item item, long loteId, BigDecimal cantidad) {
    }

    private final JdbcTemplate jdbc;

    public DescuentoExistenciasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Descuenta el pedido y devuelve sus movimientos SALIDA_VENTA. Exige una transaccion abierta: la
     * de la confirmacion del pedido.
     *
     * @param usuarioId quien confirmo la compra; queda en el movimiento, pero no se muestra al
     *                  administrador del punto (RNF-05)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Descuento> descontarPorPedido(long pedidoId, long usuarioId) {
        // El bloqueo del pedido ordena dos confirmaciones simultaneas del mismo pedido
        Long ubicacionId = jdbc.query("SELECT ubicacion_origen_id FROM pedido WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getLong("ubicacion_origen_id"), pedidoId).stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe."));

        List<Descuento> previos = descuentos(pedidoId);
        if (!previos.isEmpty()) {
            return previos;
        }

        List<Asignacion> asignaciones = asignar(pedidoId, ubicacionId);
        for (Asignacion a : asignaciones) {
            jdbc.update("UPDATE lote SET cantidad_actual = cantidad_actual - ? WHERE id = ?", a.cantidad(), a.loteId());
            jdbc.update("UPDATE stock SET cantidad = cantidad - ?, actualizado_en = now() WHERE producto_id = ? AND ubicacion_id = ?",
                    a.cantidad(), a.item().productoId(), ubicacionId);
            jdbc.update("""
                    INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, pedido_id,
                                                       item_pedido_id, usuario_id, observacion)
                    SELECT ?, ?, ?, 'SALIDA_VENTA', ?, p.id, ?, ?, 'Venta del pedido ' || p.numero
                    FROM pedido p WHERE p.id = ?""",
                    a.item().productoId(), ubicacionId, a.loteId(), a.cantidad(), a.item().id(), usuarioId, pedidoId);
        }
        return descuentos(pedidoId);
    }

    /**
     * Reparte cada linea entre los lotes vigentes del punto, primero el que vence antes, sin escribir
     * nada. Bloquea lotes y stock para que nadie mas los descuente hasta que termine la transaccion.
     */
    private List<Asignacion> asignar(long pedidoId, long ubicacionId) {
        List<Item> items = jdbc.query("""
                SELECT i.id, i.producto_id, p.nombre, i.cantidad FROM item_pedido i JOIN producto p ON p.id = i.producto_id
                WHERE i.pedido_id = ? ORDER BY i.id""",
                (rs, row) -> new Item(rs.getLong("id"), rs.getLong("producto_id"), rs.getString("nombre"),
                        rs.getBigDecimal("cantidad")), pedidoId);

        Map<Long, List<Lote>> lotesPorProducto = new HashMap<>();
        Map<Long, BigDecimal> stockPorProducto = new HashMap<>();
        List<Asignacion> asignaciones = new ArrayList<>();
        for (Item item : items) {
            List<Lote> lotes = lotesPorProducto.computeIfAbsent(item.productoId(), id -> lotesVigentes(id, ubicacionId));
            BigDecimal stock = stockPorProducto.computeIfAbsent(item.productoId(), id -> stock(id, ubicacionId));
            if (stock.compareTo(item.cantidad()) < 0) {
                throw sinExistencias(item);
            }
            stockPorProducto.put(item.productoId(), stock.subtract(item.cantidad()));

            BigDecimal pendiente = item.cantidad();
            for (int i = 0; i < lotes.size() && pendiente.signum() > 0; i++) {
                Lote lote = lotes.get(i);
                BigDecimal sale = lote.cantidad().min(pendiente);
                if (sale.signum() > 0) {
                    asignaciones.add(new Asignacion(item, lote.id(), sale));
                    lotes.set(i, new Lote(lote.id(), lote.cantidad().subtract(sale)));
                    pendiente = pendiente.subtract(sale);
                }
            }
            if (pendiente.signum() > 0) {
                throw sinExistencias(item);
            }
        }
        return asignaciones;
    }

    private List<Lote> lotesVigentes(long productoId, long ubicacionId) {
        return new ArrayList<>(jdbc.query("""
                SELECT id, cantidad_actual FROM lote
                WHERE producto_id = ? AND ubicacion_id = ? AND cantidad_actual > 0
                  AND (fecha_vencimiento IS NULL OR fecha_vencimiento >= %s)
                ORDER BY fecha_vencimiento NULLS LAST, registrado_en, id
                FOR UPDATE""".formatted(HOY),
                (rs, row) -> new Lote(rs.getLong("id"), rs.getBigDecimal("cantidad_actual")), productoId, ubicacionId));
    }

    private BigDecimal stock(long productoId, long ubicacionId) {
        return jdbc.query("SELECT cantidad FROM stock WHERE producto_id = ? AND ubicacion_id = ? FOR UPDATE",
                (rs, row) -> rs.getBigDecimal("cantidad"), productoId, ubicacionId)
                .stream().findFirst().orElse(BigDecimal.ZERO);
    }

    private static ErrorNegocio sinExistencias(Item item) {
        return new ErrorNegocio(HttpStatus.CONFLICT, "STOCK_INSUFICIENTE",
                "No hay existencias suficientes de " + item.nombre() + " en el punto de recojo.");
    }

    private List<Descuento> descuentos(long pedidoId) {
        return jdbc.query("""
                SELECT id, item_pedido_id, producto_id, lote_id, cantidad FROM movimiento_inventario
                WHERE pedido_id = ? AND tipo = 'SALIDA_VENTA' ORDER BY id""",
                (rs, row) -> new Descuento(rs.getLong("id"), rs.getLong("item_pedido_id"), rs.getLong("producto_id"),
                        rs.getLong("lote_id"), rs.getBigDecimal("cantidad")), pedidoId);
    }
}
