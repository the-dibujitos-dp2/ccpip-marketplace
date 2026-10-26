package pe.ccpip.reportes.gestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import pe.ccpip.reportes.exportar.DocumentoReporte;
import pe.ccpip.reportes.exportar.DocumentoReporte.Seccion;
import pe.ccpip.reportes.exportar.TextosReporte;
import pe.ccpip.reportes.gestion.GestionModelos.Destino;
import pe.ccpip.reportes.gestion.GestionModelos.Flujo;
import pe.ccpip.reportes.gestion.GestionModelos.Indicador;
import pe.ccpip.reportes.gestion.GestionModelos.LineaPublicados;
import pe.ccpip.reportes.gestion.GestionModelos.PeriodoIndicadores;
import pe.ccpip.reportes.gestion.GestionModelos.ProductoCalificado;
import pe.ccpip.reportes.gestion.GestionModelos.ProductorCalificado;
import pe.ccpip.reportes.gestion.GestionModelos.ProductorVentas;
import pe.ccpip.reportes.gestion.GestionModelos.PuntoReporte;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteCalificaciones;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteIndicadores;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteOrigenDestino;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteProductores;
import pe.ccpip.reportes.gestion.GestionModelos.ReportePuntos;
import pe.ccpip.reportes.gestion.GestionModelos.Ruta;
import pe.ccpip.reportes.gestion.GestionModelos.Tendencia;

/**
 * Textos de los reportes de gestion exportados (la pestaña que se ve), en español o ingles. Solo presentacion:
 * las cifras llegan calculadas por {@link ReportesGestionService}. Un dato que no aplica (un promedio bajo el
 * minimo, una variacion sin periodo anterior) queda como celda vacia.
 */
final class ContenidoGestion {

    private ContenidoGestion() {
    }

    static DocumentoReporte productores(ReporteProductores r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (ProductorVentas p : r.productores()) {
            filas.add(List.of(p.productor(), texto(p.pueblo()), BigDecimal.valueOf(p.pedidos()), p.unidades(), p.b2c(),
                    p.b2b(), p.total(), p.participacion()));
        }
        Seccion seccion = new Seccion(en ? "Top-selling producers" : "Productores que más venden",
                en ? List.of("Producer", "People", "Orders", "Units", "B2C (S/)", "B2B (S/)", "Total (S/)", "Share (%)")
                        : List.of("Productor", "Pueblo", "Pedidos", "Unidades", "B2C (S/)", "B2B (S/)", "Total (S/)", "Parte (%)"),
                List.of(false, false, false, false, true, true, true, false), filas, false,
                en ? "No sales in this period." : "No hubo ventas en este período.");
        List<String> filtros = rango(r.desde(), r.hasta(), en);
        filtros.add((en ? "Platform sales: S/ " : "Ventas de la plataforma: S/ ") + DocumentoReporte.cifra(r.totales().total(), true));
        filtros.add((en ? "Producers with sales: " : "Productores con ventas: ") + r.productoresConVentas());
        return new DocumentoReporte(en ? "Top-selling producers" : "Productores que más venden", filtros, List.of(seccion));
    }

    static DocumentoReporte puntos(ReportePuntos r, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (PuntoReporte p : r.puntos()) {
            filas.add(List.of(p.punto(), texto(p.departamento()), BigDecimal.valueOf(p.pedidos()), p.b2c(), p.b2b(), p.total(),
                    p.participacion(), BigDecimal.valueOf(p.denuncias()), BigDecimal.valueOf(p.denunciasAbiertas())));
        }
        Seccion porPunto = new Seccion(en ? "Sales and complaints by collection point" : "Ventas y denuncias por punto de acopio",
                en ? List.of("Point", "Department", "Orders", "B2C (S/)", "B2B (S/)", "Total (S/)", "Share (%)", "Complaints", "Open")
                        : List.of("Punto", "Departamento", "Pedidos", "B2C (S/)", "B2B (S/)", "Total (S/)", "Parte (%)", "Denuncias",
                                "Abiertas"),
                List.of(false, false, false, true, true, true, false, false, false), filas, false, null);
        var d = r.denuncias();
        Seccion denuncias = new Seccion(en ? "Platform complaints" : "Denuncias de la plataforma",
                en ? List.of("Status", "Complaints") : List.of("Estado", "Denuncias"), List.of(false, false),
                List.of(List.of(en ? "Open" : "Abiertas", BigDecimal.valueOf(d.abiertas())),
                        List.of(en ? "Resolved" : "Resueltas", BigDecimal.valueOf(d.resueltas())),
                        List.of(en ? "Dismissed" : "Desestimadas", BigDecimal.valueOf(d.desestimadas())),
                        List.of("Total", BigDecimal.valueOf(d.total()))),
                true, null);
        List<String> filtros = rango(r.desde(), r.hasta(), en);
        filtros.add(en ? "A complaint counts in every point where the reported offer is stocked."
                : "Una denuncia suma en cada punto donde está la oferta denunciada.");
        return new DocumentoReporte(en ? "Collection points" : "Puntos de acopio", filtros, List.of(porPunto, denuncias));
    }

