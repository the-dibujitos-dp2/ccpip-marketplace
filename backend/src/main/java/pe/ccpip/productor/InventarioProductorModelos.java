package pe.ccpip.productor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import pe.ccpip.productor.ProductoModelos.Ubicacion;

/**
 * Respuesta de "Mi almacen" del productor (Figma PW-16, CCPIP-136 y CCPIP-162). Un solo inventario
 * virtual: su almacen y los puntos de acopio donde tiene lotes, todo derivado de los lotes, nunca de
 * un numero suelto. Solo sus productos (RNF-05).
 */
public final class InventarioProductorModelos {

    private InventarioProductorModelos() {
    }

    /**
     * Estado de un lote con saldo, como lo ve el productor en PW-16. POR_VENCER: vence dentro del
     * la anticipacion que el productor configuro en sus alertas (PW-21; la misma del inicio, PW-01).
     * Un lote vencido sigue en el inventario hasta que se retire, pero no suma a lo disponible.
     */
    public enum EstadoInventario { DISPONIBLE, POR_VENCER, VENCIDO }

    /** Cuanto hay disponible (sin vencidos) en una ubicacion. */
    public record Existencia(long ubicacionId, String ubicacion, String tipo, BigDecimal cantidad) {
    }

    /** Un lote con saldo y donde esta. El codigo del lote solo lo ve su productor. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoteInventario(long id, String codigo, long ubicacionId, String ubicacion, String tipoUbicacion,
                                 BigDecimal cantidad, LocalDate fechaProduccion, LocalDate fechaVencimiento,
                                 EstadoInventario estado) {
    }

    /**
     * Un producto con existencias. {@code total} y {@code porUbicacion} no cuentan los lotes vencidos;
     * {@code lotes} si los trae, con su estado, para que el productor los vea.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductoInventario(long id, String nombre, String miniatura, BigDecimal total,
                                     List<Existencia> porUbicacion, List<LoteInventario> lotes) {
    }

    /**
     * Indicadores de PW-16. {@code enAlmacen} y {@code enPuntosAcopio} sin vencidos;
     * {@code porPunto} desglosa lo que hay en cada punto de acopio.
     */
    public record Resumen(BigDecimal enAlmacen, int productosEnAlmacen, BigDecimal enPuntosAcopio,
                          List<Existencia> porPunto, int lotesPorVencer, int lotesVencidos) {
    }

    /**
     * {@code ubicaciones}: su almacen primero y luego los puntos de acopio donde tiene lotes con saldo.
     * {@code diasAviso}: anticipacion con la que un lote pasa a POR_VENCER.
     */
    public record Inventario(Resumen resumen, int diasAviso, List<Ubicacion> ubicaciones,
                             List<ProductoInventario> productos) {
    }
}
