package pe.ccpip.cobertura;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.cobertura.CoberturaModelos.CoberturaPunto;
import pe.ccpip.cobertura.CoberturaModelos.Zona;

/** CCPIP-401: lectura por asignacion, sin alterar reglas comerciales ni la consulta del comprador. */
@Service
public class CoberturasService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;
    public CoberturasService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
    }
    @Transactional(readOnly = true)
    public List<CoberturaPunto> listar(long usuarioId, Long puntoId) {
        puntos.nombres(usuarioId);
        String filtro = puntoId == null ? "" : " AND u.id=:punto";
        var parametros = new MapSqlParameterSource("usuario", usuarioId);
        if (puntoId != null) parametros.addValue("punto", puntoId);
        Map<Long, Grupo> grupos = new LinkedHashMap<>();
        jdbc.query("""
                SELECT u.id AS punto_id, u.nombre AS punto, c.id AS zona_id, g.codigo,
                       g.departamento, g.provincia, g.distrito, c.activa, c.actualizado_en
                FROM ubicacion u JOIN usuario_ubicacion uu ON uu.ubicacion_id=u.id
                LEFT JOIN cobertura_entrega c ON c.ubicacion_id=u.id
                LEFT JOIN ubigeo g ON g.codigo=c.ubigeo
                WHERE uu.usuario_id=:usuario AND u.tipo='PUNTO_ACOPIO'
                """ + filtro + " ORDER BY u.nombre, u.id, g.departamento, g.provincia, g.distrito, g.codigo",
                parametros, (RowCallbackHandler) rs -> {
                    long id = rs.getLong("punto_id");
                    String nombre = rs.getString("punto");
                    Grupo grupo = grupos.computeIfAbsent(id, k -> new Grupo(id, nombre));
                    if (rs.getObject("zona_id") != null) {
                        grupo.zonas.add(new Zona(rs.getLong("zona_id"), rs.getString("codigo"),
                                rs.getString("departamento"), rs.getString("provincia"), rs.getString("distrito"),
                                rs.getBoolean("activa"), rs.getTimestamp("actualizado_en").toInstant()));
                    }
                });
        if (puntoId != null && grupos.isEmpty()) {
            throw ErrorNegocio.noEncontrado("PUNTO_NO_ENCONTRADO", "El punto no existe o no esta asignado a tu cuenta.");
        }
        return grupos.values().stream().map(Grupo::respuesta).toList();
    }
    @Transactional
    public void desactivar(long usuarioId, long zonaId) {
        if (zonaId <= 0) throw ErrorNegocio.invalido("ZONA_INVALIDA", "La zona debe tener identificador positivo.");
        int actualizadas = jdbc.update("""
                UPDATE cobertura_entrega c SET activa=false, actualizado_en=now()
                WHERE c.id=:zona AND c.activa=true AND EXISTS (
                  SELECT 1 FROM usuario_ubicacion uu WHERE uu.ubicacion_id=c.ubicacion_id AND uu.usuario_id=:usuario)
                """, new MapSqlParameterSource("zona", zonaId).addValue("usuario", usuarioId));
        if (actualizadas == 0) {
            Integer existe = jdbc.queryForObject("SELECT count(*) FROM cobertura_entrega WHERE id=:zona", new MapSqlParameterSource("zona", zonaId), Integer.class);
            if (existe != null && existe > 0) {
                Integer propia = jdbc.queryForObject("SELECT count(*) FROM cobertura_entrega c JOIN usuario_ubicacion uu ON uu.ubicacion_id=c.ubicacion_id WHERE c.id=:zona AND uu.usuario_id=:usuario", new MapSqlParameterSource("zona", zonaId).addValue("usuario", usuarioId), Integer.class);
                if (propia != null && propia > 0) return;
            }
            throw ErrorNegocio.noEncontrado("ZONA_NO_ENCONTRADA", "La zona no existe o no está asignada a tu cuenta.");
        }
    }
    private static final class Grupo {
        private final long id;
        private final String nombre;
        private final List<Zona> zonas = new ArrayList<>();
        private Grupo(long id, String nombre) { this.id = id; this.nombre = nombre; }
        private CoberturaPunto respuesta() { return new CoberturaPunto(id, nombre, List.copyOf(zonas)); }
    }
}
