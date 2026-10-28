package pe.ccpip.autorizacion;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.productor.Auditoria;
import pe.ccpip.productor.Auditoria.Accion;

/**
 * CCPIP-34 (RF-11): el productor declara, por cada producto propio, cuales reglas de origen y sostenibilidad
 * definidas por la Camara cumple. No hay reglas universales (C11): cada producto declara las suyas, con fecha,
 * y puede no declarar ninguna.
 *
 * <p>Cada declaracion es completa y no se edita: una nueva reemplaza a la anterior, que queda en el
 * historial. Declarar no es acreditar: no da sellos al visitante (CCPIP-38) ni cambia la autorizacion.
 */
@Service
public class CumplimientoService {

    /** Una regla del catalogo de la Camara, en los dos idiomas (como los demas catalogos controlados). */
    public record Regla(String codigo, String nombre, String nombreEn, String descripcion, String descripcionEn) {
    }

    /** Una declaracion: las reglas que el producto cumplia a esa fecha. */
    public record Declaracion(long id, List<String> reglas, String observaciones, String declaradaPor,
                              Instant declaradaEn) {
    }

    /** {@code vigente} es la mas reciente (nula si nunca declaro); {@code historial} la incluye primero. */
    public record Cumplimiento(long productoId, String producto, List<Regla> reglas, Declaracion vigente,
                               List<Declaracion> historial) {
    }

    public record NuevaDeclaracion(
            @NotNull(message = "Indica las reglas que cumple el producto (puede ser ninguna)")
            @Size(max = 20, message = "Demasiadas reglas") List<String> reglas,
            @Size(max = 500, message = "Las observaciones admiten hasta 500 caracteres") String observaciones) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Auditoria auditoria;

    public CumplimientoService(NamedParameterJdbcTemplate jdbc, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public Cumplimiento consultar(long productorId, long productoId) {
        String nombre = productoPropio(productorId, productoId);
        List<Regla> reglas = jdbc.query("""
                SELECT codigo, nombre, nombre_en, descripcion, descripcion_en FROM regla_cumplimiento
                WHERE activa ORDER BY orden, id""", new MapSqlParameterSource(),
                (rs, row) -> new Regla(rs.getString("codigo"), rs.getString("nombre"), rs.getString("nombre_en"),
                        rs.getString("descripcion"), rs.getString("descripcion_en")));
        List<Declaracion> historial = jdbc.query("""
                SELECT d.id, d.observaciones, u.nombre AS declarada_por, d.declarada_en,
                       array(SELECT r.codigo FROM declaracion_regla dr JOIN regla_cumplimiento r ON r.id = dr.regla_id
                             WHERE dr.declaracion_id = d.id ORDER BY r.orden, r.id) AS reglas
                FROM declaracion_cumplimiento d JOIN usuario u ON u.id = d.declarada_por
                WHERE d.producto_id = :producto AND d.productor_id = :productor
                ORDER BY d.declarada_en DESC, d.id DESC""",
                new MapSqlParameterSource("producto", productoId).addValue("productor", productorId),
                (rs, row) -> declaracion(rs));
        return new Cumplimiento(productoId, nombre, reglas, historial.isEmpty() ? null : historial.get(0), historial);
    }

    /** Solo reglas activas del catalogo, sin repetir. Una lista vacia declara que hoy no cumple ninguna. */
    @Transactional
    public Cumplimiento declarar(long productorId, long usuarioId, long productoId, NuevaDeclaracion nueva) {
        productoPropio(productorId, productoId);
        Set<String> codigos = new LinkedHashSet<>();
        for (String codigo : nueva.reglas()) {
            if (codigo == null || !codigos.add(codigo.strip())) {
                throw ErrorNegocio.invalido("REGLA_INVALIDA", "Cada regla se declara una sola vez.");
            }
        }
        List<Integer> ids = new ArrayList<>();
        if (!codigos.isEmpty()) {
            ids = jdbc.queryForList("SELECT id FROM regla_cumplimiento WHERE activa AND codigo IN (:codigos)",
                    new MapSqlParameterSource("codigos", codigos), Integer.class);
            if (ids.size() != codigos.size()) {
                throw ErrorNegocio.invalido("REGLA_INVALIDA", "Solo se declaran reglas vigentes definidas por la Cámara.");
            }
        }
        String observaciones = nueva.observaciones() == null || nueva.observaciones().isBlank()
                ? null : nueva.observaciones().strip();
        Long id = jdbc.queryForObject("""
                INSERT INTO declaracion_cumplimiento (producto_id, productor_id, observaciones, declarada_por)
                VALUES (:producto, :productor, :observaciones, :usuario) RETURNING id""",
                new MapSqlParameterSource("producto", productoId).addValue("productor", productorId)
                        .addValue("observaciones", observaciones).addValue("usuario", usuarioId), Long.class);
        for (Integer regla : ids) {
            jdbc.update("INSERT INTO declaracion_regla (declaracion_id, regla_id) VALUES (:d, :r)",
                    new MapSqlParameterSource("d", id).addValue("r", regla));
        }
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put("cumplimientoDeclarado", id);
        cambios.put("reglas", List.copyOf(codigos));
        auditoria.registrar(productoId, usuarioId, Accion.CUMPLIMIENTO, cambios);
        return consultar(productorId, productoId);
    }

    private String productoPropio(long productorId, long productoId) {
        return jdbc.query("SELECT nombre FROM producto WHERE id = :id AND productor_id = :productor",
                        new MapSqlParameterSource("id", productoId).addValue("productor", productorId),
                        (rs, row) -> rs.getString(1))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe."));
    }

    private static Declaracion declaracion(ResultSet rs) throws SQLException {
        Array reglas = rs.getArray("reglas");
        return new Declaracion(rs.getLong("id"), List.of((String[]) reglas.getArray()), rs.getString("observaciones"),
                rs.getString("declarada_por"), rs.getTimestamp("declarada_en").toInstant());
    }
}
