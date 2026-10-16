package pe.ccpip.comision;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Comision de la Camara (CCPIP-90) y su copia en cada pedido confirmado (CCPIP-91, CCPIP-167). */
public final class ComisionModelos {

    /**
     * Unica base de calculo mientras CCPIP-101 no defina el flete: el precio de venta de los
     * productos, sin costo de envio.
     */
    public static final String BASE_PRECIO_SIN_ENVIO = "PRECIO_SIN_ENVIO";

    private ComisionModelos() {
    }

    /** VIGENTE rige hoy; PROGRAMADA entra en vigor despues; ANTERIOR ya fue reemplazada. */
    public enum EstadoCambio { VIGENTE, PROGRAMADA, ANTERIOR, CANCELADA }

    /** Un cambio del historial, con quien lo registro y, si se cancelo, quien y cuando. */
    public record Cambio(long id, BigDecimal porcentaje, String base, LocalDate vigenteDesde, String motivo,
                         String registradoPor, Instant registradoEn, EstadoCambio estado, String canceladoPor,
                         Instant canceladoEn) {
    }

    /**
     * SA-02. {@code vigente} es nula si la Camara aun no configuro ninguna comision; {@code programado},
     * si no hay un cambio pendiente. {@code hoy}: la fecha de Lima, para validar la vigencia en pantalla.
     */
    public record Estado(LocalDate hoy, String base, Cambio vigente, Cambio programado, List<Cambio> historial) {
    }

    /** Nuevo porcentaje. Rige desde {@code vigenteDesde} (hoy o despues) para los pedidos que se confirmen. */
    public record NuevaComision(
            @NotNull(message = "Indica el porcentaje")
            @DecimalMin(value = "0", message = "El porcentaje no puede ser negativo")
            @DecimalMax(value = "100", inclusive = false, message = "El porcentaje debe ser menor que 100")
            @Digits(integer = 2, fraction = 2, message = "Usa hasta 2 decimales") BigDecimal porcentaje,
            @NotNull(message = "Indica desde cuándo rige") LocalDate vigenteDesde,
            @NotBlank(message = "Explica el motivo del cambio") @Size(max = 300, message = "Máximo 300 caracteres") String motivo) {
    }

    /** La configuracion que rige al confirmar un pedido. */
    public record Vigente(long id, BigDecimal porcentaje, String base) {
    }

    /** CCPIP-167: lo que el comprador ve en su pedido confirmado. Incluida en el precio, no se suma. */
    public record ComisionPedido(BigDecimal porcentaje, String base, BigDecimal monto) {
    }

    /** CCPIP-91: lo vendido por un productor dentro del pedido y lo que le corresponde. */
    public record ParteProductor(long productorId, String productor, BigDecimal venta, BigDecimal comision,
                                 BigDecimal neto) {
    }

    /**
     * CCPIP-91: un pedido confirmado con su comision y el neto para los productores. {@code porcentaje},
     * {@code comision} y {@code neto} son nulos si se confirmo sin comision configurada.
     */
    public record PedidoComision(long id, String numero, String estado, Instant confirmadoEn, BigDecimal venta,
                                 BigDecimal porcentaje, BigDecimal comision, BigDecimal neto,
                                 List<ParteProductor> productores) {
    }

    /** Totales del rango. {@code sinComision}: pedidos confirmados sin comision copiada. */
    public record Resumen(int pedidos, BigDecimal ventas, BigDecimal comision, BigDecimal neto, int sinComision) {
    }

    /** CCPIP-91: pedidos confirmados entre {@code desde} y {@code hasta} (fechas de Lima, inclusive). */
    public record ComisionesPorPedido(LocalDate desde, LocalDate hasta, Resumen resumen, List<PedidoComision> pedidos) {
    }
}
