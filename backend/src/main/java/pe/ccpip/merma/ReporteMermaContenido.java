package pe.ccpip.merma;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import pe.ccpip.comun.Idioma;
import pe.ccpip.merma.MermaModelos.Merma;
import pe.ccpip.merma.MermaModelos.ReporteMerma;

/** Presentation-only report text. Authorization and filtering remain in {@link MermaService}. */
final class ReporteMermaContenido {
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Lima"));
    private ReporteMermaContenido() { }

    /**
     * Canonical export rows. Renderers receive typed cells rather than parsing a presentation string,
     * so field content such as "golpe | humedad" remains a single Detail cell.
     */
    static List<Fila> filas(ReporteMerma reporte) {
        Idioma idioma = reporte.idioma();
        List<Fila> filas = new java.util.ArrayList<>();
        filas.add(new Fila(List.of(idioma == Idioma.EN ? "Shrinkage report" : "Reporte de merma")));
        filas.add(new Fila(encabezados(idioma)));
        if (reporte.items().isEmpty()) {
            filas.add(new Fila(List.of(idioma == Idioma.EN ? "No shrinkage for the selected filters."
                    : "No hay mermas para los filtros seleccionados.")));
        } else {
            for (Merma m : reporte.items()) {
                filas.add(new Fila(List.of(FECHA.format(m.registradoEn()), m.producto(), m.lote(), m.ubicacion(),
                        cantidad(m.cantidad()), causa(m, idioma), texto(m.detalle()))));
            }
        }
        return filas;
    }

    static List<String> encabezados(Idioma idioma) {
        return idioma == Idioma.EN ? List.of("Date", "Product", "Batch", "Location", "Quantity", "Cause", "Detail")
                : List.of("Fecha", "Producto", "Lote", "Ubicación", "Cantidad", "Causa", "Detalle");
    }

    /** A row's cells are immutable; PDF text is derived only for display, never reparsed. */
    record Fila(List<String> celdas) {
        Fila {
            celdas = List.copyOf(celdas);
        }

        String texto() {
            return String.join(" | ", celdas);
        }
    }

    /** The immutable copied cause fields, not a current catalog lookup, determine the localized label. */
    static String causa(Merma merma, Idioma idioma) {
        return idioma == Idioma.EN && merma.motivoEn() != null && !merma.motivoEn().isBlank() ? merma.motivoEn() : merma.motivo();
    }

    private static String cantidad(BigDecimal cantidad) { return cantidad.stripTrailingZeros().toPlainString(); }
    private static String texto(String texto) { return texto == null ? "" : texto; }
}