    static DocumentoReporte calificaciones(ReporteCalificaciones r, String nombreLinea, boolean en) {
        List<List<Object>> distribucion = new ArrayList<>();
        r.resumen().distribucion().forEach(e -> distribucion.add(List.of(estrellas(e.estrellas(), en),
                BigDecimal.valueOf(e.cantidad()))));
        distribucion.add(List.of("Total", BigDecimal.valueOf(r.resumen().resenas())));
        Seccion resumen = new Seccion(en ? "Ratings by stars" : "Calificaciones por estrellas",
                en ? List.of("Rating", "Ratings") : List.of("Calificación", "Calificaciones"), List.of(false, false),
                distribucion, true, null);
        List<List<Object>> productos = new ArrayList<>();
        for (ProductoCalificado p : r.productos()) {
            productos.add(List.of(p.producto(), p.productor(), cifra(p.promedio()), BigDecimal.valueOf(p.resenas()),
                    BigDecimal.valueOf(p.denuncias()), tendencia(p.tendencia(), en)));
        }
        Seccion porProducto = new Seccion(en ? "Products" : "Productos",
                en ? List.of("Product", "Producer", "Average", "Ratings", "Complaints", "Trend")
                        : List.of("Producto", "Productor", "Promedio", "Calificaciones", "Denuncias", "Tendencia"),
                Collections.nCopies(6, false), productos, false,
                en ? "No ratings in this period." : "No hubo calificaciones en este período.");
        List<List<Object>> productores = new ArrayList<>();
        for (ProductorCalificado p : r.productores()) {
            productores.add(List.of(p.productor(), texto(p.pueblo()), cifra(p.promedio()), BigDecimal.valueOf(p.resenas()),
                    BigDecimal.valueOf(p.productos()), BigDecimal.valueOf(p.denuncias()), tendencia(p.tendencia(), en)));
        }
        Seccion porProductor = new Seccion(en ? "Producers" : "Productores",
                en ? List.of("Producer", "People", "Average", "Ratings", "Products", "Complaints", "Trend")
                        : List.of("Productor", "Pueblo", "Promedio", "Calificaciones", "Productos", "Denuncias", "Tendencia"),
                Collections.nCopies(7, false), productores, false,
                en ? "No ratings in this period." : "No hubo calificaciones en este período.");
        List<String> filtros = rango(r.desde(), r.hasta(), en);
        filtros.add((en ? "Product line: " : "Línea: ") + (nombreLinea == null ? (en ? "All" : "Todas") : nombreLinea));
        filtros.add((en ? "Average: " : "Promedio: ") + (r.resumen().promedio() == null
                ? (en ? "not enough ratings" : "sin suficientes calificaciones")
                : DocumentoReporte.cifra(r.resumen().promedio(), false)));
        filtros.add(en ? "An average is shown from " + r.minimo() + " ratings; the trend compares with "
                + TextosReporte.fecha(r.anteriorDesde()) + " to " + TextosReporte.fecha(r.anteriorHasta()) + "."
                : "El promedio se muestra desde " + r.minimo() + " calificaciones; la tendencia compara con el "
                + TextosReporte.fecha(r.anteriorDesde()) + " al " + TextosReporte.fecha(r.anteriorHasta()) + ".");
        return new DocumentoReporte(en ? "Ratings" : "Calificaciones", filtros, List.of(resumen, porProducto, porProductor));
    }

