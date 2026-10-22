package pe.ccpip.reportes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CCPIP-354 (RF-244): el superadministrador consulta las existencias agregadas de los puntos de acopio.
 * Es solo lectura: no hay ninguna operacion que edite stock desde la administracion. Las cifras son
 * agregadas por punto y por linea; no se nombra a ningun productor ni producto (SA-08), que es el
 * detalle que ve el administrador de cada punto (A-02, CCPIP-162).
 *
 * <p>El saldo es {@code lote.cantidad_actual}, una vez por lote, igual que en A-02. Las cantidades no se
 * suman entre unidades distintas: cada total va por unidad de medida.
 */
@Service
public class ExistenciasAgregadasService {

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    private static final String VENCIDO = "(l.fecha_vencimiento IS NOT NULL AND l.fecha_vencimiento < " + HOY + ")";

    /** Lotes con saldo en puntos de acopio activos. */
    private static final String LOTES = """
            FROM lote l
            JOIN ubicacion u ON u.id = l.ubicacion_id AND u.tipo = 'PUNTO_ACOPIO' AND u.activo
            JOIN producto p ON p.id = l.producto_id
            JOIN unidad_medida um ON um.id = p.unidad_medida_id
            WHERE l.cantidad_actual > 0
            """;

    /** Una cantidad en su unidad. {@code vigente} es lo vendible; {@code vencido} ya no se vende. */
    public record Cantidad(String unidad, String nombreUnidad, BigDecimal vigente, BigDecimal vencido) {
    }

    /** Un punto de acopio: cuantos productos y productores tienen saldo ahi, y cuanto, por unidad. */
    public record Punto(long id, String nombre, String departamento, String provincia, int productos, int productores,
                        int lotes, int lotesVencidos, List<Cantidad> cantidades) {
    }

    /** Una linea de producto en todos los puntos. {@code codigo} y {@code nombre} son nulos si no tiene linea. */
    public record Linea(String codigo, String nombre, int productos, int puntos, List<Cantidad> cantidades) {
    }

    /** Totales de la red de puntos de acopio. */
    public record Totales(int puntos, int puntosConExistencias, int productos, int lotes, int lotesVencidos,
                          List<Cantidad> cantidades) {
    }

    public record Existencias(LocalDate fecha, Totales totales, List<Punto> puntos, List<Linea> lineas) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ExistenciasAgregadasService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Existencias consultar() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        LocalDate hoy = jdbc.getJdbcTemplate().queryForObject("SELECT " + HOY, LocalDate.class);

        Map<Long, List<Cantidad>> porPunto = cantidades("l.ubicacion_id", p);
        List<Punto> puntos = jdbc.query("""
                SELECT u.id, u.nombre, u.departamento, u.provincia,
                       count(DISTINCT l.producto_id) AS productos, count(DISTINCT p.productor_id) AS productores,
                       count(l.id) AS lotes, count(l.id) FILTER (WHERE %s) AS vencidos
                FROM ubicacion u
                LEFT JOIN lote l ON l.ubicacion_id = u.id AND l.cantidad_actual > 0
                LEFT JOIN producto p ON p.id = l.producto_id
                WHERE u.tipo = 'PUNTO_ACOPIO' AND u.activo
                GROUP BY u.id, u.nombre, u.departamento, u.provincia
                ORDER BY u.nombre""".formatted(VENCIDO), p, (rs, row) -> new Punto(rs.getLong("id"),
                rs.getString("nombre"), rs.getString("departamento"), rs.getString("provincia"), rs.getInt("productos"),
                rs.getInt("productores"), rs.getInt("lotes"), rs.getInt("vencidos"),
                porPunto.getOrDefault(rs.getLong("id"), List.of())));

        // Un borrador puede no tener linea: va en un grupo propio, con id 0
        Map<Long, List<Cantidad>> porLinea = cantidades("coalesce(p.linea_id, 0)", p);
        List<Linea> lineas = jdbc.query("""
                SELECT coalesce(lp.id, 0) AS id, lp.codigo, lp.nombre, count(DISTINCT l.producto_id) AS productos,
                       count(DISTINCT l.ubicacion_id) AS puntos
                FROM lote l
                JOIN ubicacion u ON u.id = l.ubicacion_id AND u.tipo = 'PUNTO_ACOPIO' AND u.activo
                JOIN producto p ON p.id = l.producto_id
                LEFT JOIN linea_producto lp ON lp.id = p.linea_id
                WHERE l.cantidad_actual > 0
                GROUP BY lp.id, lp.codigo, lp.nombre
                ORDER BY lp.nombre NULLS LAST""", p, (rs, row) -> new Linea(rs.getString("codigo"), rs.getString("nombre"),
                rs.getInt("productos"), rs.getInt("puntos"), porLinea.getOrDefault(rs.getLong("id"), List.of())));

        List<Cantidad> total = cantidades("CAST(0 AS bigint)", p).getOrDefault(0L, List.of());
        Totales totales = jdbc.queryForObject("""
                SELECT count(DISTINCT l.producto_id) AS productos, count(l.id) AS lotes,
                       count(l.id) FILTER (WHERE %s) AS vencidos
                """.formatted(VENCIDO) + LOTES, p, (rs, row) -> new Totales(puntos.size(),
                (int) puntos.stream().filter(pu -> pu.lotes() > 0).count(), rs.getInt("productos"), rs.getInt("lotes"),
                rs.getInt("vencidos"), total));
        return new Existencias(hoy, totales, puntos, lineas);
    }

    /** Saldo vigente y vencido por unidad de medida, agrupado por {@code grupo} (una expresion SQL de id). */
    private Map<Long, List<Cantidad>> cantidades(String grupo, MapSqlParameterSource p) {
        Map<Long, List<Cantidad>> resultado = new LinkedHashMap<>();
        jdbc.query("""
                SELECT %1$s AS grupo, um.codigo, um.nombre,
                       coalesce(sum(l.cantidad_actual) FILTER (WHERE NOT %2$s), 0) AS vigente,
                       coalesce(sum(l.cantidad_actual) FILTER (WHERE %2$s), 0) AS vencido
                """.formatted(grupo, VENCIDO) + LOTES + """
                GROUP BY %1$s, um.codigo, um.nombre
                ORDER BY um.nombre""".formatted(grupo), p, rs -> {
            resultado.computeIfAbsent(rs.getLong("grupo"), k -> new ArrayList<>())
                    .add(new Cantidad(rs.getString("codigo"), rs.getString("nombre"), rs.getBigDecimal("vigente"),
                            rs.getBigDecimal("vencido")));
        });
        return resultado;
    }
}
