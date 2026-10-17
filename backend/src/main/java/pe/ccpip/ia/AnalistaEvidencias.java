package pe.ccpip.ia;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * CCPIP-50 (RF-74): analisis preliminar de las evidencias de una solicitud de autorizacion. El
 * proveedor definitivo aun no esta elegido: esta interfaz permite conectarlo sin tocar el resto.
 * Lo que devuelve es una SUGERENCIA para el subadministrador; nunca aprueba ni rechaza (RNF-30).
 * Cada hallazgo dice a que evidencia se refiere y por que, para que sea explicable (RNF-48).
 */
public interface AnalistaEvidencias {

    /** Lo que se declara de cada evidencia vigente. El formato es PDF, JPG o PNG. */
    record Evidencia(UUID id, String tipo, String titulo, String emisor, LocalDate vigenteDesde,
                     LocalDate vigenteHasta, String formato) {
    }

    record Solicitud(String producto, String organizacion, LocalDate hoy, List<Evidencia> evidencias) {
    }

    enum TipoHallazgo { FALTANTE, INCONSISTENCIA, CONFORME }

    /** {@code evidenciaId} es nulo cuando lo observado es algo que falta. */
    record Hallazgo(UUID evidenciaId, TipoHallazgo tipo, String detalle) {
    }

    enum Recomendacion { SIN_OBSERVACIONES, REVISAR }

    record Analisis(Recomendacion recomendacion, String resumen, List<Hallazgo> hallazgos) {
    }

    Analisis analizar(Solicitud solicitud);

    /** Nombre del proveedor, para registrar de donde salio cada analisis. */
    String proveedor();

    /** Verdadero si el resultado es de prueba y no un analisis real. */
    boolean simulado();
}
