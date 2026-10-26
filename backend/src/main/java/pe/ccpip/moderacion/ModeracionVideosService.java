package pe.ccpip.moderacion;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.autorizacion.AutorizacionModelos.Productor;
import pe.ccpip.autorizacion.RevisionService;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.moderacion.ModeracionModelos.BandejaVideos;
import pe.ccpip.moderacion.ModeracionModelos.Contadores;
import pe.ccpip.moderacion.ModeracionModelos.Estado;
import pe.ccpip.moderacion.ModeracionModelos.ExpedienteVideo;
import pe.ccpip.moderacion.ModeracionModelos.FilaVideo;
import pe.ccpip.moderacion.ModeracionModelos.VideoResumen;
import pe.ccpip.video.VideosService;

/**
 * CCPIP-118: el subadministrador revisa los videos que los productores suben a su tienda, como las ofertas
 * (CCPIP-46): los aprueba, y se ven en la tienda publica, o los rechaza; siempre con motivo, que el
 * productor ve. Un video retirado por su productor mientras esperaba deja de estar pendiente.
 */
@Service
public class ModeracionVideosService {

    /** El estado de la revision a partir del del video. */
    private static final String ESTADO = """
            CASE v.estado WHEN 'EN_REVISION' THEN 'PENDIENTE' WHEN 'PUBLICADO' THEN 'APROBADA' ELSE 'RECHAZADA' END""";

    private final JdbcTemplate jdbc;

    public ModeracionVideosService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Pendientes primero los mas antiguos; los decididos, del mas reciente al mas antiguo. */
    @Transactional(readOnly = true)
    public BandejaVideos bandeja(Estado estado) {
        String filtro = switch (estado) {
            case PENDIENTE -> "v.estado = 'EN_REVISION' AND v.retirado_en IS NULL ORDER BY v.subido_en, v.id";
            case APROBADA -> "v.estado = 'PUBLICADO' ORDER BY v.decidido_en DESC, v.id DESC";
            case RECHAZADA -> "v.estado = 'RECHAZADO' ORDER BY v.decidido_en DESC, v.id DESC";
        };
        List<FilaVideo> filas = jdbc.query("""
                        SELECT v.id, %s AS estado, v.titulo, v.duracion_segundos, pr.id AS productor_id,
                               coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion, pr.pueblo, pr.region,
                               v.reemplaza_a IS NOT NULL AS reemplazo, v.subido_en, v.decidido_en
                        FROM video_tienda v JOIN productor pr ON pr.id = v.productor_id
                        WHERE\s""".formatted(ESTADO) + filtro,
                (rs, row) -> new FilaVideo(rs.getLong("id"), Estado.valueOf(rs.getString("estado")), rs.getString("titulo"),
                        rs.getBigDecimal("duracion_segundos"), rs.getLong("productor_id"), rs.getString("organizacion"),
                        rs.getString("pueblo"), rs.getString("region"), rs.getBoolean("reemplazo"),
                        VideosService.instante(rs, "subido_en"), VideosService.instante(rs, "decidido_en")));
        return new BandejaVideos(contadores(), filas);
    }

    @Transactional(readOnly = true)
    public ExpedienteVideo expediente(long id) {
        return expediente(id, false);
    }

    /** Aprobar: el video se ve en la tienda publica. */
    @Transactional
    public ExpedienteVideo aprobar(long id, long usuarioId, String motivo) {
        return decidir(id, usuarioId, motivo, "PUBLICADO");
    }

    /** Rechazar: el video no se ve; el productor ve el motivo y puede subir otro. */
    @Transactional
    public ExpedienteVideo rechazar(long id, long usuarioId, String motivo) {
        return decidir(id, usuarioId, motivo, "RECHAZADO");
    }

    private ExpedienteVideo decidir(long id, long usuarioId, String motivo, String decision) {
        String texto = RevisionService.motivoRequerido(motivo);
        ExpedienteVideo v = expediente(id, true);
        if (!v.puedeDecidir()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "VIDEO_YA_RESUELTO", "Este video ya no está pendiente de revisión.");
        }
        jdbc.update("""
                UPDATE video_tienda SET estado = ?, motivo = ?, decidido_por = ?, decidido_en = now() WHERE id = ?""",
                decision, texto, usuarioId, id);
        return expediente(id, false);
    }

    private ExpedienteVideo expediente(long id, boolean bloquear) {
        return jdbc.query("""
                        SELECT v.*, %s AS estado_revision, s.nombre AS subido_por_nombre, d.nombre AS decidido_por_nombre,
                               pr.id AS productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion,
                               pr.comunidad, pr.pueblo, pr.region,
                               r.titulo AS reemplaza_titulo, r.duracion_segundos AS reemplaza_duracion
                        FROM video_tienda v
                        JOIN productor pr ON pr.id = v.productor_id
                        JOIN usuario s ON s.id = v.subido_por
                        LEFT JOIN usuario d ON d.id = v.decidido_por
                        LEFT JOIN video_tienda r ON r.id = v.reemplaza_a
                        WHERE v.id = ?""".formatted(ESTADO) + (bloquear ? " FOR UPDATE OF v" : ""),
                        (rs, row) -> {
                            Long reemplaza = (Long) rs.getObject("reemplaza_a");
                            Estado estado = Estado.valueOf(rs.getString("estado_revision"));
                            boolean retirado = rs.getTimestamp("retirado_en") != null;
                            return new ExpedienteVideo(id, estado, rs.getString("titulo"), rs.getString("formato"),
                                    rs.getLong("bytes"), rs.getBigDecimal("duracion_segundos"), VideosService.url(id),
                                    VideosService.instante(rs, "subido_en"), rs.getString("subido_por_nombre"),
                                    new Productor(rs.getLong("productor_id"), rs.getString("organizacion"),
                                            rs.getString("comunidad"), rs.getString("pueblo"), rs.getString("region")),
                                    reemplaza == null ? null : new VideoResumen(reemplaza, rs.getString("reemplaza_titulo"),
                                            rs.getBigDecimal("reemplaza_duracion"), VideosService.url(reemplaza)),
                                    rs.getString("motivo"), rs.getString("decidido_por_nombre"),
                                    VideosService.instante(rs, "decidido_en"), estado == Estado.PENDIENTE && !retirado);
                        }, id)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("VIDEO_NO_ENCONTRADO", "El video no existe."));
    }

    private Contadores contadores() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM video_tienda WHERE estado = 'EN_REVISION' AND retirado_en IS NULL),
                       count(*) FILTER (WHERE estado = 'PUBLICADO'),
                       count(*) FILTER (WHERE estado = 'RECHAZADO')
                FROM video_tienda
                WHERE decidido_en IS NOT NULL
                  AND to_char(decidido_en AT TIME ZONE 'America/Lima', 'YYYY-MM') = to_char(now() AT TIME ZONE 'America/Lima', 'YYYY-MM')""",
                (rs, row) -> new Contadores(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
    }
}
