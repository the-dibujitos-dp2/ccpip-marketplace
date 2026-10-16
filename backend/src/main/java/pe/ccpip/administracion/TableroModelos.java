package pe.ccpip.administracion;

import java.math.BigDecimal;
import java.util.List;

/**
 * SA-01 · Tablero de operacion del superadministrador (CCPIP-111). Solo informacion agregada:
 * ningun dato personal de consumidores ni detalle editable de tiendas, productos o pedidos.
 */
public final class TableroModelos {

    private TableroModelos() {
    }

    /**
     * @param periodo        mes calendario en Lima, "2026-10"
     * @param ventas         ventas brutas del mes frente al mes anterior
     * @param comision       comision estimada del mes con el porcentaje vigente (CCPIP-90); nula sin comision configurada
     * @param productores    productores activos y cuantos se sumaron este mes
     * @param ventasPorLinea ventas del mes por linea de producto, de mayor a menor
     * @param productoresTop productores con mas ventas del mes (hasta 5)
     * @param puntos         estado de cada punto de acopio activo
     */
    public record Tablero(String periodo, Ventas ventas, Comision comision, Productores productores,
                          List<VentaLinea> ventasPorLinea, List<ProductorTop> productoresTop, List<PuntoAcopio> puntos) {
    }

    /** {@code variacion}: porcentaje frente al mes anterior; nulo si el mes anterior no tuvo ventas. */
    public record Ventas(BigDecimal mes, BigDecimal mesAnterior, BigDecimal variacion, long pedidos) {
    }

    public record Comision(BigDecimal porcentaje, BigDecimal estimada) {
    }

    public record Productores(long activos, long nuevosDelMes) {
    }

    public record VentaLinea(String codigo, String nombre, BigDecimal monto) {
    }

    public record ProductorTop(long id, String nombre, String pueblo, BigDecimal monto) {
    }

    /** {@code stock}: unidades vendibles (lotes vigentes); {@code ventasDelMes} en soles. */
    public record PuntoAcopio(long id, String nombre, String distrito, BigDecimal stock, long pedidosDelMes,
                              BigDecimal ventasDelMes) {
    }
}
