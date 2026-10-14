package pe.ccpip.traduccion;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.ccpip.comun.Idioma;

/**
 * Contenido aprobado en otro idioma (CCPIP-166, CCPIP-357, CCPIP-358). El espanol es el original y
 * no pasa por aqui; una traduccion pendiente nunca se lee.
 */
@Repository
public class Traducciones {

    /** Lo que se traduce. La clave es el id del producto o del productor, o el codigo del catalogo. */
    public enum Entidad { PRODUCTO, HISTORIA, TIENDA, COMUNIDAD, LINEA, UNIDAD }

    private final NamedParameterJdbcTemplate jdbc;

    public Traducciones(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Textos aprobados de esas claves en el idioma pedido: clave → (campo → texto). Vacio en
     * espanol o sin claves, sin consultar la base.
     */
    public Map<String, Map<String, String>> aprobadas(Entidad entidad, Collection<String> claves, Idioma idioma) {
        Set<String> unicas = claves.stream().filter(c -> c != null && !c.isBlank()).collect(Collectors.toSet());
        if (idioma == Idioma.ES || unicas.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("entidad", entidad.name())
                .addValue("idioma", idioma.codigo())
                .addValue("claves", unicas);
        Map<String, Map<String, String>> textos = new HashMap<>();
        jdbc.query("""
                SELECT clave, campo, texto FROM traduccion
                WHERE entidad = :entidad AND idioma = :idioma AND estado = 'APROBADA' AND clave IN (:claves)""",
                p, rs -> {
                    textos.computeIfAbsent(rs.getString("clave"), k -> new HashMap<>())
                            .put(rs.getString("campo"), rs.getString("texto"));
                });
        return textos;
    }
}
