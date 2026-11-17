package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.fidelizacion.FidelizacionModelos.Campana;
import pe.ccpip.fidelizacion.FidelizacionModelos.EstadoCampana;
import pe.ccpip.fidelizacion.FidelizacionModelos.NuevaCampana;
import pe.ccpip.fidelizacion.FidelizacionModelos.Panel;
import pe.ccpip.fidelizacion.FidelizacionModelos.Resumen;
import pe.ccpip.fidelizacion.FidelizacionModelos.Totales;

/**
 * CCPIP-86: el sub administrador programa campanas de puntos por un periodo (fechas de Lima, inclusive). Mientras
 * una rige, sus pedidos ganan con su regla en vez de la base, si llegan a su pedido minimo. Las campanas no se
 * cruzan, asi que cada pedido tiene una sola regla: la de la campana que regia cuando se confirmo.
 *
 * <p>Solo se edita una campana que aun no empieza; una activa se puede finalizar antes (rige hasta ese
 * instante) y una programada, cancelar. Los pedidos ya confirmados conservan la regla que les toco.
 */
@Service
public class CampanasService {

    static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /**
     * El pedido {@code p} cae en el periodo de la campana {@code c}: confirmado dentro de sus fechas y antes de que
     * se finalizara. Es la unica definicion; la usan la acumulacion, el resumen y las metricas.
     */
    static final String EN_CAMPANA = enPeriodo("c.desde", "c.hasta", "c.finalizado_en");

    /**
     * El pedido {@code p} se confirmo entre los dias {@code desde} y {@code hasta} de Lima (expresiones SQL) y antes
     * del instante {@code corte}, si lo hay.
     */
    static String enPeriodo(String desde, String hasta, String corte) {
        return """
                p.confirmado_en IS NOT NULL
                AND (p.confirmado_en AT TIME ZONE 'America/Lima')::date BETWEEN %1$s AND %2$s
                AND (%3$s IS NULL OR p.confirmado_en < %3$s)""".formatted(desde, hasta, corte);
    }

    private static final String COLUMNAS = "SELECT c.*, r.nombre AS registrado_por_nombre, x.nombre AS finalizado_por_nombre";
    private static final String USUARIOS = " JOIN usuario r ON r.id = c.registrado_por LEFT JOIN usuario x ON x.id = c.finalizado_por";
    private static final String CAMPANAS = COLUMNAS + " FROM campana_puntos c" + USUARIOS;

    private final NamedParameterJdbcTemplate jdbc;

    public CampanasService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** La seccion Fidelizacion: regla base, lo acumulado y todas las campanas con su resumen. */
    @Transactional(readOnly = true)
    public Panel panel() {
        LocalDate hoy = hoy();
        Map<Long, Resumen> resumenes = resumenes();
        List<Campana> campanas = jdbc.query(CAMPANAS + " ORDER BY c.desde DESC, c.id DESC", new MapSqlParameterSource(),
                (rs, row) -> campana(rs, hoy, resumenes));
        Totales totales = jdbc.queryForObject("""
                SELECT coalesce(sum(puntos), 0) AS puntos,
                       coalesce(sum(puntos) FILTER (WHERE campana_id IS NOT NULL), 0) AS de_campanas,
                       count(DISTINCT usuario_id) AS clientes, count(*) AS pedidos
                FROM movimiento_puntos""", new MapSqlParameterSource(), (rs, row) -> new Totales(
                rs.getLong("puntos"), rs.getLong("de_campanas"), rs.getInt("clientes"), rs.getInt("pedidos")));
        return new Panel(hoy, ReglaPuntos.BASE, totales, campanas);
    }

