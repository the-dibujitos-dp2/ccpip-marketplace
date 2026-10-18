package pe.ccpip.catalogomaestro;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Catalogo maestro de la Camara (CCPIP-96, 309, 310, 311, 351, 312, 313 y 314). Son las lineas y los
 * productos de referencia; no son la oferta comercial de un productor ({@code producto}).
 */
public final class CatalogoMaestroModelos {

    private CatalogoMaestroModelos() {
    }

    /** Filtro de la consulta: solo activos, solo inactivos o todos. */
    public enum EstadoFiltro { ACTIVOS, INACTIVOS, TODOS }

    /** Codigo y nombre de un catalogo cerrado (unidad de medida). */
    public record Codigo(String codigo, String nombre) {
    }

    /** Cuantos hay activos e inactivos, sin importar el filtro aplicado. */
    public record Contadores(long activos, long inactivos) {
    }

    /**
     * Una linea del catalogo maestro. {@code productos}: productos maestros de la linea; {@code ofertas}:
     * ofertas de productores que la tienen, que se conservan aunque la linea se desactive.
     */
    public record Linea(int id, String codigo, String nombre, String nombreEn, boolean requiereVencimiento,
                        Codigo unidadSugerida, int orden, boolean activa, long productos, long ofertas,
                        Instant actualizadaEn, Instant desactivadaEn) {
    }

    public record Lineas(Contadores contadores, List<Linea> lineas) {
    }

    /**
     * Lo que se edita de una linea (CCPIP-310). El codigo no cambia: es la clave de sus traducciones.
     * {@code nombreEn} opcional: nombre aprobado en ingles. {@code orden} nulo conserva el actual.
     */
    public record DatosLinea(
            @NotBlank(message = "Escribe el nombre de la línea") @Size(max = 80, message = "Máximo 80 caracteres") String nombre,
            @Size(max = 80, message = "Máximo 80 caracteres") String nombreEn,
            @NotNull(message = "Indica si sus lotes vencen") Boolean requiereVencimiento,
            String unidadSugerida,
            @Min(value = 0, message = "El orden no puede ser negativo") @Max(value = 999, message = "Máximo 999") Integer orden) {
    }

    /** Linea nueva (CCPIP-309). {@code codigo}: letras, numeros o guion bajo; se guarda en mayusculas. */
    public record NuevaLinea(
            @NotBlank(message = "Escribe el código de la línea")
            @Pattern(regexp = "^[A-Za-z0-9_]{2,40}$", message = "Usa de 2 a 40 letras, números o guion bajo, sin espacios") String codigo,
            @NotBlank(message = "Escribe el nombre de la línea") @Size(max = 80, message = "Máximo 80 caracteres") String nombre,
            @Size(max = 80, message = "Máximo 80 caracteres") String nombreEn,
            @NotNull(message = "Indica si sus lotes vencen") Boolean requiereVencimiento,
            String unidadSugerida,
            @Min(value = 0, message = "El orden no puede ser negativo") @Max(value = 999, message = "Máximo 999") Integer orden) {

        DatosLinea datos() {
            return new DatosLinea(nombre, nombreEn, requiereVencimiento, unidadSugerida, orden);
        }
    }

    /** La linea de un producto maestro, y si sigue activa. */
    public record LineaDelProducto(String codigo, String nombre, boolean activa) {
    }

    /**
     * Un producto del catalogo maestro (CCPIP-42, CCPIP-351). No tiene precio, unidad ni stock: eso es de
     * cada oferta. {@code ofertas}: ofertas de productores vinculadas; {@code ofertasPublicadas}, las que estan
     * a la venta.
     */
    public record ProductoMaestro(long id, String codigo, String nombre, String descripcion, LineaDelProducto linea,
                                  boolean activo, long ofertas, long ofertasPublicadas, Instant actualizadoEn,
                                  Instant desactivadoEn) {
    }

    public record ProductosMaestros(Contadores contadores, List<ProductoMaestro> productos) {
    }

    /** Lo que se edita de un producto maestro (CCPIP-313). El codigo no cambia. */
    public record DatosProductoMaestro(
            @NotBlank(message = "Escribe el nombre del producto") @Size(max = 150, message = "Máximo 150 caracteres") String nombre,
            @Size(max = 1000, message = "Máximo 1000 caracteres") String descripcion,
            @NotBlank(message = "Elige la línea") String linea) {
    }

    /** Producto maestro nuevo (CCPIP-312). {@code codigo}: letras, numeros, guion o guion bajo; en mayusculas. */
    public record NuevoProductoMaestro(
            @NotBlank(message = "Escribe el código del producto")
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,39}$", message = "Usa de 2 a 40 letras, números, guion o guion bajo, sin espacios") String codigo,
            @NotBlank(message = "Escribe el nombre del producto") @Size(max = 150, message = "Máximo 150 caracteres") String nombre,
            @Size(max = 1000, message = "Máximo 1000 caracteres") String descripcion,
            @NotBlank(message = "Elige la línea") String linea) {

        DatosProductoMaestro datos() {
            return new DatosProductoMaestro(nombre, descripcion, linea);
        }
    }
}
