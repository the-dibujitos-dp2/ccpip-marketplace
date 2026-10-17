package pe.ccpip.traduccion;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.ccpip.comun.Idioma;

/**
 * Contenido en otro idioma (CCPIP-166, CCPIP-357, CCPIP-358). El espanol es el original y no pasa
 * por aqui; una traduccion pendiente nunca se lee en el catalogo.
 *
 * CCPIP-170 (RF-164): cada traduccion guarda la version del original desde la que se hizo, y es
 * unica por entidad, clave, campo e idioma: traducir otra vez reemplaza, nunca duplica.
 * CCPIP-373 (RF-263): al cambiar el original queda DESACTUALIZADA y deja de publicarse.
 */
@Repository
public class Traducciones {

    /** Lo que se traduce. La clave es el id del producto o del productor, o el codigo del catalogo. */
    public enum Entidad { PRODUCTO, HISTORIA, TIENDA, COMUNIDAD, LINEA, UNIDAD }

    public enum Estado { PENDIENTE, APROBADA, DESACTUALIZADA }

    /** CCPIP-171: quien tradujo. COMUNIDAD es una persona (productor o Camara); AUTOMATICA, un proveedor. */
    public enum Origen { COMUNIDAD, AUTOMATICA }

    /**
     * Lo que el catalogo sabe de un campo traducido. {@code vigente} falso es una traduccion que se
     * publico y cuyo original cambio despues (CCPIP-373): se muestra el original con un aviso.
     */
    public record Publicada(String texto, Origen origen, boolean vigente) {
    }

    /** Una traduccion con su estado, para el editor del productor. */
    public record Fila(Entidad entidad, String campo, String texto, Estado estado, Origen origen, String proveedor,
                       int versionOriginal) {
    }

    /** Version vigente de un campo original: sin fila en contenido_version es la 1. */
    private static final String VERSION_ACTUAL = """
            coalesce((SELECT cv.version FROM contenido_version cv
                      WHERE cv.entidad = t.entidad AND cv.clave = t.clave AND cv.campo = t.campo), 1)""";

    private final NamedParameterJdbcTemplate jdbc;

