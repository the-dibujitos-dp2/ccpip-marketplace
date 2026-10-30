package pe.ccpip.merma;

import java.time.Instant;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.comun.ErrorNegocio;

@Service
public class SolicitudRevisionMermaService {
    public record Solicitud(long id, long mermaId, long solicitanteId, String solicitante, Instant registradaEn) {}
    private final NamedParameterJdbcTemplate jdbc;
    public SolicitudRevisionMermaService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Solicitud registrar(long usuarioId, long mermaId) {
        var p = new MapSqlParameterSource().addValue("usuario", usuarioId).addValue("merma", mermaId);
        var permitida = jdbc.query("""
            SELECT 1 FROM merma m JOIN lote l ON l.id=m.lote_id
            JOIN ubicacion u ON u.id=l.ubicacion_id
            JOIN usuario_ubicacion uu ON uu.ubicacion_id=l.ubicacion_id
            WHERE m.movimiento_id=:merma AND u.tipo='PUNTO_ACOPIO' AND uu.usuario_id=:usuario
            """, p, (ResultSetExtractor<Boolean>) rs -> rs.next());
        if (!permitida) throw new ErrorNegocio(HttpStatus.NOT_FOUND, "MERMA_NO_ENCONTRADA", "No se encontró la merma.");
        return jdbc.queryForObject("""
            INSERT INTO solicitud_revision_merma (merma_movimiento_id, solicitante_id)
            VALUES (:merma,:usuario)
            RETURNING id, merma_movimiento_id, solicitante_id,
              (SELECT nombre FROM usuario WHERE id=solicitante_id) AS solicitante, registrada_en
            """, p, (rs, n) -> new Solicitud(rs.getLong("id"), rs.getLong("merma_movimiento_id"),
                rs.getLong("solicitante_id"), rs.getString("solicitante"), rs.getTimestamp("registrada_en").toInstant()));
    }
}
