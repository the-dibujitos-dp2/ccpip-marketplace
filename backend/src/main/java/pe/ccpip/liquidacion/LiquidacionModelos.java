package pe.ccpip.liquidacion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * CCPIP-92 (RF-70): la liquidacion del productor en un periodo. Sale de las lineas propias de cada pedido
 * confirmado, con los importes que se copiaron al confirmar (RNF-13): bruto es el subtotal, comision la de
 * la Camara y neto lo que le corresponde. Nunca incluye lineas de otro productor ni datos del comprador.
 */
public final class LiquidacionModelos {

    private LiquidacionModelos() {
    }

    /** Una linea propia del pedido. {@code comision} es cero si el pedido se confirmo sin comision configurada. */
    public record Linea(long productoId, String producto, BigDecimal cantidad, BigDecimal precioUnitario,
                        BigDecimal bruto, BigDecimal comision, BigDecimal neto) {
    }

    /**
     * Un pedido con sus lineas propias. {@code porcentaje} es el que regia al confirmarlo, o nulo si no habia
     * comision configurada; en ese caso nada se descuenta y el neto es el bruto.
     */
    public record PedidoLiquidado(long id, String numero, String estado, Instant confirmadoEn, BigDecimal porcentaje,
                                  BigDecimal bruto, BigDecimal comision, BigDecimal neto, List<Linea> lineas) {
    }

    /** Totales del periodo: la suma exacta de los pedidos. {@code sinComision}: pedidos sin comision copiada. */
    public record Resumen(int pedidos, BigDecimal bruto, BigDecimal comision, BigDecimal neto, int sinComision) {
    }

    /** La liquidacion entre {@code desde} y {@code hasta} (fechas de Lima, inclusive) del productor de la sesion. */
    public record Liquidacion(LocalDate desde, LocalDate hasta, String productor, Resumen resumen,
                              List<PedidoLiquidado> pedidos) {
    }
}
