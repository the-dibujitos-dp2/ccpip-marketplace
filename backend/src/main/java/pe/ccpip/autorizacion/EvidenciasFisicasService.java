package pe.ccpip.autorizacion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * CCPIP-35 (RF-12) y CCPIP-329 (RF-219): en el expediente S-02, el subadministrador registra el envio de
 * una evidencia fisica (una muestra, un documento original) cuando la evaluacion de la solicitud la
 * requiera, y despues su recepcion con el resultado y las observaciones.
 *
 * <p>Es opcional: ninguna regla de aprobacion la exige (no se impone muestra obligatoria) y el resultado
 * no decide la solicitud, que sigue siendo una decision aparte y con motivo. Cada registro se conserva tal
 * cual (la base lo impide): el envio no se reescribe y la recepcion se registra una sola vez.
 */
@Service
public class EvidenciasFisicasService {

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    public enum EstadoFisica { ENVIADA, RECIBIDA }

    /** Lo que encontro la Camara al recibirla. No aprueba ni rechaza la solicitud. */
    public enum Resultado { CONFORME, OBSERVADA, NO_CONFORME }

    /** La recepcion, si ya se registro. */
    public record Recepcion(LocalDate recibidaEl, Resultado resultado, String observaciones, String registradaPor,
                            Instant registradaEn) {
    }

    public record EvidenciaFisica(long id, long autorizacionId, long productoId, String descripcion, String referencia,
                                  LocalDate enviadaEl, String observaciones, String registradaPor, Instant registradaEn,
                                  EstadoFisica estado, Recepcion recepcion) {
    }

    public record NuevoEnvio(
            @NotBlank(message = "Describe la evidencia física que se envía")
            @Size(max = 300, message = "La descripción admite hasta 300 caracteres") String descripcion,
            @Size(max = 120, message = "La referencia admite hasta 120 caracteres") String referencia,
            @NotNull(message = "Indica la fecha del envío") LocalDate enviadaEl,
            @Size(max = 500, message = "Las observaciones admiten hasta 500 caracteres") String observaciones) {
    }

    /** Las observaciones son obligatorias si el resultado no es CONFORME: explican que se encontro. */
    public record NuevaRecepcion(
            @NotNull(message = "Indica la fecha de recepción") LocalDate recibidaEl,
            @NotNull(message = "Indica el resultado de la revisión") Resultado resultado,
            @Size(max = 500, message = "Las observaciones admiten hasta 500 caracteres") String observaciones) {
    }

