package pe.ccpip.reportes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.ReportesModelos.Desglose;
import pe.ccpip.reportes.ReportesModelos.Periodo;
import pe.ccpip.reportes.ReportesModelos.Reporte;

/**
 * Reportes acumulados por periodo del superadministrador (SA-05). Cada metrica aporta su consulta de
 * origen, que devuelve {@code fecha} (de Lima), {@code tarifa} y {@code monto}; este servicio arma la
 * serie por dia, semana o mes, la separa en B2C y B2B y acumula. Lee solo importes copiados al
 * confirmar (RNF-13), nunca los recalcula.
 */
@Service
public class ReportesService {

    /** Un rango mas largo se pide por partes. */
    static final int MAX_DIAS = 366;

    /**
     * CCPIP-345 (RF-235): ventas de la plataforma, el subtotal de cada linea de los pedidos confirmados
     * (pagados) y no cancelados, por la fecha de confirmacion.
     */
    static final String VENTAS = """
            SELECT (pe.confirmado_en AT TIME ZONE 'America/Lima')::date AS fecha, i.tarifa, i.subtotal AS monto
            FROM item_pedido i
            JOIN pedido pe ON pe.id = i.pedido_id
            WHERE pe.confirmado_en IS NOT NULL AND pe.estado <> 'CANCELADO'
              AND (pe.confirmado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta""";

    private final NamedParameterJdbcTemplate jdbc;

    public ReportesService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Reporte ventas(LocalDate desde, LocalDate hasta, Agrupacion agrupacion) {
        return serie("VENTAS", VENTAS, desde, hasta, agrupacion);
    }

    /**
     * Sin fechas, el año en curso hasta hoy (fecha de Lima); sin agrupacion, por mes. {@code origen} usa
     * los parametros {@code :desde} y {@code :hasta}.
     */
    Reporte serie(String metrica, String origen, LocalDate desde, LocalDate hasta, Agrupacion agrupacion) {
        LocalDate hoy = jdbc.getJdbcTemplate()
                .queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
        LocalDate fin = hasta == null ? hoy : hasta;
        LocalDate inicio = desde == null ? fin.withDayOfYear(1) : desde;
        Agrupacion por = agrupacion == null ? Agrupacion.MES : agrupacion;
        if (inicio.isAfter(fin)) {
            throw ErrorNegocio.invalido("RANGO_INVALIDO", "La fecha inicial debe ser anterior o igual a la final.");
        }
        if (ChronoUnit.DAYS.between(inicio, fin) >= MAX_DIAS) {
            throw ErrorNegocio.invalido("RANGO_MUY_LARGO", "Consulta como máximo un año a la vez.");
        }

        String unidad = switch (por) {
            case DIA -> "day";
            case SEMANA -> "week";
            case MES -> "month";
        };
        MapSqlParameterSource p = new MapSqlParameterSource("desde", inicio).addValue("hasta", fin);
        // Sin zona horaria: las fechas ya son de Lima y el truncado no debe depender de la sesion
        List<Periodo> periodos = new ArrayList<>();
        BigDecimal[] acumulado = {BigDecimal.ZERO};
        jdbc.query("""
                WITH periodos AS (
                    SELECT g::date AS inicio
                    FROM generate_series(date_trunc('%1$s', CAST(:desde AS timestamp)), CAST(:hasta AS timestamp), interval '1 %1$s') g
                ), origen AS (%2$s)
                SELECT p.inicio,
                       coalesce(sum(o.monto) FILTER (WHERE o.tarifa = 'MINORISTA'), 0) AS b2c,
                       coalesce(sum(o.monto) FILTER (WHERE o.tarifa = 'MAYORISTA'), 0) AS b2b
                FROM periodos p
                LEFT JOIN origen o ON date_trunc('%1$s', o.fecha::timestamp)::date = p.inicio
                GROUP BY p.inicio
                ORDER BY p.inicio""".formatted(unidad, origen), p, rs -> {
            LocalDate desdePeriodo = rs.getObject("inicio", LocalDate.class);
            BigDecimal b2c = rs.getBigDecimal("b2c");
            BigDecimal b2b = rs.getBigDecimal("b2b");
            BigDecimal total = b2c.add(b2b);
            acumulado[0] = acumulado[0].add(total);
            periodos.add(new Periodo(max(desdePeriodo, inicio), min(finDe(desdePeriodo, por), fin), b2c, b2b, total,
                    acumulado[0]));
        });

        BigDecimal b2c = periodos.stream().map(Periodo::b2c).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal b2b = periodos.stream().map(Periodo::b2b).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Reporte(metrica, inicio, fin, por, new Desglose(b2c, b2b, b2c.add(b2b)), periodos);
    }

    private static LocalDate finDe(LocalDate inicio, Agrupacion por) {
        return switch (por) {
            case DIA -> inicio;
            case SEMANA -> inicio.plusDays(6);
            case MES -> inicio.plusMonths(1).minusDays(1);
        };
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
