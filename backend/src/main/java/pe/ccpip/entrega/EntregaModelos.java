package pe.ccpip.entrega;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Respuestas de los pedidos del punto de acopio (Figma A-05). */
public final class EntregaModelos {

    private EntregaModelos() {
    }

    /**
     * Un lote del que sale parte de una linea (CCPIP-74): el que vence antes se asigna primero y uno
     * vencido nunca (RNF-14). {@code fechaVencimiento} es nula en lo que no vence, como una artesania.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoteAsignado(String codigo, BigDecimal cantidad, LocalDate fechaVencimiento) {
    }

    /**
     * Lo que se entrega de una linea: producto, comunidad que lo vende y cantidad. {@code lotes} son
     * los lotes asignados al confirmar la compra, mas proximo a vencer primero; esta vacia mientras
     * el pedido no descuenta existencias.
     */
    public record Linea(String producto, String comunidad, BigDecimal cantidad, List<LoteAsignado> lotes) {
    }

    /**
     * Un cambio de estado del pedido. {@code responsable} es quien lo hizo en el punto; es nulo
     * cuando lo registro el comprador (la confirmacion), cuyos datos no se muestran al punto (RNF-05).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cambio(String estado, Instant registradoEn, String responsable) {
    }

    /** Un pedido para recoger en el punto, sin datos del comprador. */
    public record PedidoPunto(String numero, String estado, Instant confirmadoEn, Instant actualizadoEn,
                              BigDecimal total, List<Linea> lineas, List<Cambio> historial) {
    }

    /** {@code items}: confirmados, listos para recojo y entregados hoy, en ese orden de columnas. */
    public record PedidosPunto(String ubicacion, List<PedidoPunto> items) {
    }
}