    private static final String COLUMNAS = """
            SELECT f.id, f.autorizacion_id, f.producto_id, f.descripcion, f.referencia, f.enviada_el,
                   f.observaciones_envio, ue.nombre AS envio_por, f.envio_registrado_en, f.estado,
                   f.recibida_el, f.resultado, f.observaciones_recepcion, ur.nombre AS recepcion_por,
                   f.recepcion_registrada_en
            FROM evidencia_fisica f
            JOIN usuario ue ON ue.id = f.envio_registrado_por
            LEFT JOIN usuario ur ON ur.id = f.recepcion_registrada_por
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Auditoria auditoria;

    public EvidenciasFisicasService(NamedParameterJdbcTemplate jdbc, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    /** CCPIP-35: solo mientras la solicitud esta en evaluacion; la fecha del envio no puede ser futura. */
    @Transactional
    public EvidenciaFisica registrarEnvio(long autorizacionId, long usuarioId, NuevoEnvio envio) {
        MapSqlParameterSource p = new MapSqlParameterSource("autorizacion", autorizacionId)
                .addValue("usuario", usuarioId).addValue("descripcion", envio.descripcion().strip())
                .addValue("referencia", limpio(envio.referencia())).addValue("enviada", envio.enviadaEl())
                .addValue("observaciones", limpio(envio.observaciones()));
        String estado = jdbc.query("SELECT estado FROM autorizacion_producto WHERE id = :autorizacion FOR UPDATE", p,
                        (rs, row) -> rs.getString("estado"))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("SOLICITUD_NO_ENCONTRADA", "La solicitud no existe."));
        if (!"SOLICITADA".equals(estado)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "SOLICITUD_YA_RESUELTA",
                    "Esta solicitud ya no está pendiente de revisión.");
        }
        if (envio.enviadaEl().isAfter(hoy())) {
            throw ErrorNegocio.invalido("FECHA_FUTURA", "La fecha no puede ser posterior a hoy.");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO evidencia_fisica (autorizacion_id, productor_id, producto_id, descripcion, referencia,
                                              enviada_el, observaciones_envio, envio_registrado_por)
                SELECT a.id, a.productor_id, a.producto_id, :descripcion, :referencia, :enviada, :observaciones, :usuario
                FROM autorizacion_producto a WHERE a.id = :autorizacion
                RETURNING id""", p, Long.class);
        EvidenciaFisica fisica = una(id);
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put("evidenciaFisicaEnviada", id);
        cambios.put("descripcion", fisica.descripcion());
        cambios.put("enviadaEl", fisica.enviadaEl().toString());
        auditoria.registrar(fisica.productoId(), usuarioId, Accion.EVIDENCIA, cambios);
        return fisica;
    }

    /**
     * CCPIP-329: una sola vez por envio, con fecha entre el envio y hoy. Se puede registrar aunque la
     * solicitud ya se haya decidido: la muestra llega cuando llega y su resultado queda en el expediente.
     */
    @Transactional
    public EvidenciaFisica registrarRecepcion(long autorizacionId, long fisicaId, long usuarioId, NuevaRecepcion recepcion) {
        MapSqlParameterSource p = new MapSqlParameterSource("autorizacion", autorizacionId).addValue("id", fisicaId)
                .addValue("usuario", usuarioId).addValue("recibida", recepcion.recibidaEl())
                .addValue("resultado", recepcion.resultado().name())
                .addValue("observaciones", limpio(recepcion.observaciones()));
        record Envio(String estado, LocalDate enviadaEl) { }
        Envio envio = jdbc.query("""
                        SELECT estado, enviada_el FROM evidencia_fisica
                        WHERE id = :id AND autorizacion_id = :autorizacion FOR UPDATE""", p,
                        (rs, row) -> new Envio(rs.getString("estado"), rs.getObject("enviada_el", LocalDate.class)))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("EVIDENCIA_FISICA_NO_ENCONTRADA",
                        "La evidencia física no existe en esta solicitud."));
        if (!"ENVIADA".equals(envio.estado())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "EVIDENCIA_FISICA_YA_RECIBIDA",
                    "La recepción de esta evidencia física ya se registró.");
        }
        if (recepcion.recibidaEl().isAfter(hoy())) {
            throw ErrorNegocio.invalido("FECHA_FUTURA", "La fecha no puede ser posterior a hoy.");
        }
        if (recepcion.recibidaEl().isBefore(envio.enviadaEl())) {
            throw ErrorNegocio.invalido("RECEPCION_ANTES_DEL_ENVIO", "La recepción no puede ser anterior al envío.");
        }
        if (recepcion.resultado() != Resultado.CONFORME && limpio(recepcion.observaciones()) == null) {
            throw ErrorNegocio.invalido("OBSERVACIONES_REQUERIDAS",
                    "Explica en las observaciones qué se encontró en la evidencia.");
        }
        jdbc.update("""
                UPDATE evidencia_fisica SET estado = 'RECIBIDA', recibida_el = :recibida, resultado = :resultado,
                       observaciones_recepcion = :observaciones, recepcion_registrada_por = :usuario,
                       recepcion_registrada_en = now()
                WHERE id = :id""", p);
        EvidenciaFisica fisica = una(fisicaId);
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put("evidenciaFisicaRecibida", fisicaId);
        cambios.put("resultado", recepcion.resultado().name());
        cambios.put("recibidaEl", recepcion.recibidaEl().toString());
        auditoria.registrar(fisica.productoId(), usuarioId, Accion.EVIDENCIA, cambios);
        return fisica;
    }

    /** Las de la solicitud, la mas reciente primero. Una solicitud que no existe responde 404. */
    @Transactional(readOnly = true)
    public List<EvidenciaFisica> deAutorizacion(long autorizacionId) {
        MapSqlParameterSource p = new MapSqlParameterSource("autorizacion", autorizacionId);
        Integer existe = jdbc.queryForObject("SELECT count(*) FROM autorizacion_producto WHERE id = :autorizacion", p,
                Integer.class);
        if (existe == null || existe == 0) {
            throw ErrorNegocio.noEncontrado("SOLICITUD_NO_ENCONTRADA", "La solicitud no existe.");
        }
        return jdbc.query(COLUMNAS + "WHERE f.autorizacion_id = :autorizacion ORDER BY f.envio_registrado_en DESC, f.id DESC",
                p, (rs, row) -> fisica(rs));
    }

    private EvidenciaFisica una(long id) {
        return jdbc.queryForObject(COLUMNAS + "WHERE f.id = :id", new MapSqlParameterSource("id", id),
                (rs, row) -> fisica(rs));
    }

    private LocalDate hoy() {
        return jdbc.getJdbcTemplate().queryForObject("SELECT " + HOY, LocalDate.class);
    }

    private static String limpio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    private static EvidenciaFisica fisica(ResultSet rs) throws SQLException {
        EstadoFisica estado = EstadoFisica.valueOf(rs.getString("estado"));
        Recepcion recepcion = estado == EstadoFisica.RECIBIDA
                ? new Recepcion(rs.getObject("recibida_el", LocalDate.class), Resultado.valueOf(rs.getString("resultado")),
                        rs.getString("observaciones_recepcion"), rs.getString("recepcion_por"),
                        instante(rs.getTimestamp("recepcion_registrada_en")))
                : null;
        return new EvidenciaFisica(rs.getLong("id"), rs.getLong("autorizacion_id"), rs.getLong("producto_id"),
                rs.getString("descripcion"), rs.getString("referencia"), rs.getObject("enviada_el", LocalDate.class),
                rs.getString("observaciones_envio"), rs.getString("envio_por"),
                instante(rs.getTimestamp("envio_registrado_en")), estado, recepcion);
    }

    private static Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
