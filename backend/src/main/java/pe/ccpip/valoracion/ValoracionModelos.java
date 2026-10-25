package pe.ccpip.valoracion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Calificaciones (CCPIP-81, CCPIP-82) y denuncias (CCPIP-83, CCPIP-84, CCPIP-116, CCPIP-323).
 * Ninguna respuesta lleva la identidad del comprador que califica o denuncia (RNF-41): ni su id, ni su
 * nombre, ni su correo. Las respuestas al productor y al subadministrador se arman ya anonimizadas.
 */
public final class ValoracionModelos {

    private ValoracionModelos() {
    }

    // --- Calificaciones ---

    /** Lo que el comprador envia al calificar una linea de su pedido. */
    public record NuevaCalificacion(@NotNull(message = "Elige el producto que calificas") Long itemId,
                                    @NotNull(message = "Elige de 1 a 5 estrellas")
                                    @Min(value = 1, message = "Elige de 1 a 5 estrellas")
                                    @Max(value = 5, message = "Elige de 1 a 5 estrellas") Integer estrellas,
                                    @Size(max = 500, message = "El comentario admite hasta 500 caracteres") String comentario) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Calificacion(int estrellas, String comentario, Instant calificadoEn) {
    }

    /** Una linea del pedido con su calificacion, o nula si aun no la califico. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LineaCalificable(long itemId, long productoId, String nombre, String tienda, BigDecimal cantidad,
                                   Calificacion calificacion) {
    }

    /** {@code calificable}: solo un pedido ENTREGADO (recibido) se califica. */
    public record CalificacionesPedido(long pedidoId, String numero, String estado, boolean calificable,
                                       int pendientes, List<LineaCalificable> lineas) {
    }

    /**
     * CCPIP-412: si el comprador puede calificar el producto desde la ficha. Solo con un pedido ENTREGADO
     * que lo incluya y aun no calificado; los pedidos cancelados o sin pagar no cuentan como compra.
     */
    public enum EstadoCalificacionProducto { PUEDE_CALIFICAR, YA_CALIFICADO, PEDIDO_EN_CURSO, SIN_COMPRA }

    /**
     * CCPIP-412: lo que la ficha necesita para ofrecer la calificacion. {@code pedidoId} e {@code itemId}
     * son la linea por calificar (PUEDE_CALIFICAR) o el pedido aun no recibido (PEDIDO_EN_CURSO);
     * {@code calificacion}, la ultima que dejo (YA_CALIFICADO).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MiCalificacionProducto(long productoId, EstadoCalificacionProducto estado, Long pedidoId,
                                         String numeroPedido, Long itemId, Calificacion calificacion) {
    }

    /** Una resena publica: sin nombre ni dato alguno del comprador. Solo el dia en que se califico. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resena(int estrellas, String comentario, LocalDate fecha) {
    }

    /** Cuantas calificaciones hay con cada numero de estrellas, de 5 a 1. */
    public record Estrellas(long cinco, long cuatro, long tres, long dos, long uno) {
    }

    /**
     * CCPIP-82 (RF-89): {@code promedio} solo cuando {@code total >= minimo} ({@code suficiente}). Sin
     * suficiente evidencia el promedio es nulo, nunca cero, y no se publican distribucion ni resenas.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResumenCalificaciones(long productoId, long total, int minimo, boolean suficiente, BigDecimal promedio,
                                        Estrellas distribucion, List<Resena> resenas) {
    }

    // --- Denuncias ---

    /** Motivos cerrados de una denuncia (RNF-32). */
    public enum Motivo { NO_CORRESPONDE, CALIDAD, ORIGEN_DUDOSO, PRACTICA_INDEBIDA, CONTENIDO_INAPROPIADO, OTRO }

    /** RECIBIDA y EN_REVISION estan abiertas; RESUELTA y DESESTIMADA, cerradas. */
    public enum EstadoDenuncia {
        RECIBIDA, EN_REVISION, RESUELTA, DESESTIMADA;

