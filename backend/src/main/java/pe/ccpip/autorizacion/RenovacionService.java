package pe.ccpip.autorizacion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.productor.Auditoria;
import pe.ccpip.productor.Auditoria.Accion;

/**
 * CCPIP-321 (RF-205): el subadministrador registra una solicitud de renovacion de un certificado de la
 * autorizacion de un producto. Avisa al productor y queda en el expediente para la revision.
 *
 * <p>Efectos sobre la publicacion y la venta (C5): ninguno al registrarla. La autorizacion sigue APROBADA
 * y no se registra decision. Si el certificado vence sin renovarse, la oferta pasa sola a "Venta
 * pausada" por la regla de vigencia de siempre, y vuelve a venderse cuando se apruebe uno nuevo: no hay
 * bloqueo permanente automatico. Revocar sigue siendo una decision aparte y con motivo (CCPIP-39).
 */
@Service
public class RenovacionService {

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /** PENDIENTE hasta que se atienda o se cancele (las dos llegan con sus historias). */
    public enum EstadoRenovacion { PENDIENTE, ATENDIDA, CANCELADA }

    /** El certificado cuya renovacion se pide, con su vigencia de hoy. */
    public record Certificado(UUID evidenciaId, String titulo, String emisor, LocalDate vigenteHasta, boolean vencido) {
    }

    /**
     * Una solicitud. {@code ventaPausadaDesde}: desde que dia la oferta pasa a "Venta pausada" si el
     * certificado no se renueva (el dia siguiente al vencimiento de la autorizacion); nulo si no vence.
     * {@code solicitadaPor} es nulo en la vista del productor.
     */
    public record Renovacion(long id, long autorizacionId, long productoId, String producto, Certificado certificado,
                             String motivo, LocalDate plazo, EstadoRenovacion estado, String solicitadaPor,
                             Instant solicitadaEn, LocalDate ventaPausadaDesde) {
    }

    /** {@code plazo} es opcional: por defecto, el vencimiento del certificado o hoy si ya vencio. */
    public record NuevaRenovacion(
            @NotNull(message = "Elige el certificado que se debe renovar") UUID evidenciaId,
            @NotBlank(message = "Explica por qué se pide la renovación")
            @Size(max = 500, message = "El motivo admite hasta 500 caracteres") String motivo,
            LocalDate plazo) {
    }

    private static final String COLUMNAS = """
            SELECT r.id, r.autorizacion_id, r.producto_id, p.nombre AS producto, r.motivo, r.plazo, r.estado,
                   u.nombre AS solicitada_por, r.solicitada_en,
                   e.id AS evidencia_id, e.titulo, e.emisor, e.vigente_hasta,
                   e.vigente_hasta < %1$s AS vencido,
                   a.vigente_hasta + 1 AS venta_pausada_desde
            FROM renovacion_certificado r
            JOIN producto p ON p.id = r.producto_id
            JOIN evidencia_producto e ON e.id = r.evidencia_id
            JOIN autorizacion_producto a ON a.id = r.autorizacion_id
            JOIN usuario u ON u.id = r.solicitada_por
            """.formatted(HOY);

    private final NamedParameterJdbcTemplate jdbc;
    private final Auditoria auditoria;

