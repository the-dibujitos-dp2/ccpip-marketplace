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
     * @param mermaPorLinea  merma del mes de cada linea en cada punto, solo donde hubo merma (CCPIP-430)
     */
    public record Tablero(String periodo, Ventas ventas, Comision comision, Productores productores,
                          List<VentaLinea> ventasPorLinea, List<ProductorTop> productoresTop, List<PuntoAcopio> puntos,
                          List<MermaLinea> mermaPorLinea) {
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

    /**
     * {@code stock}: unidades vendibles (lotes vigentes); {@code ventasDelMes} en soles; {@code merma}: merma del
     * mes del punto (CCPIP-430).
     */
    public record PuntoAcopio(long id, String nombre, String distrito, BigDecimal stock, long pedidosDelMes,
                              BigDecimal ventasDelMes, MermaPunto merma) {
    }

    /** CCPIP-430: nivel de la merma del mes frente a los umbrales de la linea. */
    public enum NivelMerma {
        NORMAL, ALERTA, CRITICO;

        /** Debajo de la alerta es normal; desde la alerta, alerta; desde el critico, critico. */
        static NivelMerma de(BigDecimal porcentaje, BigDecimal alerta, BigDecimal critico) {
            if (porcentaje.compareTo(critico) >= 0) {
                return CRITICO;
            }
            return porcentaje.compareTo(alerta) >= 0 ? ALERTA : NORMAL;
        }
    }

    /**
     * Merma del mes de un punto. {@code porcentaje}: merma / (merma + stock vigente), nulo si el punto no tiene
     * stock ni merma. {@code nivel}: el de su linea mas comprometida, para que el punto avise aunque el resto de
     * sus lineas diluya el porcentaje; nulo sin porcentaje.
     */
    public record MermaPunto(BigDecimal unidades, BigDecimal porcentaje, NivelMerma nivel) {
    }

    /**
     * CCPIP-430: merma del mes de una linea en un punto, coloreada con los umbrales de la linea.
     * {@code porcentaje}: merma / (merma + stock vigente de la linea en el punto), con un decimal.
     */
    public record MermaLinea(long puntoId, String punto, String codigo, String linea, BigDecimal unidades,
                             BigDecimal stock, BigDecimal porcentaje, BigDecimal umbralAlerta, BigDecimal umbralCritico,
                             NivelMerma nivel) {
    }
}
