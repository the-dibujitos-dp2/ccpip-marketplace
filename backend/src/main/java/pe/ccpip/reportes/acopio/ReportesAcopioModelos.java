package pe.ccpip.reportes.acopio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import pe.ccpip.reportes.ReportesModelos.Agrupacion;

/**
 * Reportes del punto de acopio por periodo (pantalla «Reportes» del administrador del punto). Solo con los
 * lotes y pedidos de los puntos que administra (usuario_ubicacion, RNF-05) y sin datos de los compradores.
 * Todas las cantidades salen de los movimientos de inventario (RNF-15, ADR-10).
 */
public final class ReportesAcopioModelos {

    private ReportesAcopioModelos() {
    }

    /** Una opcion de los filtros de productor o de producto. */
    public record Opcion(long id, String nombre) {
    }

    /** CCPIP-117: un periodo de entradas: lo recibido directamente y por transferencia. */
    public record PeriodoEntradas(LocalDate inicio, LocalDate fin, BigDecimal recepcion, BigDecimal transferencias,
                                  BigDecimal total, BigDecimal acumulado) {
    }

    /** CCPIP-117: lo que entro de un producto en todo el rango. */
    public record ProductoEntradas(long productoId, String producto, String productor, BigDecimal recepcion,
                                   BigDecimal transferencias, BigDecimal total) {
    }

    /**
     * CCPIP-117: totales de entradas (atendidas) y lo que aun no llego (no atendidas): transferencias hacia
     * sus puntos pedidas en el rango que todavia no se reciben.
     */
    public record TotalesEntradas(BigDecimal recepcion, BigDecimal transferencias, BigDecimal total,
                                  long transferenciasPendientes, BigDecimal unidadesPendientes) {
    }

    /**
     * CCPIP-117 (RF-65): entradas de existencias a sus puntos por periodo. {@code productor} y {@code producto}
     * son los filtros aplicados (nulos si no se filtro); {@code productores} y {@code productos}, sus opciones.
     */
    public record ReporteEntradas(String puntos, LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long productor,
                                  Long producto, TotalesEntradas totales, List<PeriodoEntradas> periodos,
                                  List<ProductoEntradas> porProducto, List<Opcion> productores, List<Opcion> productos) {
    }

    /**
     * CCPIP-341: un periodo de salidas. Las ventas se separan en atendidas (pedido cerrado: entregado o
     * cancelado) y no atendidas (pagado pero aun por entregar); el resto son transferencias enviadas,
     * devoluciones al productor y merma.
     */
    public record PeriodoSalidas(LocalDate inicio, LocalDate fin, BigDecimal ventasEntregadas, BigDecimal ventasPorEntregar,
                                 BigDecimal transferencias, BigDecimal devoluciones, BigDecimal merma, BigDecimal total,
                                 BigDecimal acumulado) {
    }

    /** CCPIP-341: lo que salio de un producto en todo el rango; {@code ventas} suma atendidas y no atendidas. */
    public record ProductoSalidas(long productoId, String producto, String productor, BigDecimal ventas,
                                  BigDecimal transferencias, BigDecimal devoluciones, BigDecimal merma, BigDecimal total) {
    }

    public record TotalesSalidas(BigDecimal ventasEntregadas, BigDecimal ventasPorEntregar, BigDecimal transferencias,
                                 BigDecimal devoluciones, BigDecimal merma, BigDecimal total) {
    }

    /** CCPIP-341 (RF-231): salidas de existencias de sus puntos por periodo. */
    public record ReporteSalidas(String puntos, LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long productor,
                                 Long producto, TotalesSalidas totales, List<PeriodoSalidas> periodos,
                                 List<ProductoSalidas> porProducto, List<Opcion> productores, List<Opcion> productos) {
    }
}
