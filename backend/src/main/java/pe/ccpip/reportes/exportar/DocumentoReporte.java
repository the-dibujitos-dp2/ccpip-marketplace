package pe.ccpip.reportes.exportar;

import java.math.BigDecimal;
import java.util.List;

/**
 * Un reporte tal como se exporta (productor: CCPIP-115 PDF, CCPIP-335 Excel; punto de acopio: CCPIP-325 y
 * siguientes): las mismas cifras que la pantalla, ya traducidas, en secciones de tabla. Los dos formatos reciben exactamente este contenido;
 * ninguno vuelve a consultar ni a calcular.
 *
 * @param titulo  titulo del documento
 * @param filtros lineas «Etiqueta: valor» con el productor, el rango y los filtros aplicados
 */
public record DocumentoReporte(String titulo, List<String> filtros, List<Seccion> secciones) {

    public DocumentoReporte {
        filtros = List.copyOf(filtros);
        secciones = List.copyOf(secciones);
    }

    /**
     * Una tabla. Cada celda es texto o una cifra; {@code moneda} dice por columna si la cifra es un importe
     * (dos decimales) o una cantidad. La ultima fila es la de totales cuando {@code conTotales}.
     */
    public record Seccion(String titulo, List<String> encabezados, List<Boolean> moneda, List<List<Object>> filas,
                   boolean conTotales, String vacio) {

        public Seccion {
            encabezados = List.copyOf(encabezados);
            moneda = List.copyOf(moneda);
            filas = filas.stream().map(List::copyOf).toList();
        }
    }

    /**
     * Una celda sin dato en una columna de cifras (un promedio bajo el minimo, una variacion sin periodo
     * anterior): queda vacia y la columna sigue siendo de cifras.
     */
    public static final String SIN_DATO = "";

    /** Una cifra lista para mostrar: cantidades sin ceros de sobra, importes con dos decimales. */
    public static String cifra(BigDecimal valor, boolean moneda) {
        java.text.DecimalFormat formato = new java.text.DecimalFormat(moneda ? "#,##0.00" : "#,##0.###",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
        return formato.format(valor);
    }
}
