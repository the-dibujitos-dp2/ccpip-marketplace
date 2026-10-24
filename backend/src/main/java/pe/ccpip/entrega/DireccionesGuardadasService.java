package pe.ccpip.entrega;

import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
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
 * en el catalogo territorial. Consultar, modificar y retirar llegan con CCPIP-393, 394 y 395.
 */
@Service
public class DireccionesGuardadasService {

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
        long id;
        jdbc.getJdbcTemplate().execute("SAVEPOINT direccion_guardada_insert");
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO direccion_guardada (usuario_id, alias, ubigeo, direccion, referencia)
                    VALUES (:usuario, :alias, :ubigeo, :direccion, :referencia) RETURNING id""", p, Long.class);
            jdbc.getJdbcTemplate().execute("RELEASE SAVEPOINT direccion_guardada_insert");
        } catch (DuplicateKeyException e) {
            jdbc.getJdbcTemplate().execute("ROLLBACK TO SAVEPOINT direccion_guardada_insert");
            jdbc.getJdbcTemplate().execute("RELEASE SAVEPOINT direccion_guardada_insert");
            throw direccionRepetida();
        }
        return jdbc.queryForObject("""
                SELECT d.id, d.alias, d.ubigeo, g.departamento, g.provincia, g.distrito, d.direccion, d.referencia
                FROM direccion_guardada d JOIN ubigeo g ON g.codigo = d.ubigeo WHERE d.id = :id""",
                new MapSqlParameterSource("id", id),
                (rs, row) -> new DireccionGuardada(rs.getLong("id"), rs.getString("alias"), rs.getString("ubigeo"),
                        rs.getString("departamento"), rs.getString("provincia"), rs.getString("distrito"),
                        rs.getString("direccion"), rs.getString("referencia")));
    }

    /** Modifica solo una direccion activa propia; los destinos de pedidos confirmados viven en entrega. */
    @Transactional
    public DireccionGuardada modificar(long usuarioId, long direccionId, NuevaDireccion nueva) {
        EntregaService.validarDireccion(nueva.direccion());
        String direccion = nueva.direccion().strip();
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("id", direccionId)
                .addValue("ubigeo", nueva.ubigeo()).addValue("direccion", direccion)
                .addValue("alias", vacioANulo(nueva.alias())).addValue("referencia", vacioANulo(nueva.referencia()));

        Boolean propiaActiva = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM direccion_guardada
                               WHERE id = :id AND usuario_id = :usuario AND activa)""", p, Boolean.class);
        if (!Boolean.TRUE.equals(propiaActiva)) {
            throw ErrorNegocio.noEncontrado("DIRECCION_NO_ENCONTRADA", "La dirección no existe.");
        }
        Boolean admitido = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ubigeo WHERE codigo = :ubigeo)", p, Boolean.class);
        if (!Boolean.TRUE.equals(admitido)) {
            throw ErrorNegocio.invalido("DESTINO_NO_ADMITIDO", "Ese distrito no está entre los destinos admitidos.");
        }
        int actualizadas;
        jdbc.getJdbcTemplate().execute("SAVEPOINT direccion_guardada_update");
        try {
            actualizadas = jdbc.update("""
                    UPDATE direccion_guardada
                    SET alias = :alias, ubigeo = :ubigeo, direccion = :direccion, referencia = :referencia
                    WHERE id = :id AND usuario_id = :usuario AND activa""", p);
            jdbc.getJdbcTemplate().execute("RELEASE SAVEPOINT direccion_guardada_update");
        } catch (DuplicateKeyException e) {
            jdbc.getJdbcTemplate().execute("ROLLBACK TO SAVEPOINT direccion_guardada_update");
            jdbc.getJdbcTemplate().execute("RELEASE SAVEPOINT direccion_guardada_update");
            throw direccionRepetida();
        }
        if (actualizadas == 0) {
            throw ErrorNegocio.noEncontrado("DIRECCION_NO_ENCONTRADA", "La dirección no existe.");
        }
        return jdbc.queryForObject("""
                SELECT d.id, d.alias, d.ubigeo, g.departamento, g.provincia, g.distrito, d.direccion, d.referencia
                FROM direccion_guardada d JOIN ubigeo g ON g.codigo = d.ubigeo WHERE d.id = :id""", p,
                (rs, row) -> new DireccionGuardada(rs.getLong("id"), rs.getString("alias"), rs.getString("ubigeo"),
                        rs.getString("departamento"), rs.getString("provincia"), rs.getString("distrito"),
                        rs.getString("direccion"), rs.getString("referencia")));
    }

    private static String vacioANulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    private static ErrorNegocio direccionRepetida() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "DIRECCION_YA_GUARDADA", "Ya guardaste esa dirección.");
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
