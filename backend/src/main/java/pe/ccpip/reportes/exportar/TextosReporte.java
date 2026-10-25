package pe.ccpip.reportes.exportar;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import pe.ccpip.reportes.ReportesModelos.Agrupacion;

/** Textos comunes de los reportes exportados (productor y punto de acopio), en español o inglés. */
public final class TextosReporte {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");

    private TextosReporte() {
    }

    public static String fecha(LocalDate fecha) {
        return FECHA.format(fecha);
    }

    /** «Período: 01/05/2024 al 30/06/2024», sin guion por la misma razon que {@link #periodo}. */
    public static String rango(LocalDate desde, LocalDate hasta, boolean en) {
        return (en ? "Period: " : "Período: ") + fecha(desde) + (en ? " to " : " al ") + fecha(hasta);
    }

    /**
     * El nombre de un periodo: el mes, la semana de lunes a domingo (recortada al rango) o el dia. Los rangos
     * se escriben con «al» y no con un guion: la fuente usa el mismo glifo para varios guiones y, al copiar
     * o buscar en el PDF, el texto saldria con otro caracter.
     */
    public static String periodo(LocalDate inicio, LocalDate fin, Agrupacion agrupacion, boolean en) {
        return switch (agrupacion) {
            case DIA -> fecha(inicio);
            case SEMANA -> DIA_MES.format(inicio) + (en ? " to " : " al ") + fecha(fin);
            case MES -> {
                String mes = inicio.getMonth().getDisplayName(TextStyle.FULL, en ? Locale.ENGLISH : Locale.forLanguageTag("es-PE"));
                yield Character.toUpperCase(mes.charAt(0)) + mes.substring(1) + " " + inicio.getYear();
            }
        };
    }

    public static String agrupacion(Agrupacion a, boolean en) {
        return switch (a) {
            case DIA -> en ? "day" : "día";
            case SEMANA -> en ? "week" : "semana";
            case MES -> en ? "month" : "mes";
        };
    }
}
