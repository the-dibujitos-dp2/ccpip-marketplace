package pe.ccpip.reportes.productor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import pe.ccpip.comun.Idioma;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.productor.DocumentoReporte.Seccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoInventario;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoExistencias;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteInventario;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteVentas;

/**
 * Textos del reporte exportado en español o inglés. Solo presentacion: las cifras llegan calculadas y
 * filtradas por {@link ReportesProductorService}, que ya verifico que todo es del productor (RNF-05).
 */
final class ContenidoReporte {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");

    private ContenidoReporte() {
    }

    /** Lo que exportan el PDF y el Excel: ventas, produccion e inventario con los mismos filtros. */
    static DocumentoReporte documento(String productor, ReporteVentas ventas, ReporteProduccion produccion,
                                      ReporteInventario inventario, String producto, Idioma idioma) {
        boolean en = idioma == Idioma.EN;
        List<String> filtros = new ArrayList<>();
        filtros.add((en ? "Producer: " : "Productor: ") + productor);
        filtros.add((en ? "Period: " : "Período: ") + FECHA.format(ventas.desde()) + (en ? " to " : " al ") + FECHA.format(ventas.hasta()));
        filtros.add((en ? "Grouped by: " : "Agrupado por: ") + agrupacion(ventas.agrupacion(), en));
        filtros.add((en ? "Product: " : "Producto: ") + (producto == null ? (en ? "All" : "Todos") : producto));
        filtros.add((en ? "Purchase mode (sales only): " : "Modalidad (solo ventas): ") + modalidad(ventas, en));

        List<Seccion> secciones = new ArrayList<>();
        secciones.add(ventasPorPeriodo(ventas, en));
        secciones.add(ventasPorProducto(ventas, en));
        secciones.add(produccionPorPeriodo(produccion, en));
        secciones.add(produccionPorProducto(produccion, en));
        secciones.add(inventarioPorPeriodo(inventario, en));
        secciones.add(inventarioPorProducto(inventario, en));
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

    /**
     * El nombre de un periodo: el mes, la semana de lunes a domingo (recortada al rango) o el dia. Los rangos
     * se escriben con «al» y no con un guion: la fuente usa el mismo glifo para varios guiones y, al copiar
     * o buscar en el PDF, el texto saldria con otro caracter.
     */
    static String periodo(LocalDate inicio, LocalDate fin, Agrupacion agrupacion, boolean en) {
        return switch (agrupacion) {
            case DIA -> FECHA.format(inicio);
            case SEMANA -> DIA_MES.format(inicio) + (en ? " to " : " al ") + FECHA.format(fin);
            case MES -> {
                String mes = inicio.getMonth().getDisplayName(TextStyle.FULL, en ? Locale.ENGLISH : Locale.forLanguageTag("es-PE"));
                yield Character.toUpperCase(mes.charAt(0)) + mes.substring(1) + " " + inicio.getYear();
            }
        };
    }

    private static String agrupacion(Agrupacion a, boolean en) {
        return switch (a) {
            case DIA -> en ? "day" : "día";
            case SEMANA -> en ? "week" : "semana";
            case MES -> en ? "month" : "mes";
        };
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
