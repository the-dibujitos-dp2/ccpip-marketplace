package pe.ccpip.cobertura;

import java.time.Instant;
import java.util.List;

import pe.ccpip.entrega.EntregaModelos.CapacidadDiaria;

/** CCPIP-401: proyecciones de lectura de cobertura y territorio ya registrados. */
public final class CoberturaModelos {
    private CoberturaModelos() { }
    /** {@code capacidad}: su capacidad diaria de entregas a domicilio y lo que uso hoy (CCPIP-484). */
    public record CoberturaPunto(long puntoId, String punto, List<Zona> zonas, CapacidadDiaria capacidad) { }
    public record Zona(long id, String ubigeo, String departamento, String provincia, String distrito,
                       boolean activa, Instant actualizadoEn) { }
}
