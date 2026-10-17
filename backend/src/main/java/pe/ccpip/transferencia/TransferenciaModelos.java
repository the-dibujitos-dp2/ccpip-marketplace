package pe.ccpip.transferencia;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Transferencias de existencias (RNF-47, Figma PW-17, PW-18 y A-03): origen, destino, producto,
 * lote, cantidad, estado e historial.
 */
public final class TransferenciaModelos {

    private TransferenciaModelos() {
    }

    /** CCPIP-73: de su almacen a un punto. CCPIP-277: entre puntos de acopio, como solicitud. */
    public enum Tipo { ALMACEN_A_PUNTO, ENTRE_PUNTOS }

    /**
     * INICIADA: la inicio el productor, sin comprometer unidades aun. SOLICITADA: entre puntos, espera
     * a un aprobador que aun no esta definido (C8). PREPARADA: las unidades ya salieron del lote de
     * origen (CCPIP-164). DESPACHADA: en transito, no vendibles (CCPIP-362). RECIBIDA: en un lote del
     * destino (CCPIP-278).
     */
    public enum Estado {
        INICIADA, SOLICITADA, PREPARADA, DESPACHADA, RECIBIDA;

        static final Set<Estado> EN_CURSO = Set.of(INICIADA, SOLICITADA, PREPARADA, DESPACHADA);

        boolean enCurso() {
            return EN_CURSO.contains(this);
        }
    }

    /** Pestanas de PW-17: en curso o historial (cerradas). */
    public enum Vista { EN_CURSO, HISTORIAL }

    /** Desde el punto: la transferencia llega a uno de sus puntos o sale de el. */
    public enum Sentido { ENTRANTE, SALIENTE }

    public record DatosItem(
            @NotNull(message = "Elige el lote") Long loteId,
            @NotNull(message = "Escribe la cantidad")
            @DecimalMin(value = "0.001", message = "La cantidad debe ser mayor a cero")
            @Digits(integer = 9, fraction = 3, message = "Usa hasta 3 decimales") BigDecimal cantidad) {
    }

    /**
     * Lo que envia PW-18. {@code origenId} solo se usa entre puntos: desde el almacen el origen es
     * siempre el almacen del productor de la sesion (RNF-05).
     */
    public record DatosTransferencia(
            @NotNull(message = "Elige el tipo de transferencia") Tipo tipo,
            Long origenId,
            @NotNull(message = "Elige el destino") Long destinoId,
            @NotEmpty(message = "Elige al menos un lote") @Size(max = 20, message = "Máximo 20 lotes")
            List<@Valid @NotNull DatosItem> items,
            @Size(max = 255, message = "Máximo 255 caracteres") String observacion) {
    }

    /** Una ubicacion con su division politica (RNF-31: sin coordenadas ni rutas). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lugar(long id, String nombre, String tipo, String departamento, String provincia, String distrito,
                        String direccion) {
    }

    /** CCPIP-146: su almacen y los puntos de acopio activos, para elegir el destino. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ubicaciones(Lugar almacen, List<Lugar> puntos) {
    }

    /** Un lote de la transferencia. {@code loteDestino} aparece al recibir. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(long id, long productoId, String producto, String unidad, long loteOrigenId, String loteOrigen,
                       LocalDate fechaProduccion, LocalDate fechaVencimiento, BigDecimal cantidad, String loteDestino) {
    }

    /** Un cambio de estado: cual, cuando y quien. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Paso(Estado estado, Instant fecha, String usuario) {
    }

    /**
     * Una transferencia. {@code historial} solo en el detalle; {@code sentido} solo para el
     * administrador del punto.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Transferencia(long id, String codigo, Tipo tipo, Estado estado, String productor, Lugar origen,
                                Lugar destino, BigDecimal cantidad, List<Item> items, String observacion,
                                Instant creadaEn, Instant actualizadaEn, Sentido sentido, List<Paso> historial) {
    }

    /** PW-17: la pestana pedida y los contadores de ambas. */
    public record Transferencias(List<Transferencia> items, int enCurso, int historial) {
    }

    /** Del punto: lo que espera su recepcion y todo lo que entra o sale de sus puntos. */
    public record TransferenciasPunto(String ubicacion, List<Transferencia> porRecibir, List<Transferencia> items) {
    }
}
