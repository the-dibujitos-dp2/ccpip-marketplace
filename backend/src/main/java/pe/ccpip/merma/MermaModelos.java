package pe.ccpip.merma;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import pe.ccpip.comun.Idioma;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Merma de existencias (CCPIP-75, CCPIP-76; Figma A-04, A-07) y su catalogo de causas (CCPIP-77,
 * 298, 299 y 300).
 */
public final class MermaModelos {

    private MermaModelos() {
    }

    // ---------- Catalogo de causas ----------

    /**
     * Una causa del catalogo. {@code sistema}: la usa la merma automatica por vencimiento (no se
     * desactiva). {@code usos}: cuantas mermas la tienen; cambiarla no las altera.
     */
    public record Motivo(long id, String nombre, String nombreEn, boolean requiereDetalle, boolean activo,
                         boolean sistema, long usos, Instant actualizadoEn) {
    }

    /** Alta o cambio de una causa (SA, CCPIP-298 y CCPIP-299). */
    public record DatosMotivo(
            @NotBlank(message = "Escribe el nombre en español") @Size(max = 60, message = "Máximo 60 caracteres") String nombre,
            @NotBlank(message = "Escribe el nombre en inglés") @Size(max = 60, message = "Máximo 60 caracteres") String nombreEn,
            boolean requiereDetalle) {
    }

    /** Lo que ofrece el formulario de merma: solo las causas activas. */
    public record Causa(long id, String nombre, String nombreEn, boolean requiereDetalle) {
    }

    // ---------- Merma ----------

    /**
     * Lo que envia "Marcar como merma" (A-04). {@code confirmada} es la confirmacion expresa del
     * resumen (A-07): sin ella no se marca nada. {@code clave}: un UUID por envio (RNF-15).
     */
    public record DatosMerma(
            @NotNull(message = "Elige el lote") Long loteId,
            @NotNull(message = "Escribe la cantidad")
            @DecimalMin(value = "0.001", message = "La cantidad debe ser mayor a cero")
            @Digits(integer = 9, fraction = 3, message = "Usa hasta 3 decimales") BigDecimal cantidad,
            @NotNull(message = "Elige la causa") Long motivoId,
            @Size(max = 200, message = "Máximo 200 caracteres") String detalle,
            @NotNull(message = "Falta la clave del envío") UUID clave,
            boolean confirmada) {
    }

    /**
     * Una merma, como la muestra A-04. {@code motivo} y {@code motivoEn} son los nombres con los que se
     * marco. {@code registradoPor} es nulo en la automatica y, para el productor, cuando la registro el
     * punto de acopio (RNF-05). {@code productor} es el nombre publico del productor del lote (su tienda o,
     * sin ella, su comunidad; CCPIP-423), nunca sus datos de contacto.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Merma(long id, Instant registradoEn, long productoId, String producto, String lote, String ubicacion,
                        BigDecimal cantidad, String motivo, String motivoEn, String detalle, boolean automatica,
                        String registradoPor, long productorId, String productor) {
    }

    /** La merma recien marcada y el saldo que le queda al lote. {@code repetida}: el mismo envio otra vez. */
    public record MermaRegistrada(Merma merma, BigDecimal saldo, boolean repetida) {
    }

    /**
     * Una pagina de merma, la mas reciente primero. {@code ubicacion}: los puntos del administrador.
     * {@code opciones}: lo que se puede elegir en los filtros del punto (CCPIP-422); nulo para el productor.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaginaMerma(String ubicacion, List<Merma> items, int pagina, int tamano, long total, int totalPaginas,
                              OpcionesMerma opciones) {
    }

    /**
     * CCPIP-422: los filtros de la merma del punto, todos opcionales y combinables. {@code causa} es el id
     * del catalogo; {@code desde} y {@code hasta}, dias de Lima inclusive.
     */
    public record FiltroMerma(Long causa, Long producto, Long productor, LocalDate desde, LocalDate hasta) {
    }

    /** Una opcion de un filtro. {@code nombreEn} solo en las causas. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Opcion(long id, String nombre, String nombreEn) {
    }

    /** CCPIP-422: las causas, productos y productores con merma en sus puntos, sin aplicar los filtros. */
    public record OpcionesMerma(List<Opcion> causas, List<Opcion> productos, List<Opcion> productores) {
    }

    /** Filtros textuales del reporte. No transportan ids de producto, lote ni ubicación: el alcance sale de la sesión. */
    public record FiltroReporteMerma(LocalDate desde, LocalDate hasta, String lote, String producto, String ubicacion) {
    }

    /** Todas las filas autorizadas para una exportación de merma. */
    public record ReporteMerma(List<Merma> items, Idioma idioma) {
    }
}
