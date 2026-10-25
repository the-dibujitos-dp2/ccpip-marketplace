package pe.ccpip.reportes.productor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import pe.ccpip.reportes.ReportesModelos.Agrupacion;

/**
 * Reportes del productor por periodo (pantalla «Reportes» del productor). Todo se calcula solo con sus
 * productos (RNF-05) y sin datos de los compradores: ni nombres, ni correos, ni numeros de pedido.
 */
public final class ReportesProductorModelos {

    private ReportesProductorModelos() {
    }

    /**
     * Modalidad de compra: minorista (B2C) o mayorista (B2B). Es la tarifa que el carrito decide segun la
     * modalidad y que cada linea del pedido copia al confirmarse ({@code item_pedido.tarifa}).
     */
    public enum Modalidad { MINORISTA, MAYORISTA }

    /** Un producto del productor, para el filtro de la pantalla. */
    public record ProductoOpcion(long id, String nombre) {
    }

    /** CCPIP-114: un periodo de ventas. {@code acumulado} es el total desde el inicio del rango. */
    public record PeriodoVentas(LocalDate inicio, LocalDate fin, BigDecimal unidades, BigDecimal b2c, BigDecimal b2b,
                                BigDecimal total, BigDecimal acumulado) {
    }

    /** CCPIP-114: lo vendido de un producto en todo el rango. */
    public record ProductoVentas(long productoId, String producto, BigDecimal unidades, BigDecimal b2c, BigDecimal b2b,
                                 BigDecimal total) {
    }

    public record TotalesVentas(BigDecimal unidades, BigDecimal b2c, BigDecimal b2b, BigDecimal total) {
    }

    /**
     * CCPIP-114 (RF-25): ventas de sus productos por periodo. {@code producto} y {@code modalidad} son los
     * filtros aplicados (nulos si no se filtro); {@code productos} son las opciones del filtro.
     */
    public record ReporteVentas(LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long producto,
                                Modalidad modalidad, TotalesVentas totales, List<PeriodoVentas> periodos,
                                List<ProductoVentas> porProducto, List<ProductoOpcion> productos) {
    }

    /** CCPIP-333: un periodo de produccion: unidades ingresadas y lotes nuevos. */
    public record PeriodoProduccion(LocalDate inicio, LocalDate fin, BigDecimal unidades, long lotes,
                                    BigDecimal acumulado) {
    }

    /** CCPIP-333: lo producido de un producto en todo el rango. */
    public record ProductoProduccion(long productoId, String producto, BigDecimal unidades, long lotes) {
    }

    public record TotalesProduccion(BigDecimal unidades, long lotes) {
    }

    /**
     * CCPIP-333 (RF-223): produccion de sus productos por periodo. La modalidad de compra no aplica: la
     * produccion existe antes de venderse a una u otra tarifa.
     */
    public record ReporteProduccion(LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long producto,
                                    TotalesProduccion totales, List<PeriodoProduccion> periodos,
                                    List<ProductoProduccion> porProducto, List<ProductoOpcion> productos) {
    }

    /**
     * CCPIP-334: existencias de un periodo. {@code saldoFinal} = {@code saldoInicial} + {@code entradas} -
     * {@code vendidasB2c} - {@code vendidasB2b} - {@code merma} - {@code otrasSalidas}, siempre.
     */
    public record PeriodoInventario(LocalDate inicio, LocalDate fin, BigDecimal saldoInicial, BigDecimal entradas,
                                    BigDecimal vendidasB2c, BigDecimal vendidasB2b, BigDecimal merma,
                                    BigDecimal otrasSalidas, BigDecimal saldoFinal) {
    }

    /** CCPIP-334: el movimiento de un producto en todo el rango; {@code vendidas} suma B2C y B2B. */
    public record ProductoExistencias(long productoId, String producto, BigDecimal saldoInicial, BigDecimal entradas,
                                      BigDecimal vendidas, BigDecimal merma, BigDecimal otrasSalidas,
                                      BigDecimal saldoFinal) {
    }

    public record TotalesInventario(BigDecimal saldoInicial, BigDecimal entradas, BigDecimal vendidasB2c,
                                    BigDecimal vendidasB2b, BigDecimal merma, BigDecimal otrasSalidas,
                                    BigDecimal saldoFinal) {
    }

    /**
     * CCPIP-334 (RF-224): existencias de sus productos por periodo, en su inventario virtual unico (su
     * almacen y los puntos de acopio juntos). La modalidad se ve en lo vendido, separado en B2C y B2B.
     */
    public record ReporteInventario(LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long producto,
                                    TotalesInventario totales, List<PeriodoInventario> periodos,
                                    List<ProductoExistencias> porProducto, List<ProductoOpcion> productos) {
    }
}