    /** Una campana con su resumen, o 404. */
    @Transactional(readOnly = true)
    public Campana campana(long id) {
        LocalDate hoy = hoy();
        Map<Long, Resumen> resumenes = resumenes();
        return jdbc.query(CAMPANAS + " WHERE c.id = :id", new MapSqlParameterSource("id", id),
                        (rs, row) -> campana(rs, hoy, resumenes))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("CAMPANA_NO_ENCONTRADA", "No encontramos esa campaña."));
    }

    @Transactional
    public Campana crear(long usuarioId, NuevaCampana nueva) {
        bloquear();
        validar(nueva, null);
        Long id = jdbc.queryForObject("""
                INSERT INTO campana_puntos (nombre, descripcion, desde, hasta, puntos, cada_soles, monto_minimo,
                                            tope_puntos, registrado_por)
                VALUES (:nombre, :descripcion, :desde, :hasta, :puntos, :cadaSoles, :montoMinimo, :tope, :usuario)
                RETURNING id""", parametros(nueva).addValue("usuario", usuarioId), Long.class);
        return campana(id);
    }

    /** Cambia una campana que aun no empieza. */
    @Transactional
    public Campana editar(long usuarioId, long id, NuevaCampana cambios) {
        bloquear();
        Campana actual = campana(id);
        if (actual.estado() != EstadoCampana.PROGRAMADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CAMPANA_NO_EDITABLE",
                    "Solo se puede editar una campaña que aún no empieza.");
        }
        validar(cambios, id);
        jdbc.update("""
                UPDATE campana_puntos SET nombre = :nombre, descripcion = :descripcion, desde = :desde, hasta = :hasta,
                       puntos = :puntos, cada_soles = :cadaSoles, monto_minimo = :montoMinimo, tope_puntos = :tope,
                       actualizado_por = :usuario, actualizado_en = clock_timestamp()
                WHERE id = :id""", parametros(cambios).addValue("usuario", usuarioId).addValue("id", id));
        return campana(id);
    }

    /**
     * Finaliza una campana activa desde este instante, o cancela una programada. Los pedidos confirmados mientras
     * rigio conservan su regla.
     */
    @Transactional
    public Campana finalizar(long usuarioId, long id) {
        Campana actual = campana(id);
        if (actual.estado() != EstadoCampana.ACTIVA && actual.estado() != EstadoCampana.PROGRAMADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CAMPANA_YA_TERMINO", "Esta campaña ya terminó.");
        }
        jdbc.update("""
                UPDATE campana_puntos SET finalizado_por = :usuario, finalizado_en = clock_timestamp()
                WHERE id = :id AND finalizado_en IS NULL""", new MapSqlParameterSource("usuario", usuarioId).addValue("id", id));
        return campana(id);
    }

    /** La campana que regia en {@code instante} (cuando se confirmo un pedido), si alguna. */
    Optional<Campana> vigenteAl(Instant instante) {
        LocalDate hoy = hoy();
        return jdbc.query(COLUMNAS + " FROM (SELECT CAST(:instante AS timestamptz) AS confirmado_en) p CROSS JOIN campana_puntos c"
                                + USUARIOS + " WHERE " + EN_CAMPANA + " ORDER BY c.id DESC LIMIT 1",
                        new MapSqlParameterSource("instante", Timestamp.from(instante)),
                        (rs, row) -> campana(rs, hoy, Map.of()))
                .stream().findFirst();
    }

    LocalDate hoy() {
        return jdbc.queryForObject("SELECT " + HOY, new MapSqlParameterSource(), LocalDate.class);
    }

    /** Dos sub administradores a la vez no deben dejar campanas que se crucen. */
    private void bloquear() {
        jdbc.getJdbcTemplate().execute("LOCK TABLE campana_puntos IN SHARE ROW EXCLUSIVE MODE");
    }

    /** Reglas de negocio de una campana nueva o editada ({@code id}: la que se edita, para no cruzarla consigo). */
    private void validar(NuevaCampana c, Long id) {
        LocalDate hoy = hoy();
        if (c.desde().isBefore(hoy)) {
            throw ErrorNegocio.invalido("CAMPANA_INICIO_PASADO", "La campaña empieza hoy o en una fecha futura.");
        }
        if (c.hasta().isBefore(c.desde())) {
            throw ErrorNegocio.invalido("CAMPANA_FIN_ANTES_DE_INICIO", "La campaña termina el mismo día que empieza o después.");
        }
        if (c.hasta().isAfter(c.desde().plusDays(365))) {
            throw ErrorNegocio.invalido("CAMPANA_MUY_LARGA", "Una campaña dura como máximo un año.");
        }
        ReglaPuntos regla = new ReglaPuntos(c.puntos(), c.cadaSoles(), c.topePuntos());
        if (regla.porSol().compareTo(ReglaPuntos.BASE.porSol()) <= 0) {
            throw ErrorNegocio.invalido("CAMPANA_NO_MEJORA_BASE",
                    "La campaña debe dar más puntos que la regla base (1 punto por cada S/ 10).");
        }
        // Las finalizadas cuentan solo hasta el dia en que se finalizaron; las canceladas, nada
        jdbc.query("""
                        SELECT nombre, desde, hasta, finalizado_en FROM campana_puntos
                        WHERE (CAST(:id AS bigint) IS NULL OR id <> :id)
                          AND (finalizado_en IS NULL OR (finalizado_en AT TIME ZONE 'America/Lima')::date >= desde)
                          AND desde <= :hasta
                          AND least(hasta, coalesce((finalizado_en AT TIME ZONE 'America/Lima')::date, hasta)) >= :desde
                        ORDER BY desde LIMIT 1""",
                new MapSqlParameterSource("id", id).addValue("desde", c.desde()).addValue("hasta", c.hasta()),
                (rs, row) -> rs.getString("nombre"))
                .stream().findFirst().ifPresent(otra -> {
                    throw new ErrorNegocio(HttpStatus.CONFLICT, "CAMPANA_SE_CRUZA",
                            "Se cruza con la campaña «" + otra + "». Elige otras fechas.");
                });
    }

    private static MapSqlParameterSource parametros(NuevaCampana c) {
        String descripcion = c.descripcion() == null || c.descripcion().isBlank() ? null : c.descripcion().strip();
        return new MapSqlParameterSource("nombre", c.nombre().strip()).addValue("descripcion", descripcion)
                .addValue("desde", c.desde()).addValue("hasta", c.hasta()).addValue("puntos", c.puntos())
                .addValue("cadaSoles", c.cadaSoles())
                .addValue("montoMinimo", c.montoMinimo() == null ? BigDecimal.ZERO : c.montoMinimo())
                .addValue("tope", c.topePuntos());
    }

    /** Pedidos, ventas, clientes y puntos otorgados de cada campana. */
    private Map<Long, Resumen> resumenes() {
        record Fila(long id, Resumen resumen) {
        }
        return jdbc.query("""
                        SELECT c.id, count(p.id) AS pedidos, coalesce(sum(p.total), 0) AS ventas,
                               count(DISTINCT p.usuario_id) AS clientes,
                               (SELECT coalesce(sum(m.puntos), 0) FROM movimiento_puntos m WHERE m.campana_id = c.id) AS puntos
                        FROM campana_puntos c
                        LEFT JOIN pedido p ON p.estado <> 'CANCELADO' AND p.total >= c.monto_minimo AND %s
                        GROUP BY c.id""".formatted(EN_CAMPANA), new MapSqlParameterSource(),
                        (rs, row) -> new Fila(rs.getLong("id"), new Resumen(rs.getInt("pedidos"),
                                rs.getBigDecimal("ventas"), rs.getInt("clientes"), rs.getLong("puntos"))))
                .stream().collect(Collectors.toMap(Fila::id, Fila::resumen));
    }

    private static Campana campana(ResultSet rs, LocalDate hoy, Map<Long, Resumen> resumenes) throws SQLException {
        LocalDate desde = rs.getObject("desde", LocalDate.class);
        LocalDate hasta = rs.getObject("hasta", LocalDate.class);
        Instant finalizado = instante(rs, "finalizado_en");
        LocalDate finalizadoDia = finalizado == null ? null : finalizado.atZone(LIMA).toLocalDate();
        EstadoCampana estado;
        LocalDate finEfectivo = hasta;
        if (finalizadoDia != null && finalizadoDia.isBefore(desde)) {
            estado = EstadoCampana.CANCELADA;
            finEfectivo = null;
        } else if (finalizadoDia != null) {
            estado = EstadoCampana.FINALIZADA;
            finEfectivo = finalizadoDia.isBefore(hasta) ? finalizadoDia : hasta;
        } else if (hoy.isBefore(desde)) {
            estado = EstadoCampana.PROGRAMADA;
        } else {
            estado = hoy.isAfter(hasta) ? EstadoCampana.FINALIZADA : EstadoCampana.ACTIVA;
        }
        long id = rs.getLong("id");
        Integer tope = rs.getObject("tope_puntos", Integer.class);
        return new Campana(id, rs.getString("nombre"), rs.getString("descripcion"), desde, hasta, finEfectivo,
                rs.getInt("puntos"), rs.getBigDecimal("cada_soles"), rs.getBigDecimal("monto_minimo"), tope, estado,
                rs.getString("registrado_por_nombre"), instante(rs, "registrado_en"),
                rs.getString("finalizado_por_nombre"), finalizado,
                resumenes.getOrDefault(id, new Resumen(0, BigDecimal.ZERO, 0, 0)));
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
