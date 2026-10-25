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
}
