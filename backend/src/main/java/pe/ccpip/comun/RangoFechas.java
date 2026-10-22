package pe.ccpip.comun;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.UnaryOperator;

/**
 * Rango de fechas de Lima, inclusive, de una consulta por periodo (CCPIP-91, CCPIP-92). Sin {@code hasta}
 * es hoy; sin {@code desde}, el inicio que diga {@code porDefecto} a partir del fin. Un rango mas largo
 * que {@link #MAX_DIAS} se pide por partes: las pantallas no paginan.
 */
public record RangoFechas(LocalDate desde, LocalDate hasta) {

    public static final int MAX_DIAS = 366;

    /** El mes del fin hasta el fin. */
    public static final UnaryOperator<LocalDate> MES_EN_CURSO = fin -> fin.withDayOfMonth(1);

    public static RangoFechas resolver(LocalDate desde, LocalDate hasta, LocalDate hoy, UnaryOperator<LocalDate> porDefecto) {
        LocalDate fin = hasta == null ? hoy : hasta;
        LocalDate inicio = desde == null ? porDefecto.apply(fin) : desde;
        if (inicio.isAfter(fin)) {
            throw ErrorNegocio.invalido("RANGO_INVALIDO", "La fecha inicial debe ser anterior o igual a la final.");
        }
        if (ChronoUnit.DAYS.between(inicio, fin) >= MAX_DIAS) {
            throw ErrorNegocio.invalido("RANGO_MUY_LARGO", "Consulta como máximo un año a la vez.");
        }
        return new RangoFechas(inicio, fin);
    }
}