    public Traducciones(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lo publicable de esas claves en el idioma pedido: clave → (campo → traduccion). Incluye las
     * aprobadas y las que se publicaron pero quedaron desactualizadas. Vacio en espanol o sin
     * claves, sin consultar la base.
     */
    public Map<String, Map<String, Publicada>> publicables(Entidad entidad, Collection<String> claves, Idioma idioma) {
        Set<String> unicas = claves.stream().filter(c -> c != null && !c.isBlank()).collect(Collectors.toSet());
        if (idioma == Idioma.ES || unicas.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("entidad", entidad.name())
                .addValue("idioma", idioma.codigo())
                .addValue("claves", unicas);
        Map<String, Map<String, Publicada>> textos = new HashMap<>();
        // Vigente solo si sigue aprobada y se hizo sobre la version actual del original
        jdbc.query("""
                SELECT t.clave, t.campo, t.texto, t.origen,
                       (t.estado = 'APROBADA' AND t.version_original = %s) AS vigente
                FROM traduccion t
                WHERE t.entidad = :entidad AND t.idioma = :idioma AND t.clave IN (:claves)
                  AND (t.estado = 'APROBADA' OR (t.estado = 'DESACTUALIZADA' AND t.aprobada_en IS NOT NULL))"""
                .formatted(VERSION_ACTUAL),
                p, rs -> {
                    textos.computeIfAbsent(rs.getString("clave"), k -> new HashMap<>())
                            .put(rs.getString("campo"), new Publicada(rs.getString("texto"),
                                    Origen.valueOf(rs.getString("origen")), rs.getBoolean("vigente")));
                });
        return textos;
    }

    /** Todas las traducciones de un contenido en un idioma, en cualquier estado. */
    public List<Fila> deContenido(Collection<Entidad> entidades, String clave, Idioma idioma) {
        MapSqlParameterSource p = new MapSqlParameterSource("entidades", entidades.stream().map(Enum::name).toList())
                .addValue("clave", clave)
                .addValue("idioma", idioma.codigo());
        return jdbc.query("""
                SELECT entidad, campo, texto, estado, origen, proveedor, version_original
                FROM traduccion WHERE entidad IN (:entidades) AND clave = :clave AND idioma = :idioma""",
                p, (rs, row) -> new Fila(Entidad.valueOf(rs.getString("entidad")), rs.getString("campo"),
                        rs.getString("texto"), Estado.valueOf(rs.getString("estado")),
                        Origen.valueOf(rs.getString("origen")), rs.getString("proveedor"), rs.getInt("version_original")));
    }

    /** Version vigente del campo original (1 si nunca se edito). */
    public int version(Entidad entidad, String clave, String campo) {
        List<Integer> v = jdbc.queryForList("""
                SELECT version FROM contenido_version WHERE entidad = :entidad AND clave = :clave AND campo = :campo""",
                campo(entidad, clave, campo), Integer.class);
        return v.isEmpty() ? 1 : v.get(0);
    }

    /**
     * CCPIP-147: guarda la traduccion automatica como PENDIENTE: no se publica hasta que el productor
     * la revise (RNF-33). Reemplaza la fila del mismo campo e idioma, nunca la duplica (CCPIP-170).
     */
    public void guardarAutomatica(Entidad entidad, String clave, String campo, Idioma idioma, String texto,
                                  String proveedor, int version) {
        jdbc.update("""
                INSERT INTO traduccion (entidad, clave, campo, idioma, texto, estado, origen, proveedor, version_original)
                VALUES (:entidad, :clave, :campo, :idioma, :texto, 'PENDIENTE', 'AUTOMATICA', :proveedor, :version)
                ON CONFLICT (entidad, clave, campo, idioma) DO UPDATE SET texto = EXCLUDED.texto, estado = 'PENDIENTE',
                    origen = 'AUTOMATICA', proveedor = EXCLUDED.proveedor, version_original = EXCLUDED.version_original,
                    aprobada_en = NULL, desactualizada_en = NULL, actualizado_en = now()""",
                campo(entidad, clave, campo).addValue("idioma", idioma.codigo()).addValue("texto", texto)
                        .addValue("proveedor", proveedor).addValue("version", version));
    }

    /** Publica la traduccion pendiente tal como la propuso el proveedor. */
    public int aprobarPendiente(Entidad entidad, String clave, String campo, Idioma idioma) {
        return jdbc.update("""
                UPDATE traduccion SET estado = 'APROBADA', aprobada_en = now(), actualizado_en = now()
                WHERE entidad = :entidad AND clave = :clave AND campo = :campo AND idioma = :idioma AND estado = 'PENDIENTE'""",
                campo(entidad, clave, campo).addValue("idioma", idioma.codigo()));
    }

    /**
     * Publica un texto escrito o corregido por el productor sobre la version actual del original. Si
     * coincide con la propuesta automatica pendiente, conserva su origen y proveedor.
     */
    public void aprobarTexto(Entidad entidad, String clave, String campo, Idioma idioma, String texto, int version) {
        jdbc.update("""
                INSERT INTO traduccion (entidad, clave, campo, idioma, texto, estado, origen, version_original, aprobada_en)
                VALUES (:entidad, :clave, :campo, :idioma, :texto, 'APROBADA', 'COMUNIDAD', :version, now())
                ON CONFLICT (entidad, clave, campo, idioma) DO UPDATE SET
                    origen = CASE WHEN traduccion.estado = 'PENDIENTE' AND traduccion.texto = EXCLUDED.texto
                                  THEN traduccion.origen ELSE 'COMUNIDAD' END,
                    proveedor = CASE WHEN traduccion.estado = 'PENDIENTE' AND traduccion.texto = EXCLUDED.texto
                                     THEN traduccion.proveedor END,
                    texto = EXCLUDED.texto, estado = 'APROBADA', version_original = EXCLUDED.version_original,
                    aprobada_en = now(), desactualizada_en = NULL, actualizado_en = now()""",
                campo(entidad, clave, campo).addValue("idioma", idioma.codigo()).addValue("texto", texto)
                        .addValue("version", version));
    }

    /**
     * CCPIP-373: el original de ese campo cambio. Sube su version y deja sus traducciones, en todos
     * los idiomas, DESACTUALIZADAS sin borrarlas. Devuelve cuantas quedaron desactualizadas.
     */
    public int invalidar(Entidad entidad, String clave, String campo) {
        MapSqlParameterSource p = campo(entidad, clave, campo);
        jdbc.update("""
                INSERT INTO contenido_version (entidad, clave, campo, version) VALUES (:entidad, :clave, :campo, 2)
                ON CONFLICT (entidad, clave, campo) DO UPDATE SET version = contenido_version.version + 1, actualizado_en = now()""",
                p);
        return jdbc.update("""
                UPDATE traduccion SET estado = 'DESACTUALIZADA', desactualizada_en = now(), actualizado_en = now()
                WHERE entidad = :entidad AND clave = :clave AND campo = :campo AND estado <> 'DESACTUALIZADA'""", p);
    }

    private static MapSqlParameterSource campo(Entidad entidad, String clave, String campo) {
        return new MapSqlParameterSource("entidad", entidad.name()).addValue("clave", clave).addValue("campo", campo);
    }
}
