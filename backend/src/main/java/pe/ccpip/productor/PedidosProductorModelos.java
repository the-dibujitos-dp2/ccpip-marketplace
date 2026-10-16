package pe.ccpip.productor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuestas de "Pedidos" del productor (Figma PW-07, CCPIP-66). Solo sus lineas: ni el comprador,
 * ni el total del pedido, ni lo que otros productores vendieron en el mismo pedido (RNF-05).
 */
public final class PedidosProductorModelos {

    private PedidosProductorModelos() {
    }

    /** Los estados que ve el productor: un pedido sin pagar todavia no es una venta. */
    public enum Estado { CONFIRMADO, EN_PREPARACION, LISTO_PARA_RECOJO, ENTREGADO, CANCELADO }

    /** Un lote del que salio parte de la linea al confirmar la compra (CCPIP-74). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lote(String codigo, BigDecimal cantidad, LocalDate fechaVencimiento) {
    }

    /**
     * Una linea propia con los importes copiados al confirmar (RNF-13). {@code comision} y {@code neto}
     * son nulos en pedidos confirmados antes de que hubiera comision configurada (CCPIP-91).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Linea(long productoId, String producto, String imagen, BigDecimal cantidad, BigDecimal precioUnitario,
                        BigDecimal subtotal, BigDecimal comision, BigDecimal neto, List<Lote> lotes) {
    }

    /** Cuando cambio de estado. Sin nombres: ni el comprador ni el personal del punto (RNF-05). */
    public record Cambio(String estado, Instant registradoEn) {
    }

    /**
     * Un pedido que incluye publicaciones del productor. {@code subtotal}, {@code comision} y
     * {@code neto} suman solo sus lineas; {@code puntoRecojo} es el punto de acopio de origen.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Pedido(String numero, String estado, String modalidad, String puntoRecojo, Instant confirmadoEn,
                         Instant actualizadoEn, BigDecimal subtotal, BigDecimal comision, BigDecimal neto,
                         List<Linea> lineas, List<Cambio> historial) {
    }

    /** {@code conteos}: cuantos pedidos hay en cada estado, para las pestanas. */
    public record Pedidos(Map<Estado, Integer> conteos, List<Pedido> items) {
    }
}