        public boolean abierta() {
            return this == RECIBIDA || this == EN_REVISION;
        }
    }

    /** Denuncia sobre un producto ({@code productoId}) o sobre una comunidad productora ({@code productorId}). */
    public record NuevaDenuncia(Long productoId, Long productorId,
                                @NotNull(message = "Elige el motivo de la denuncia") Motivo motivo,
                                @NotBlank(message = "Describe lo que ocurrió")
                                @Size(min = 10, max = 1000, message = "Describe lo que ocurrió en 10 a 1000 caracteres") String descripcion) {
    }

    public record Referencia(long id, String nombre) {
    }

    /** CCPIP-83: lo que el comprador ve de sus propias denuncias, con la respuesta del productor. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MiDenuncia(long id, Referencia producto, Referencia productor, Motivo motivo, String descripcion,
                             EstadoDenuncia estado, Instant creadaEn, String respuestaProductor, Instant respondidaEn) {
    }

    /** CCPIP-323: el descargo del productor. */
    public record NuevaRespuesta(@NotBlank(message = "Escribe tu respuesta")
                                 @Size(min = 10, max = 1000, message = "Escribe tu respuesta en 10 a 1000 caracteres") String texto) {
    }

    /**
     * CCPIP-116 (RF-91): una denuncia sobre el productor, anonimizada en el servidor. Sin denunciante,
     * sin hora exacta (solo el dia) y con correos y telefonos de la descripcion ocultos.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DenunciaRecibida(long id, Referencia producto, Motivo motivo, String descripcion, EstadoDenuncia estado,
                                   LocalDate fecha, String respuesta, Instant respondidaEn, boolean puedeResponder) {
    }

    public record ResumenRecibidas(int total, int abiertas, int sinResponder) {
    }

    public record DenunciasRecibidas(ResumenRecibidas resumen, List<DenunciaRecibida> denuncias) {
    }

    /** Cambio de estado por el subadministrador, siempre con una nota que lo explique. */
    public record CambioEstado(@NotNull(message = "Elige el nuevo estado") EstadoDenuncia estado,
                               @NotBlank(message = "Escribe una nota que explique el cambio")
                               @Size(min = 5, max = 1000, message = "La nota debe tener entre 5 y 1000 caracteres") String nota) {
    }

    public record Contadores(int recibidas, int enRevision, int resueltas, int desestimadas) {
    }

    /**
     * CCPIP-84: un productor con denuncias. La bandeja los ordena por cantidad de denuncias.
     * {@code ventaBloqueada}: el subadministrador bloqueo sus ventas (CCPIP-85, Figma S-03).
     */
    public record ProductorDenunciado(long productorId, String organizacion, String comunidad, String region, int total,
                                      int abiertas, Instant ultimaEn, boolean ventaBloqueada) {
    }

    /** Bandeja de denuncias, separada de los reportes analiticos del tablero. */
    public record BandejaDenuncias(Contadores contadores, List<ProductorDenunciado> productores) {
    }

    /**
     * Un paso del historial. {@code responsable} es el nombre del subadministrador en sus cambios de
     * estado; en el registro del comprador es nulo (identidad protegida) y en la respuesta, la comunidad.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventoDenuncia(String tipo, EstadoDenuncia estado, String rol, String responsable, String nota,
                                 Instant registradoEn) {
    }

    /**
     * Una denuncia en el expediente del subadministrador. {@code compraVerificada}: el denunciante recibio
     * algun pedido de este productor, sin decir quien es.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DenunciaAdmin(long id, Referencia producto, Motivo motivo, String descripcion, EstadoDenuncia estado,
                                Instant creadaEn, boolean compraVerificada, String respuestaProductor,
                                Instant respondidaEn, List<EstadoDenuncia> siguientes, List<EventoDenuncia> historial) {
    }

    public record ExpedienteProductor(ProductorDenunciado productor, List<DenunciaAdmin> denuncias) {
    }
}
