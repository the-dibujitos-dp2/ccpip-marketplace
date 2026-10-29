package pe.ccpip.cobertura;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.cobertura.CoberturaModelos.CoberturaPunto;
import pe.ccpip.cobertura.CoberturaModelos.Zona;

/**
 * CCPIP-401: lectura por asignacion, sin alterar reglas comerciales ni la consulta del comprador.
 * CCPIP-149: el punto declara los distritos que atiende, solo del catalogo territorial vigente.
 */
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
    /**
     * CCPIP-149 (RF-118): declara que el punto propio entrega en esos distritos del catalogo territorial. Un
     * distrito ya declarado e inactivo se reactiva; uno activo queda igual. No toca pedidos ni entregas: cada
     * entrega guarda su propio distrito, asi que declarar no altera lo ya vendido. Solo Peru: el destino
     * internacional sigue pendiente (C9). La capacidad aprobada aun no esta definida por la Camara.
     */
    @Transactional
    public CoberturaPunto declarar(long usuarioId, long puntoId, Collection<String> ubigeos) {
        puntos.nombres(usuarioId);
        var p = new MapSqlParameterSource("usuario", usuarioId).addValue("punto", puntoId);
        Integer propio = jdbc.queryForObject("""
                SELECT count(*) FROM ubicacion u JOIN usuario_ubicacion uu ON uu.ubicacion_id=u.id
                WHERE u.id=:punto AND uu.usuario_id=:usuario AND u.tipo='PUNTO_ACOPIO'""", p, Integer.class);
        if (propio == null || propio == 0) {
            throw ErrorNegocio.noEncontrado("PUNTO_NO_ENCONTRADO", "El punto no existe o no esta asignado a tu cuenta.");
        }
        List<String> pedidos = ubigeos.stream().map(String::strip).distinct().toList();
        List<String> conocidos = jdbc.queryForList("SELECT codigo FROM ubigeo WHERE codigo IN (:codigos)",
                new MapSqlParameterSource("codigos", pedidos), String.class);
        List<String> faltan = pedidos.stream().filter(c -> !conocidos.contains(c)).toList();
        if (!faltan.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "UBIGEO_NO_CATALOGADO",
                    "Algunos distritos no estan en el catalogo territorial.", faltan);
        }
        for (String ubigeo : pedidos) {
            jdbc.update("""
                    INSERT INTO cobertura_entrega (ubicacion_id, ubigeo) VALUES (:punto, :ubigeo)
                    ON CONFLICT (ubicacion_id, ubigeo) DO UPDATE SET activa = true, actualizado_en = now()
                    WHERE NOT cobertura_entrega.activa""", new MapSqlParameterSource(p.getValues()).addValue("ubigeo", ubigeo));
        }
        return listar(usuarioId, puntoId).get(0);
    }
    private static final class Grupo {
        private final long id;
        private final String nombre;
        private final List<Zona> zonas = new ArrayList<>();
        private Grupo(long id, String nombre) { this.id = id; this.nombre = nombre; }
        private CoberturaPunto respuesta() { return new CoberturaPunto(id, nombre, List.copyOf(zonas)); }
    }
}
