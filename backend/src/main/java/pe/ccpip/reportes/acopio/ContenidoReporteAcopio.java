package pe.ccpip.reportes.acopio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoInventario;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoSalidas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoVentas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoInventario;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoSalidas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoVentas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteInventario;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteSalidas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteVentas;
import pe.ccpip.reportes.exportar.DocumentoReporte;
import pe.ccpip.reportes.exportar.DocumentoReporte.Seccion;
import pe.ccpip.reportes.exportar.TextosReporte;

/**
 * Textos del reporte exportado del punto de acopio en español o inglés: entradas, salidas, ventas e
 * inventario juntos, con los mismos filtros. Solo presentacion: las cifras llegan calculadas por
 * {@link ReportesAcopioService}, ya limitadas a sus puntos (RNF-05) y sin datos de los compradores.
 */
final class ContenidoReporteAcopio {

    private ContenidoReporteAcopio() {
    }

    static DocumentoReporte documento(ReporteEntradas entradas, ReporteSalidas salidas, ReporteVentas ventas,
                                      ReporteInventario inventario, String productor, String producto, boolean en) {
        List<String> filtros = new ArrayList<>();
        filtros.add((en ? "Collection point: " : "Punto de acopio: ") + entradas.puntos());
        filtros.add(TextosReporte.rango(entradas.desde(), entradas.hasta(), en));
        filtros.add((en ? "Grouped by: " : "Agrupado por: ") + TextosReporte.agrupacion(entradas.agrupacion(), en));
        filtros.add((en ? "Producer: " : "Productor: ") + (productor == null ? (en ? "All" : "Todos") : productor));
        filtros.add((en ? "Product: " : "Producto: ") + (producto == null ? (en ? "All" : "Todos") : producto));

        List<Seccion> secciones = new ArrayList<>();
        secciones.add(resumen(entradas, salidas, ventas, inventario, en));
        secciones.add(entradasPorPeriodo(entradas, en));
        secciones.add(entradasPorProducto(entradas, en));
        secciones.add(salidasPorPeriodo(salidas, en));
        secciones.add(salidasPorProducto(salidas, en));
        secciones.add(ventasPorPeriodo(ventas, en));
        secciones.add(ventasPorProducto(ventas, en));
        secciones.add(inventarioPorPeriodo(inventario, en));
        secciones.add(inventarioPorProducto(inventario, en));
        return new DocumentoReporte(en ? "Collection point report" : "Reporte de su punto de acopio", filtros, secciones);
    }

    /** Lo atendido y lo que sigue pendiente en cada reporte, en una sola tabla. */
    private static Seccion resumen(ReporteEntradas e, ReporteSalidas s, ReporteVentas v, ReporteInventario i,
                                   boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        filas.add(List.of(en ? "Units received" : "Unidades recibidas", e.totales().total()));
        filas.add(List.of(en ? "Transfers on the way" : "Transferencias por llegar",
                BigDecimal.valueOf(e.totales().transferenciasPendientes())));
        filas.add(List.of(en ? "Units on the way" : "Unidades por llegar", e.totales().unidadesPendientes()));
        filas.add(List.of(en ? "Units out" : "Unidades que salieron", s.totales().total()));
        filas.add(List.of(en ? "Orders delivered" : "Pedidos atendidos", BigDecimal.valueOf(v.totales().pedidosAtendidos())));
        filas.add(List.of(en ? "Orders to deliver" : "Pedidos por entregar", BigDecimal.valueOf(v.totales().pedidosPorEntregar())));
        filas.add(List.of(en ? "Closing balance (units)" : "Saldo final (unidades)", i.totales().saldoFinal()));
        filas.add(List.of(en ? "Sold units still at the point" : "Unidades vendidas aún en el punto", i.totales().porEntregar()));
        filas.add(List.of(en ? "Physical stock (units)" : "Existencia física (unidades)", i.totales().existenciaFisica()));
        return new Seccion(en ? "Summary" : "Resumen", en ? List.of("Item", "Quantity") : List.of("Concepto", "Cantidad"),
                List.of(false, false), filas, false, null);
    }