    static DocumentoReporte origenDestino(ReporteOrigenDestino r, boolean en) {
        Seccion porPunto = flujos(r.porPunto(), en ? "Origin by collection point" : "Origen por punto de acopio",
                en ? List.of("Point", "Department") : List.of("Punto", "Departamento"), en);
        Seccion porProductor = flujos(r.porProductor(), en ? "Origin by producer" : "Origen por productor",
                en ? List.of("Producer", "People") : List.of("Productor", "Pueblo"), en);
        List<List<Object>> destinos = new ArrayList<>();
        for (Destino d : r.porDestino()) {
            destinos.add(List.of(d.departamento() == null ? (en ? "No address recorded" : "Sin dirección registrada") : d.departamento(),
                    BigDecimal.valueOf(d.pedidos()), BigDecimal.valueOf(d.envios()), BigDecimal.valueOf(d.recojos()), d.total(),
                    d.participacion()));
        }
        Seccion porDestino = new Seccion(en ? "Destination by department" : "Destino por departamento",
                en ? List.of("Department", "Orders", "Deliveries", "Pickups", "Total (S/)", "Share (%)")
                        : List.of("Departamento", "Pedidos", "Envíos", "Recojos", "Total (S/)", "Parte (%)"),
                List.of(false, false, false, false, true, false), destinos, false, sinVentas(en));
        List<List<Object>> rutas = new ArrayList<>();
        for (Ruta ruta : r.rutas()) {
            rutas.add(List.of(ruta.punto(), ruta.destino() == null ? (en ? "No address recorded" : "Sin dirección registrada")
                    : ruta.destino(), BigDecimal.valueOf(ruta.pedidos()), ruta.total()));
        }
        Seccion porRuta = new Seccion(en ? "Routes" : "Rutas",
                en ? List.of("From", "To", "Orders", "Total (S/)") : List.of("Desde", "Hacia", "Pedidos", "Total (S/)"),
                List.of(false, false, false, true), rutas, false, sinVentas(en));
        List<String> filtros = rango(r.desde(), r.hasta(), en);
        filtros.add((en ? "Sales: S/ " : "Ventas: S/ ") + DocumentoReporte.cifra(r.total(), true) + (en ? " in " : " en ")
                + r.pedidos() + (en ? " orders" : " pedidos"));
        return new DocumentoReporte(en ? "Sales origin and destination" : "Origen y destino de las ventas", filtros,
                List.of(porPunto, porProductor, porDestino, porRuta));
    }

