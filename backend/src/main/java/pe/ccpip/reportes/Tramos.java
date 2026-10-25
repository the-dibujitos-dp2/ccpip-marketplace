package pe.ccpip.reportes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pe.ccpip.reportes.ReportesModelos.Agrupacion;

/**
 * Los periodos de un reporte por periodo (productor, punto de acopio) entre {@code desde} y {@code hasta} (fechas de Lima, inclusive),
 * por dia, semana (de lunes a domingo, ISO 8601) o mes, igual que los reportes de la plataforma (SA-05).
 * El primero y el ultimo se recortan al rango. Estan todos, aun sin movimiento, para que la serie no
 * tenga huecos.
 */
public final class Tramos {

    /** Un periodo ya recortado al rango. */
    public record Tramo(LocalDate inicio, LocalDate fin) {
    }

    private final Agrupacion agrupacion;
    private final List<Tramo> lista = new ArrayList<>();
    /** Inicio sin recortar de cada periodo -> su posicion en la lista. */
    private final Map<LocalDate, Integer> posiciones = new LinkedHashMap<>();

    public Tramos(LocalDate desde, LocalDate hasta, Agrupacion agrupacion) {
        this.agrupacion = agrupacion;
        for (LocalDate inicio = truncar(desde); !inicio.isAfter(hasta); inicio = finDe(inicio).plusDays(1)) {
            posiciones.put(inicio, lista.size());
            LocalDate fin = finDe(inicio);
            lista.add(new Tramo(inicio.isBefore(desde) ? desde : inicio, fin.isAfter(hasta) ? hasta : fin));
        }
    }

    public List<Tramo> lista() {
        return lista;
    }

    public int cantidad() {
        return lista.size();
    }

    /** La posicion del periodo que contiene {@code fecha}; la fecha debe estar en el rango. */
    public int posicion(LocalDate fecha) {
        Integer i = posiciones.get(truncar(fecha));
        if (i == null) {
            throw new IllegalArgumentException("Fecha fuera del rango del reporte: " + fecha);
        }
        return i;
    }

    private LocalDate truncar(LocalDate fecha) {
        return switch (agrupacion) {
            case DIA -> fecha;
            case SEMANA -> fecha.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MES -> fecha.withDayOfMonth(1);
        };
    }

    private LocalDate finDe(LocalDate inicio) {
        return switch (agrupacion) {
            case DIA -> inicio;
            case SEMANA -> inicio.plusDays(6);
            case MES -> inicio.plusMonths(1).minusDays(1);
        };
    }
}