    public RenovacionService(NamedParameterJdbcTemplate jdbc, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    /**
     * Registra la solicitud. Exige la autorizacion APROBADA (vigente o vencida) y un certificado suyo sin
     * retirar; uno ya pedido y pendiente no se vuelve a pedir.
     */
    @Transactional
    public Renovacion registrar(long autorizacionId, long usuarioId, NuevaRenovacion nueva) {
        MapSqlParameterSource p = new MapSqlParameterSource("autorizacion", autorizacionId)
                .addValue("evidencia", nueva.evidenciaId()).addValue("usuario", usuarioId)
                .addValue("motivo", nueva.motivo().strip()).addValue("plazo", nueva.plazo());
        String estado = jdbc.query("SELECT estado FROM autorizacion_producto WHERE id = :autorizacion FOR UPDATE", p,
                        (rs, row) -> rs.getString("estado"))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("SOLICITUD_NO_ENCONTRADA", "La solicitud no existe."));
        if (!"APROBADA".equals(estado)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "AUTORIZACION_NO_APROBADA",
                    "Solo se pide renovar un certificado de una autorización aprobada.");
        }
        LocalDate vence = jdbc.query("""
                        SELECT e.vigente_hasta FROM evidencia_producto e
                        WHERE e.id = :evidencia AND e.autorizacion_id = :autorizacion AND e.tipo = 'CERTIFICADO'
                          AND e.retirada_en IS NULL""", p, (rs, row) -> rs.getObject("vigente_hasta", LocalDate.class))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.invalido("CERTIFICADO_INVALIDO",
                        "Elige un certificado vigente de esta solicitud."));
        LocalDate hoy = jdbc.getJdbcTemplate().queryForObject("SELECT " + HOY, LocalDate.class);
        if (nueva.plazo() != null && nueva.plazo().isBefore(hoy)) {
            throw ErrorNegocio.invalido("PLAZO_PASADO", "El plazo no puede ser una fecha pasada.");
        }
        p.addValue("plazo", nueva.plazo() != null ? nueva.plazo() : (vence.isBefore(hoy) ? hoy : vence));
        Integer pendientes = jdbc.queryForObject("""
                SELECT count(*) FROM renovacion_certificado WHERE evidencia_id = :evidencia AND estado = 'PENDIENTE'""",
                p, Integer.class);
        if (pendientes != null && pendientes > 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "RENOVACION_PENDIENTE",
                    "Ya hay una solicitud de renovación pendiente para este certificado.");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO renovacion_certificado (autorizacion_id, productor_id, producto_id, evidencia_id, motivo,
                                                    plazo, solicitada_por)
                SELECT a.id, a.productor_id, a.producto_id, :evidencia, :motivo, :plazo, :usuario
                FROM autorizacion_producto a WHERE a.id = :autorizacion
                RETURNING id""", p, Long.class);
        Renovacion renovacion = una(id);
        auditoria.registrar(renovacion.productoId(), usuarioId, Accion.AUTORIZACION, Map.of(
                "renovacionSolicitada", id, "certificado", nueva.evidenciaId().toString(),
                "plazo", renovacion.plazo().toString()));
        return renovacion;
    }

    /** Las solicitudes de la autorizacion, la mas reciente primero (expediente S-02). */
    @Transactional(readOnly = true)
    public List<Renovacion> deAutorizacion(long autorizacionId) {
        return jdbc.query(COLUMNAS + "WHERE r.autorizacion_id = :autorizacion ORDER BY r.solicitada_en DESC, r.id DESC",
                new MapSqlParameterSource("autorizacion", autorizacionId), (rs, row) -> renovacion(rs, true));
    }

    /** Las del producto propio (PW-14), sin quien las pidio. Un producto ajeno no tiene ninguna (RNF-05). */
    @Transactional(readOnly = true)
    public List<Renovacion> deProducto(long productorId, long productoId) {
        return jdbc.query(COLUMNAS + """
                WHERE r.productor_id = :productor AND r.producto_id = :producto
                ORDER BY r.solicitada_en DESC, r.id DESC""",
                new MapSqlParameterSource("productor", productorId).addValue("producto", productoId),
                (rs, row) -> renovacion(rs, false));
    }

    Renovacion una(long id) {
        return jdbc.queryForObject(COLUMNAS + "WHERE r.id = :id", new MapSqlParameterSource("id", id),
                (rs, row) -> renovacion(rs, true));
    }

    private static Renovacion renovacion(ResultSet rs, boolean conAutor) throws SQLException {
        return new Renovacion(rs.getLong("id"), rs.getLong("autorizacion_id"), rs.getLong("producto_id"),
                rs.getString("producto"),
                new Certificado(rs.getObject("evidencia_id", UUID.class), rs.getString("titulo"), rs.getString("emisor"),
                        rs.getObject("vigente_hasta", LocalDate.class), rs.getBoolean("vencido")),
                rs.getString("motivo"), rs.getObject("plazo", LocalDate.class),
                EstadoRenovacion.valueOf(rs.getString("estado")), conAutor ? rs.getString("solicitada_por") : null,
                rs.getTimestamp("solicitada_en").toInstant(), rs.getObject("venta_pausada_desde", LocalDate.class));
    }
}