    static DocumentoReporte indicadores(ReporteIndicadores r, boolean en) {
        Seccion ventas = new Seccion(en ? "Sales" : "Ventas", encabezadoIndicador(en, " (S/)"),
                List.of(false, true, true, false),
                List.of(indicador(en ? "Sales" : "Ventas", r.ventas().total()),
                        indicador(en ? "Average order" : "Ticket promedio", r.ventas().ticketPromedio())),
                false, null);
        Seccion actividad = new Seccion(en ? "Activity" : "Actividad", encabezadoIndicador(en, ""),
                Collections.nCopies(4, false),
                List.of(indicador(en ? "Orders" : "Pedidos", r.ventas().pedidos()),
                        indicador(en ? "Producers with sales" : "Productores con ventas", r.productores().conVentas()),
                        indicador(en ? "New producers" : "Productores nuevos", r.productores().nuevos()),
                        indicador(en ? "Products published in the period" : "Productos publicados en el período", r.productos().nuevos()),
                        indicador(en ? "Products sold" : "Productos con ventas", r.productos().conVentas()),
                        indicador(en ? "Buyers" : "Compradores", r.actividad().compradores()),
                        indicador(en ? "New buyer accounts" : "Cuentas nuevas", r.actividad().registros()),
                        indicador(en ? "Ratings" : "Calificaciones", r.actividad().calificaciones()),
                        indicador(en ? "Complaints" : "Denuncias", r.actividad().denuncias())),
                false, null);
        List<List<Object>> lineas = new ArrayList<>();
        for (LineaPublicados l : r.productos().porLinea()) {
            lineas.add(List.of(l.nombre(), BigDecimal.valueOf(l.publicados())));
        }
        lineas.add(List.of("Total", BigDecimal.valueOf(r.productos().publicados())));
        Seccion porLinea = new Seccion(en ? "Products on sale today by line" : "Productos publicados hoy por línea",
                en ? List.of("Line", "Products") : List.of("Línea", "Productos"), List.of(false, false), lineas, true, null);
        List<List<Object>> periodos = new ArrayList<>();
        for (PeriodoIndicadores p : r.periodos()) {
            periodos.add(List.of(TextosReporte.periodo(p.inicio(), p.fin(), r.agrupacion(), en), p.ventas(),
                    BigDecimal.valueOf(p.pedidos()), BigDecimal.valueOf(p.productoresConVentas()),
                    BigDecimal.valueOf(p.productosNuevos()), BigDecimal.valueOf(p.compradores())));
        }
        Seccion serie = new Seccion(en ? "By period" : "Por período",
                en ? List.of("Period", "Sales (S/)", "Orders", "Producers with sales", "New products", "Buyers")
                        : List.of("Período", "Ventas (S/)", "Pedidos", "Productores con ventas", "Productos nuevos", "Compradores"),
                List.of(false, true, false, false, false, false), periodos, false, null);
        List<String> filtros = rango(r.desde(), r.hasta(), en);
        filtros.add((en ? "Grouped by: " : "Agrupado por: ") + TextosReporte.agrupacion(r.agrupacion(), en));
        filtros.add((en ? "Previous period: " : "Período anterior: ") + TextosReporte.fecha(r.anteriorDesde())
                + (en ? " to " : " al ") + TextosReporte.fecha(r.anteriorHasta()));
        filtros.add((en ? "Active producers today: " : "Productores activos hoy: ") + r.productores().activos());
        return new DocumentoReporte(en ? "Platform indicators" : "Indicadores de la plataforma", filtros,
                List.of(ventas, actividad, porLinea, serie));
    }

    private static List<String> rango(LocalDate desde, LocalDate hasta, boolean en) {
        List<String> filtros = new ArrayList<>();
        filtros.add(TextosReporte.rango(desde, hasta, en));
        return filtros;
    }

    private static Seccion flujos(List<Flujo> flujos, String titulo, List<String> nombres, boolean en) {
        List<List<Object>> filas = new ArrayList<>();
        for (Flujo f : flujos) {
            filas.add(List.of(f.nombre(), texto(f.detalle()), BigDecimal.valueOf(f.pedidos()), f.total(), f.participacion()));
        }
        List<String> encabezados = new ArrayList<>(nombres);
        encabezados.addAll(en ? List.of("Orders", "Total (S/)", "Share (%)") : List.of("Pedidos", "Total (S/)", "Parte (%)"));
        return new Seccion(titulo, encabezados, List.of(false, false, false, true, false), filas, false, sinVentas(en));
    }

    private static List<String> encabezadoIndicador(boolean en, String unidad) {
        return en ? List.of("Indicator", "This period" + unidad, "Previous period" + unidad, "Change (%)")
                : List.of("Indicador", "Este período" + unidad, "Período anterior" + unidad, "Variación (%)");
    }

    private static List<Object> indicador(String nombre, Indicador i) {
        return List.of(nombre, i.valor(), i.anterior(), cifra(i.variacion()));
    }

    private static String estrellas(int n, boolean en) {
        if (en) {
            return n + (n == 1 ? " star" : " stars");
        }
        return n + (n == 1 ? " estrella" : " estrellas");
    }

    private static Object cifra(BigDecimal valor) {
        return valor == null ? DocumentoReporte.SIN_DATO : valor;
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor;
    }

    private static String tendencia(Tendencia t, boolean en) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case SUBE -> en ? "Rising" : "Sube";
            case ESTABLE -> en ? "Stable" : "Estable";
            case BAJA -> en ? "Falling" : "Baja";
        };
    }

    private static String sinVentas(boolean en) {
        return en ? "No sales in this period." : "No hubo ventas en este período.";
    }
}