    private static Seccion entradasPorPeriodo(ReporteEntradas r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PeriodoEntradas p : r.periodos()) {
            filas.add(List.of(periodo(p.inicio(), p.fin(), r.agrupacion(), en), p.recepcion(), p.transferencias(), p.total(),
                    p.acumulado()));
        }
        var t = r.totales();
        filas.add(List.of("Total", t.recepcion(), t.transferencias(), t.total(), t.total()));
        return new Seccion(en ? "Stock in by period (units)" : "Entradas por período (unidades)",
                en ? List.of("Period", "Received", "Transfers", "Total", "Cumulative")
                        : List.of("Período", "Recepción", "Transferencias", "Total", "Acumulado"),
                sinMoneda(5), filas, true, null);
    }

    private static Seccion entradasPorProducto(ReporteEntradas r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (ProductoEntradas p : r.porProducto()) {
            filas.add(List.of(p.producto(), p.productor(), p.recepcion(), p.transferencias(), p.total()));
        }
        return new Seccion(en ? "Stock in by product (units)" : "Entradas por producto (unidades)",
                en ? List.of("Product", "Producer", "Received", "Transfers", "Total")
                        : List.of("Producto", "Productor", "Recepción", "Transferencias", "Total"),
                sinMoneda(5), filas, false, en ? "No stock came in this period." : "No hubo entradas en este período.");
    }

    private static Seccion salidasPorPeriodo(ReporteSalidas r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PeriodoSalidas p : r.periodos()) {
            filas.add(List.of(periodo(p.inicio(), p.fin(), r.agrupacion(), en), p.ventasEntregadas(), p.ventasPorEntregar(),
                    p.transferencias(), p.devoluciones(), p.merma(), p.total(), p.acumulado()));
        }
        var t = r.totales();
        filas.add(List.of("Total", t.ventasEntregadas(), t.ventasPorEntregar(), t.transferencias(), t.devoluciones(),
                t.merma(), t.total(), t.total()));
        return new Seccion(en ? "Stock out by period (units)" : "Salidas por período (unidades)",
                en ? List.of("Period", "Sale delivered", "Sale pending", "Transfers", "Returns", "Shrinkage", "Total",
                        "Cumulative")
                        : List.of("Período", "Venta entregada", "Venta pendiente", "Transferencias", "Devoluciones",
                                "Merma", "Total", "Acumulado"),
                sinMoneda(8), filas, true, null);
    }

    private static Seccion salidasPorProducto(ReporteSalidas r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (ProductoSalidas p : r.porProducto()) {
            filas.add(List.of(p.producto(), p.productor(), p.ventas(), p.transferencias(), p.devoluciones(), p.merma(),
                    p.total()));
        }
        return new Seccion(en ? "Stock out by product (units)" : "Salidas por producto (unidades)",
                en ? List.of("Product", "Producer", "Sales", "Transfers", "Returns", "Shrinkage", "Total")
                        : List.of("Producto", "Productor", "Ventas", "Transferencias", "Devoluciones", "Merma", "Total"),
                sinMoneda(7), filas, false, en ? "No stock went out this period." : "No hubo salidas en este período.");
    }

    private static Seccion ventasPorPeriodo(ReporteVentas r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PeriodoVentas p : r.periodos()) {
            filas.add(List.of(periodo(p.inicio(), p.fin(), r.agrupacion(), en), BigDecimal.valueOf(p.pedidos()),
                    BigDecimal.valueOf(p.pedidosAtendidos()), BigDecimal.valueOf(p.pedidosPorEntregar()), p.unidades(),
                    p.b2c(), p.b2b(), p.total(), p.acumulado()));
        }
        var t = r.totales();
        filas.add(List.of("Total", BigDecimal.valueOf(t.pedidos()), BigDecimal.valueOf(t.pedidosAtendidos()),
                BigDecimal.valueOf(t.pedidosPorEntregar()), t.unidades(), t.b2c(), t.b2b(), t.total(), t.total()));
        return new Seccion(en ? "Sales by period" : "Ventas por período",
                en ? List.of("Period", "Orders", "Delivered", "To deliver", "Units", "B2C (S/)", "B2B (S/)", "Total (S/)",
                        "Cumulative (S/)")
                        : List.of("Período", "Pedidos", "Atendidos", "Por entregar", "Unidades", "B2C (S/)", "B2B (S/)",
                                "Total (S/)", "Acumulado (S/)"),
                List.of(false, false, false, false, false, true, true, true, true), filas, true, null);
    }

    private static Seccion ventasPorProducto(ReporteVentas r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (ProductoVentas p : r.porProducto()) {
            filas.add(List.of(p.producto(), p.productor(), p.unidades(), p.total()));
        }
        return new Seccion(en ? "Sales by product" : "Ventas por producto",
                en ? List.of("Product", "Producer", "Units", "Total (S/)")
                        : List.of("Producto", "Productor", "Unidades", "Total (S/)"),
                List.of(false, false, false, true), filas, false,
                en ? "No sales in this period." : "No hubo ventas en este período.");
    }

    private static Seccion inventarioPorPeriodo(ReporteInventario r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PeriodoInventario p : r.periodos()) {
            filas.add(List.of(periodo(p.inicio(), p.fin(), r.agrupacion(), en), p.saldoInicial(), p.entradas(), p.salidas(),
                    p.saldoFinal()));
        }
        var t = r.totales();
        filas.add(List.of("Total", t.saldoInicial(), t.entradas(), t.salidas(), t.saldoFinal()));
        return new Seccion(en ? "Inventory by period (units)" : "Inventario por período (unidades)",
                en ? List.of("Period", "Opening", "In", "Out", "Closing")
                        : List.of("Período", "Saldo inicial", "Entradas", "Salidas", "Saldo final"),
                sinMoneda(5), filas, true, null);
    }

    private static Seccion inventarioPorProducto(ReporteInventario r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (ProductoInventario p : r.porProducto()) {
            filas.add(List.of(p.producto(), p.productor(), p.saldoInicial(), p.entradas(), p.salidas(), p.saldoFinal(),
                    p.porEntregar()));
        }
        return new Seccion(en ? "Inventory by product (units)" : "Inventario por producto (unidades)",
                en ? List.of("Product", "Producer", "Opening", "In", "Out", "Closing", "Sale pending")
                        : List.of("Producto", "Productor", "Saldo inicial", "Entradas", "Salidas", "Saldo final",
                                "Venta pendiente"),
                sinMoneda(7), filas, false, en ? "No stock in this period." : "No hubo existencias en este período.");
    }

    private static String periodo(java.time.LocalDate inicio, java.time.LocalDate fin, Agrupacion agrupacion, boolean en) {
        return TextosReporte.periodo(inicio, fin, agrupacion, en);
    }

    private static List<Boolean> sinMoneda(int columnas) {
        return java.util.Collections.nCopies(columnas, false);
    }
}
