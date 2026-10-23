package pe.ccpip.territorio;

import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import pe.ccpip.entrega.EntregaModelos.Distrito;

/**
 * CCPIP-61 (RF-42): el catalogo territorial del Peru (departamento, provincia y distrito con su ubigeo)
 * para que el comprador indique el destino de su pedido sin depender de mapas (RNF-31). Se lee por niveles:
 * cada lista depende de la eleccion anterior.
 */
@Service
public class TerritorioService {

    private final NamedParameterJdbcTemplate jdbc;

    public TerritorioService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> departamentos() {
        return jdbc.getJdbcTemplate().queryForList("SELECT DISTINCT departamento FROM ubigeo ORDER BY departamento", String.class);
    }

    public List<String> provincias(String departamento) {
        return jdbc.queryForList("SELECT DISTINCT provincia FROM ubigeo WHERE departamento = :departamento ORDER BY provincia",
                new MapSqlParameterSource("departamento", departamento), String.class);
    }

    public List<Distrito> distritos(String departamento, String provincia) {
        return jdbc.query("""
                SELECT codigo, departamento, provincia, distrito FROM ubigeo
                WHERE departamento = :departamento AND provincia = :provincia ORDER BY distrito""",
                new MapSqlParameterSource("departamento", departamento).addValue("provincia", provincia),
                (rs, row) -> new Distrito(rs.getString("codigo"), rs.getString("departamento"), rs.getString("provincia"),
                        rs.getString("distrito")));
    }
}
