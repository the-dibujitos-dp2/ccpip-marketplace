package pe.ccpip.productor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.ccpip.imagenes.ImagenService.Imagen;

/** Contratos de la API del productor para sus productos (PW-03, PW-04, PW-05, PW-06). */
public final class ProductoModelos {

    private ProductoModelos() {
    }

    public enum Estado { BORRADOR, PUBLICADO, RETIRADO }

    public enum Modalidad { MINORISTA, MAYORISTA, AMBAS }

    /**
     * Lo que el productor escribe en PW-04. Sin descuentos ni precios promocionales (CCPIP-87): solo fija
     * precios ordinarios. {@code otros} junta cualquier campo que no sea de este contrato (p. ej. un
     * "descuento") para rechazarlo en vez de ignorarlo en silencio.
     */
    public record DatosProducto(
            @NotBlank(message = "Escribe el nombre del producto") @Size(max = 200, message = "Máximo 200 caracteres") String nombre,
            @Size(max = 2000, message = "Máximo 2000 caracteres") String descripcion,
            boolean descripcionAsistida,
            @Size(max = 4, message = "Elige hasta 4 líneas") List<String> lineas,
            String unidad,
            @Size(max = 40, message = "Máximo 40 caracteres") String contenido,
            @NotNull(message = "Elige la modalidad de venta") Modalidad modalidad,
            @DecimalMin(value = "0.10", message = "El precio debe ser mayor a S/ 0.10")
            @Digits(integer = 6, fraction = 2, message = "Usa un precio con hasta 2 decimales") BigDecimal precio,
            @DecimalMin(value = "0.10", message = "El precio debe ser mayor a S/ 0.10")
            @Digits(integer = 6, fraction = 2, message = "Usa un precio con hasta 2 decimales") BigDecimal precioMayorista,
            @Min(value = 2, message = "La cantidad mínima mayorista es 2 o más") Integer cantidadMinimaMayorista,
            boolean esMateriaPrima,
            @JsonAnySetter Map<String, Object> otros) {
    }

    public record Ubicado(String ubicacion, String tipo, BigDecimal cantidad) {
    }

    /** Fila de PW-03. {@code alerta}: LOTE_POR_VENCER, STOCK_BAJO o SIN_STOCK. */
    public record ProductoFila(long id, String nombre, String miniatura, String lineaPrincipal, String lineaCodigo, Modalidad modalidad,
                               BigDecimal precio, BigDecimal precioMayorista, BigDecimal enVenta,
                               List<Ubicado> stock, Estado estado, String alerta) {
    }

    public record ListaProductos(List<ProductoFila> items, Map<Estado, Integer> conteo) {
    }

    public record Historia(String quienes, String como, String significado, String cuidado, String titulo,
                           String textoPropuesto, String textoFinal, boolean asistida, boolean aprobada) {
    }

    public record Publicable(boolean listo, List<String> faltan) {
    }

    public record ProductoEdicion(long id, Estado estado, String nombre, String descripcion, boolean descripcionAsistida,
                                  String descripcionPropuesta, List<String> lineas, String unidad, String contenido,
                                  Modalidad modalidad, BigDecimal precio, BigDecimal precioMayorista,
                                  Integer cantidadMinimaMayorista, boolean esMateriaPrima, List<Imagen> imagenes,
                                  Historia historia, BigDecimal enVenta, Publicable publicable) {
    }

    public record DatosHistoria(
            @Size(max = 1000, message = "Máximo 1000 caracteres") String quienes,
            @Size(max = 1000, message = "Máximo 1000 caracteres") String como,
            @Size(max = 1000, message = "Máximo 1000 caracteres") String significado,
            @Size(max = 1000, message = "Máximo 1000 caracteres") String cuidado,
            @Size(max = 160, message = "Máximo 160 caracteres") String titulo,
            @Size(max = 4000, message = "Máximo 4000 caracteres") String textoFinal) {
    }

    public record AprobarHistoria(
            @Size(max = 160, message = "Máximo 160 caracteres") String titulo,
            @NotBlank(message = "Escribe la historia antes de aprobarla")
            @Size(min = 40, max = 4000, message = "La historia debe tener entre 40 y 4000 caracteres") String textoFinal,
            boolean asistida) {
    }

    public record DatosLote(
            @NotBlank(message = "Escribe el código del lote") @Size(max = 40, message = "Máximo 40 caracteres") String codigo,
            @NotNull(message = "Escribe la cantidad")
            @DecimalMin(value = "0.001", message = "La cantidad debe ser mayor a cero")
            @Digits(integer = 9, fraction = 3, message = "Usa hasta 3 decimales") BigDecimal cantidad,
            LocalDate fechaProduccion,
            LocalDate fechaVencimiento,
            @NotNull(message = "Elige dónde ingresa el lote") Long ubicacionId) {
    }

    /** Estados de PW-06. El codigo del lote solo lo ve su productor, nunca el consumidor. */
    public enum EstadoLote { VENDE_PRIMERO, DISPONIBLE, EN_ALMACEN, AGOTADO, VENCIDO }

    public record Lote(long id, String codigo, BigDecimal cantidadInicial, BigDecimal cantidadActual,
                       LocalDate fechaProduccion, LocalDate fechaVencimiento, long ubicacionId, String ubicacion,
                       String tipoUbicacion, EstadoLote estado) {
    }

    public record Lotes(long productoId, String producto, String miniatura, String lineaCodigo, boolean requiereVencimiento,
                        BigDecimal stockTotal, BigDecimal enVenta, List<Lote> items) {
    }

    public record Ubicacion(long id, String nombre, String tipo, String departamento) {
    }
}
