package pe.ccpip.reportes.productor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import pe.ccpip.comun.Idioma;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.exportar.DocumentoReporte;
import pe.ccpip.reportes.exportar.DocumentoReporte.Seccion;
import pe.ccpip.reportes.exportar.TextosReporte;
import pe.ccpip.reportes.productor.ReportesProductorModelos.FrecuenciaCompra;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoConsumo;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoInventario;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoExistencias;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteConsumo;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteInventario;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.TotalesConsumo;
import pe.ccpip.reportes.productor.ReportesProductorModelos.TramoFrecuencia;

/**
 * Textos del reporte exportado en español o inglés. Solo presentacion: las cifras llegan calculadas y
 * filtradas por {@link ReportesProductorService}, que ya verifico que todo es del productor (RNF-05).
 */
final class ContenidoReporte {

    private ContenidoReporte() {
    }

    /** Lo que exportan el PDF y el Excel: ventas, produccion, inventario y consumo con los mismos filtros. */
    static DocumentoReporte documento(String productor, ReporteVentas ventas, ReporteProduccion produccion,
                                      ReporteInventario inventario, ReporteConsumo consumo, String producto,
                                      Idioma idioma) {
        boolean en = idioma == Idioma.EN;
        List<String> filtros = new ArrayList<>();
        filtros.add((en ? "Producer: " : "Productor: ") + productor);
        filtros.add(TextosReporte.rango(ventas.desde(), ventas.hasta(), en));
        filtros.add((en ? "Grouped by: " : "Agrupado por: ") + TextosReporte.agrupacion(ventas.agrupacion(), en));
        filtros.add((en ? "Product: " : "Producto: ") + (producto == null ? (en ? "All" : "Todos") : producto));
        filtros.add((en ? "Purchase mode (sales and consumption): " : "Modalidad (ventas y consumo): ") + modalidad(ventas, en));

        List<Seccion> secciones = new ArrayList<>();
        secciones.add(ventasPorPeriodo(ventas, en));
        secciones.add(ventasPorProducto(ventas, en));
        secciones.add(produccionPorPeriodo(produccion, en));
        secciones.add(produccionPorProducto(produccion, en));
        secciones.add(inventarioPorPeriodo(inventario, en));
        secciones.add(inventarioPorProducto(inventario, en));
        // CCPIP-135: al final, para no mover las hojas que ya existian en el Excel
        secciones.add(consumoPorPeriodo(consumo, en));
        secciones.add(frecuencia(consumo, en));
        return new DocumentoReporte(en ? "Product report" : "Reporte de sus productos", filtros, secciones);
    }

