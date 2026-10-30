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

    /**
     * CCPIP-135: el consumo de un periodo. {@code pedidos} son las compras confirmadas con alguno de sus
     * productos y {@code compradores}, cuantas personas distintas las hicieron (solo el conteo).
     */
    public record PeriodoConsumo(LocalDate inicio, LocalDate fin, long pedidos, long compradores,
                                 BigDecimal unidadesB2c, BigDecimal unidadesB2b, BigDecimal importeB2c,
                                 BigDecimal importeB2b) {
    }

    /**
     * CCPIP-135: el consumo de todo el rango. {@code compradores} son personas distintas en el rango, no la
     * suma de los periodos. {@code ticketPromedio} = importe / pedidos y {@code comprasPorComprador} = pedidos
     * / compradores, con dos decimales (cero si no hubo compras).
     */
    public record TotalesConsumo(long pedidos, long compradores, BigDecimal unidadesB2c, BigDecimal unidadesB2b,
                                 BigDecimal importeB2c, BigDecimal importeB2b, BigDecimal importe,
                                 BigDecimal ticketPromedio, BigDecimal comprasPorComprador) {
    }

    /** CCPIP-135: cuantas veces compro una persona en el rango. */
    public enum TramoFrecuencia {
        UNA, DOS_A_TRES, CUATRO_O_MAS;

        static TramoFrecuencia de(long compras) {
            return compras <= 1 ? UNA : compras <= 3 ? DOS_A_TRES : CUATRO_O_MAS;
        }
    }

    /**
     * CCPIP-135: cuantos compradores caen en un tramo de frecuencia. {@code compradoresB2c} y
     * {@code compradoresB2b} cuentan solo sus compras de esa modalidad; {@code compradores} e {@code importe},
     * todas sus compras. Por eso alguien con una compra B2C y otra B2B esta en UNA para cada modalidad y en
     * DOS_A_TRES en el total.
     */
    public record FrecuenciaCompra(TramoFrecuencia tramo, long compradoresB2c, long compradoresB2b, long compradores,
                                   BigDecimal importe) {
    }

    /**
     * CCPIP-135 (RF-105): consumo y ventas de sus productos por modalidad y frecuencia de compra. Sin datos
     * de los compradores: solo conteos, nunca quien compro. Un pedido con lineas B2C y B2B cuenta en cada
     * modalidad, pero una sola vez en {@code pedidos}.
     */
    public record ReporteConsumo(LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long producto,
                                 Modalidad modalidad, TotalesConsumo totales, List<PeriodoConsumo> periodos,
                                 List<FrecuenciaCompra> frecuencia, List<ProductoOpcion> productos) {
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
