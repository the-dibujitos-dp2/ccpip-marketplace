package pe.ccpip.cobertura;

import java.time.Instant;
import java.util.List;

/** CCPIP-401: proyecciones de lectura de cobertura y territorio ya registrados. */
public final class CoberturaModelos {
    private CoberturaModelos() { }
    public record CoberturaPunto(long puntoId, String punto, List<Zona> zonas) { }
    public record Zona(long id, String ubigeo, String departamento, String provincia, String distrito,
                       boolean activa, Instant actualizadoEn) { }
}