    private static Seccion ventasPorPeriodo(ReporteVentas r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PeriodoVentas p : r.periodos()) {
            filas.add(List.of(periodo(p.inicio(), p.fin(), r.agrupacion(), en), p.unidades(), p.b2c(), p.b2b(), p.total(),
                    p.acumulado()));
        }
        filas.add(List.of("Total", r.totales().unidades(), r.totales().b2c(), r.totales().b2b(), r.totales().total(),
                r.totales().total()));
        return new Seccion(en ? "Sales by period" : "Ventas por período",
                en ? List.of("Period", "Units", "B2C (S/)", "B2B (S/)", "Total (S/)", "Cumulative (S/)")
                        : List.of("Período", "Unidades", "B2C (S/)", "B2B (S/)", "Total (S/)", "Acumulado (S/)"),
                List.of(false, false, true, true, true, true), filas, true, null);
    }

    private static Seccion ventasPorProducto(ReporteVentas r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (ProductoVentas p : r.porProducto()) {
            filas.add(List.of(p.producto(), p.unidades(), p.b2c(), p.b2b(), p.total()));
        }
        return new Seccion(en ? "Sales by product" : "Ventas por producto",
                en ? List.of("Product", "Units", "B2C (S/)", "B2B (S/)", "Total (S/)")
                        : List.of("Producto", "Unidades", "B2C (S/)", "B2B (S/)", "Total (S/)"),
                List.of(false, false, true, true, true), filas, false,
                en ? "No sales in this period." : "No hubo ventas en este período.");
    }

    private static Seccion produccionPorPeriodo(ReporteProduccion r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PeriodoProduccion p : r.periodos()) {
            filas.add(List.of(periodo(p.inicio(), p.fin(), r.agrupacion(), en), p.unidades(), BigDecimal.valueOf(p.lotes()),
                    p.acumulado()));
        }
        filas.add(List.of("Total", r.totales().unidades(), BigDecimal.valueOf(r.totales().lotes()), r.totales().unidades()));
        return new Seccion(en ? "Production by period" : "Producción por período",
                en ? List.of("Period", "Units in", "New batches", "Cumulative units")
                        : List.of("Período", "Unidades ingresadas", "Lotes nuevos", "Unidades acumuladas"),
                List.of(false, false, false, false), filas, true, null);
    }

    private static Seccion produccionPorProducto(ReporteProduccion r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (ProductoProduccion p : r.porProducto()) {
            filas.add(List.of(p.producto(), p.unidades(), BigDecimal.valueOf(p.lotes())));
        }
        return new Seccion(en ? "Production by product" : "Producción por producto",
                en ? List.of("Product", "Units in", "New batches") : List.of("Producto", "Unidades ingresadas", "Lotes nuevos"),
                List.of(false, false, false), filas, false,
                en ? "No production in this period." : "No hubo producción en este período.");
    }

    private static Seccion inventarioPorPeriodo(ReporteInventario r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PeriodoInventario p : r.periodos()) {
            filas.add(List.of(periodo(p.inicio(), p.fin(), r.agrupacion(), en), p.saldoInicial(), p.entradas(),
                    p.vendidasB2c(), p.vendidasB2b(), p.merma(), p.otrasSalidas(), p.saldoFinal()));
        }
        var t = r.totales();
        filas.add(List.of("Total", t.saldoInicial(), t.entradas(), t.vendidasB2c(), t.vendidasB2b(), t.merma(),
                t.otrasSalidas(), t.saldoFinal()));
        return new Seccion(en ? "Inventory by period (units)" : "Inventario por período (unidades)",
                en ? List.of("Period", "Opening", "In", "Sold B2C", "Sold B2B", "Shrinkage", "Other out", "Closing")
                        : List.of("Período", "Saldo inicial", "Entradas", "Vendidas B2C", "Vendidas B2B", "Merma",
                                "Otras salidas", "Saldo final"),
                List.of(false, false, false, false, false, false, false, false), filas, true, null);
    }

    private static Seccion inventarioPorProducto(ReporteInventario r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (ProductoExistencias p : r.porProducto()) {
            filas.add(List.of(p.producto(), p.saldoInicial(), p.entradas(), p.vendidas(), p.merma(), p.otrasSalidas(),
                    p.saldoFinal()));
        }
        return new Seccion(en ? "Inventory by product (units)" : "Inventario por producto (unidades)",
                en ? List.of("Product", "Opening", "In", "Sold", "Shrinkage", "Other out", "Closing")
                        : List.of("Producto", "Saldo inicial", "Entradas", "Vendidas", "Merma", "Otras salidas", "Saldo final"),
                List.of(false, false, false, false, false, false, false), filas, false,
                en ? "No stock in this period." : "No hubo existencias en este período.");
    }

    /** CCPIP-135: pedidos, compradores distintos, volumen e importe por modalidad. Sin datos de quien compro. */
    private static Seccion consumoPorPeriodo(ReporteConsumo r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PeriodoConsumo p : r.periodos()) {
            filas.add(List.of(periodo(p.inicio(), p.fin(), r.agrupacion(), en), BigDecimal.valueOf(p.pedidos()),
                    BigDecimal.valueOf(p.compradores()), p.unidadesB2c(), p.unidadesB2b(), p.importeB2c(), p.importeB2b()));
        }
        TotalesConsumo t = r.totales();
        // Los compradores del total son personas distintas en todo el rango, no la suma de los periodos
        filas.add(List.of("Total", BigDecimal.valueOf(t.pedidos()), BigDecimal.valueOf(t.compradores()), t.unidadesB2c(),
                t.unidadesB2b(), t.importeB2c(), t.importeB2b()));
        return new Seccion(en ? "Consumption by period" : "Consumo por período",
                en ? List.of("Period", "Orders", "Buyers", "Units B2C", "Units B2B", "B2C (S/)", "B2B (S/)")
                        : List.of("Período", "Pedidos", "Compradores", "Unidades B2C", "Unidades B2B", "B2C (S/)", "B2B (S/)"),
                List.of(false, false, false, false, false, true, true), filas, true, null);
    }

    /** CCPIP-135: cuantos compradores compraron una, dos a tres, o cuatro o mas veces en el rango. */
    private static Seccion frecuencia(ReporteConsumo r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (FrecuenciaCompra f : r.frecuencia()) {
            filas.add(List.of(tramo(f.tramo(), en), BigDecimal.valueOf(f.compradoresB2c()), BigDecimal.valueOf(f.compradoresB2b()),
                    BigDecimal.valueOf(f.compradores()), f.importe()));
        }
        TotalesConsumo t = r.totales();
        filas.add(List.of(en ? "Average per buyer" : "Promedio por comprador", "", "", t.comprasPorComprador(),
                t.compradores() == 0 ? BigDecimal.ZERO : t.importe().divide(BigDecimal.valueOf(t.compradores()), 2,
                        RoundingMode.HALF_UP)));
        return new Seccion(en ? "Purchase frequency" : "Frecuencia de compra",
                en ? List.of("Purchases in the period", "Buyers B2C", "Buyers B2B", "Buyers", "Amount (S/)")
                        : List.of("Compras en el período", "Compradores B2C", "Compradores B2B", "Compradores", "Importe (S/)"),
                List.of(false, false, false, false, true), filas, true, null);
    }

    private static String tramo(TramoFrecuencia tramo, boolean en) {
        return switch (tramo) {
            case UNA -> en ? "1 purchase" : "1 compra";
            case DOS_A_TRES -> en ? "2 to 3 purchases" : "2 a 3 compras";
            case CUATRO_O_MAS -> en ? "4 or more purchases" : "4 o más compras";
        };
    }

    private static String periodo(LocalDate inicio, LocalDate fin, Agrupacion agrupacion, boolean en) {
        return TextosReporte.periodo(inicio, fin, agrupacion, en);
    }

    private static String modalidad(ReporteVentas ventas, boolean en) {
        if (ventas.modalidad() == null) {
            return en ? "Retail (B2C) and wholesale (B2B)" : "Minorista (B2C) y mayorista (B2B)";
        }
        return switch (ventas.modalidad()) {
            case MINORISTA -> en ? "Retail (B2C)" : "Minorista (B2C)";
            case MAYORISTA -> en ? "Wholesale (B2B)" : "Mayorista (B2B)";
        };
    }
}
