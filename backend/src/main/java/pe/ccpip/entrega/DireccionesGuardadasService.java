package pe.ccpip.entrega;

import org.springframework.http.HttpStatus;
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
        return jdbc.queryForObject("""
                SELECT d.id, d.alias, d.ubigeo, g.departamento, g.provincia, g.distrito, d.direccion, d.referencia
                FROM direccion_guardada d JOIN ubigeo g ON g.codigo = d.ubigeo WHERE d.id = :id""",
                new MapSqlParameterSource("id", id),
                (rs, row) -> new DireccionGuardada(rs.getLong("id"), rs.getString("alias"), rs.getString("ubigeo"),
                        rs.getString("departamento"), rs.getString("provincia"), rs.getString("distrito"),
                        rs.getString("direccion"), rs.getString("referencia")));
    }

    private static String vacioANulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
