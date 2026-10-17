package pe.ccpip.autorizacion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lo que viaja entre la pantalla y el servidor en evidencias y revision de autorizaciones. */
public final class AutorizacionModelos {

    private AutorizacionModelos() {
    }

    /** CCPIP-36: tipos de evidencia de RF-13. */
    public enum TipoEvidencia { CERTIFICADO, DOCUMENTO, EVIDENCIA }

    /** Estado de la autorizacion; SIN_SOLICITUD si el productor aun no adjunto nada. */
    public enum Estado { SIN_SOLICITUD, SOLICITADA, APROBADA, RECHAZADA, REVOCADA }

    /**
     * Una evidencia identifica su producto, su tipo y su vigencia (RF-13). {@code vencida} se calcula
     * con la fecha de Lima. {@code url} lleva al archivo por una ruta que exige sesion (RNF-08).
     */
    public record Evidencia(UUID id, long productoId, TipoEvidencia tipo, String titulo, String emisor,
                            LocalDate vigenteDesde, LocalDate vigenteHasta, boolean vencida, String nombreArchivo,
                            String formato, int bytes, Instant subidaEn, String url) {
    }

    public record Autorizacion(Long id, Estado estado, String motivo, Instant solicitadaEn, Instant decididaEn,
                               LocalDate vigenteHasta) {
    }

    /** Vista del productor: la solicitud de su producto y sus evidencias vigentes. */
    public record EvidenciasProducto(long productoId, String producto, Autorizacion autorizacion,
                                     List<Evidencia> evidencias, boolean puedeAdjuntar, int maximo) {
    }

    /** Datos que el productor declara al subir un archivo. */
    public record NuevaEvidencia(TipoEvidencia tipo, String titulo, String emisor, LocalDate vigenteDesde,
                                 LocalDate vigenteHasta) {
    }

    // --- Revision del subadministrador (S-01, S-02) ---

    public record Fila(long id, Estado estado, long productoId, String producto, String linea, long productorId,
                       String organizacion, String pueblo, String region, int evidencias, int certificadosVigentes,
                       String recomendacion, Instant solicitadaEn, Instant decididaEn) {
    }

    public record Contadores(int pendientes, int aprobadasMes, int rechazadasMes) {
    }

    public record Bandeja(Contadores contadores, List<Fila> solicitudes) {
    }

    public record Hallazgo(UUID evidenciaId, String tipo, String detalle) {
    }

    /**
     * CCPIP-50: analisis preliminar. {@code simulado} indica un resultado de prueba que no es real;
     * {@code desactualizado}, que las evidencias cambiaron despues de pedirlo.
     */
    public record Analisis(long id, String estado, String proveedor, boolean simulado, String recomendacion,
                           String resumen, List<Hallazgo> hallazgos, Instant solicitadoEn, boolean desactualizado) {
    }

    public record Decision(long id, String decision, String motivo, String decididoPor, Instant decididoEn,
                           boolean conAnalisis, LocalDate vigenteHasta) {
    }

    public record Productor(long id, String organizacion, String comunidad, String pueblo, String region) {
    }

    public record Expediente(long id, Estado estado, String motivo, Instant solicitadaEn, Instant decididaEn,
                             LocalDate vigenteHasta, long productoId, String producto, String linea,
                             Productor productor, List<Evidencia> evidencias, int certificadosVigentes,
                             Analisis analisis, List<Decision> decisiones, boolean puedeDecidir) {
    }

    /** CCPIP-174 y CCPIP-284: la decision humana siempre lleva motivo. */
    public record NuevaDecision(
            @NotBlank(message = "Escribe el motivo de la decisión") @Size(max = 500, message = "El motivo admite hasta 500 caracteres") String motivo) {
    }
}
