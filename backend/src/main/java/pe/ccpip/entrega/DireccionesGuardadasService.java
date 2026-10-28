package pe.ccpip.entrega;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.entrega.EntregaModelos.DireccionGuardada;
import pe.ccpip.entrega.EntregaModelos.NuevaDireccion;

/**
 * CCPIP-65 (RF-46): el comprador guarda sus direcciones de entrega habituales. Solo datos propios: el
 * comprador sale de la sesion y nunca de la peticion (RNF-05). Solo destinos admitidos: el distrito debe estar
 * en el catalogo territorial. CCPIP-393: el comprador consulta las suyas; CCPIP-395: las retira. Modificarlas
 * llega con CCPIP-394.
 */
@Service
public class DireccionesGuardadasService {

    /** Una direccion guardada con su distrito, provincia y departamento; se completa con el filtro que corresponda. */
    private static final String DIRECCIONES = """
            SELECT d.id, d.alias, d.ubigeo, g.departamento, g.provincia, g.distrito, d.direccion, d.referencia
            FROM direccion_guardada d JOIN ubigeo g ON g.codigo = d.ubigeo
            """;

    private static final RowMapper<DireccionGuardada> DIRECCION = (rs, row) -> new DireccionGuardada(rs.getLong("id"),
            rs.getString("alias"), rs.getString("ubigeo"), rs.getString("departamento"), rs.getString("provincia"),
            rs.getString("distrito"), rs.getString("direccion"), rs.getString("referencia"));

    private final NamedParameterJdbcTemplate jdbc;

    public DireccionesGuardadasService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Guarda la direccion del comprador; la misma direccion en el mismo distrito no se guarda dos veces. */
    @Transactional
    public DireccionGuardada guardar(long usuarioId, NuevaDireccion nueva) {
        EntregaService.validarDireccion(nueva.direccion());
        String direccion = nueva.direccion().strip();
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("ubigeo", nueva.ubigeo())
                .addValue("direccion", direccion)
                .addValue("alias", vacioANulo(nueva.alias())).addValue("referencia", vacioANulo(nueva.referencia()));

        Boolean admitido = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ubigeo WHERE codigo = :ubigeo)", p, Boolean.class);
        if (!Boolean.TRUE.equals(admitido)) {
            throw ErrorNegocio.invalido("DESTINO_NO_ADMITIDO", "Ese distrito no está entre los destinos admitidos.");
        }
        Boolean repetida = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM direccion_guardada
                               WHERE usuario_id = :usuario AND activa AND ubigeo = :ubigeo AND lower(direccion) = lower(:direccion))""",
                p, Boolean.class);
        if (Boolean.TRUE.equals(repetida)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "DIRECCION_YA_GUARDADA", "Ya guardaste esa dirección.");
        }
        long id = jdbc.queryForObject("""
                INSERT INTO direccion_guardada (usuario_id, alias, ubigeo, direccion, referencia)
                VALUES (:usuario, :alias, :ubigeo, :direccion, :referencia) RETURNING id""", p, Long.class);
        return jdbc.queryForObject(DIRECCIONES + "WHERE d.id = :id", new MapSqlParameterSource("id", id), DIRECCION);
    }

    /** CCPIP-394: modifica únicamente una dirección activa propia; los pedidos conservan su snapshot. */
    @Transactional
    public DireccionGuardada modificar(long usuarioId, long direccionId, NuevaDireccion nueva) {
        EntregaService.validarDireccion(nueva.direccion());
        String direccion = nueva.direccion().strip();
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("id", direccionId)
                .addValue("ubigeo", nueva.ubigeo()).addValue("direccion", direccion)
                .addValue("alias", vacioANulo(nueva.alias())).addValue("referencia", vacioANulo(nueva.referencia()));
        Boolean admitido = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ubigeo WHERE codigo = :ubigeo)", p, Boolean.class);
        if (!Boolean.TRUE.equals(admitido)) {
            throw ErrorNegocio.invalido("DESTINO_NO_ADMITIDO", "Ese distrito no está entre los destinos admitidos.");
        }
        Boolean repetida = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM direccion_guardada
                               WHERE usuario_id = :usuario AND activa AND id <> :id AND ubigeo = :ubigeo
                                 AND lower(direccion) = lower(:direccion))""", p, Boolean.class);
        if (Boolean.TRUE.equals(repetida)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "DIRECCION_YA_GUARDADA", "Ya guardaste esa dirección.");
        }
        int actualizadas = jdbc.update("""
                UPDATE direccion_guardada SET alias = :alias, ubigeo = :ubigeo, direccion = :direccion,
                    referencia = :referencia WHERE id = :id AND usuario_id = :usuario AND activa""", p);
        if (actualizadas == 0) throw ErrorNegocio.noEncontrado("DIRECCION_NO_ENCONTRADA", "La dirección no existe.");
        return jdbc.queryForObject(DIRECCIONES + "WHERE d.id = :id", p, DIRECCION);
    }

    /**
     * CCPIP-393: las direcciones activas del comprador de la sesion, la ultima que guardo primero. Las de otros
     * compradores y las retiradas (CCPIP-395) no aparecen (RNF-05).
     */
    @Transactional(readOnly = true)
    public List<DireccionGuardada> listar(long usuarioId) {
        return jdbc.query(DIRECCIONES + "WHERE d.usuario_id = :usuario AND d.activa ORDER BY d.creado_en DESC, d.id DESC",
                new MapSqlParameterSource("usuario", usuarioId), DIRECCION);
    }

    private static String vacioANulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    /** Retira logicamente una direccion activa propia; cualquier otra no existe para el comprador (RNF-05). */
    @Transactional
    public void retirar(long usuarioId, long direccionId) {
        int actualizadas = jdbc.update("""
                UPDATE direccion_guardada SET activa = false
                WHERE id = :direccion AND usuario_id = :usuario AND activa""",
                new MapSqlParameterSource("direccion", direccionId).addValue("usuario", usuarioId));
        if (actualizadas == 0) {
            throw ErrorNegocio.noEncontrado("DIRECCION_NO_ENCONTRADA", "La dirección no existe.");
        }
    }
}
