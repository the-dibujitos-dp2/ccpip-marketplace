package pe.ccpip.catalogo;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Respuestas publicas del catalogo (CCPIP-52, CCPIP-53, CCPIP-152). Nunca exponen lotes ni cantidades. */
public final class CatalogoModelos {

    private CatalogoModelos() {
    }

    public record Codigo(String codigo, String nombre) {
    }

    public record ProductorResumen(long id, String comunidad, String region, boolean origenVerificado) {
    }

    public record ProductoResumen(long id, String nombre, BigDecimal precio, Codigo unidad, Codigo linea,
                                  ProductorResumen productor) {
    }

    public record Pagina<T>(List<T> items, int pagina, int tamano, long total, int totalPaginas) {
        static <T> Pagina<T> of(List<T> items, int pagina, int tamano, long total) {
            return new Pagina<>(items, pagina, tamano, total, (int) ((total + tamano - 1) / tamano));
        }
    }

    /**
     * Ficha del producto. {@code disponible} dice si hay stock sin revelar cuanto ni en que lote.
     * {@code historiaOrigen} se omite si el productor no la cargo (CCPIP-152).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductoDetalle(long id, String nombre, String descripcion, String historiaOrigen,
                                  BigDecimal precio, Codigo unidad, Codigo linea, ProductorResumen productor,
                                  boolean disponible) {
    }
}
