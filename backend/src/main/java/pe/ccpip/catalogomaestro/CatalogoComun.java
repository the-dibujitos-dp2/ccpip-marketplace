package pe.ccpip.catalogomaestro;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.Contadores;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.EstadoFiltro;
import pe.ccpip.comun.ErrorNegocio;

/**
 * Lo comun a lineas y productos del catalogo maestro: filtros de la consulta, activar o desactivar sin
 * borrar (RNF-17) y la auditoria de cada cambio en {@code catalogo_maestro_auditoria}.
 */
@Component
class CatalogoComun {

    /** Que se audita. Coincide con el CHECK de catalogo_maestro_auditoria.entidad. */
    enum Entidad { LINEA, PRODUCTO }

    enum Accion { CREAR, EDITAR, DESACTIVAR, REACTIVAR }

    /** Tablas del catalogo maestro. Solo estas dos se activan o desactivan (nunca texto del cliente). */
    enum Tabla {
        LINEA("linea_producto"), PRODUCTO("producto_maestro");

        private final String nombre;

        Tabla(String nombre) {
            this.nombre = nombre;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    CatalogoComun(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Condicion SQL del filtro por estado sobre la columna {@code activo} del alias dado. */
    static String porEstado(EstadoFiltro estado, String alias) {
        return switch (estado == null ? EstadoFiltro.TODOS : estado) {
            case ACTIVOS -> " AND " + alias + ".activo";
            case INACTIVOS -> " AND NOT " + alias + ".activo";
            case TODOS -> "";
        };
    }

    /**
     * Busqueda por nombre que ignora mayusculas y tildes (RNF-22). Agrega el parametro {@code q} y devuelve
     * la condicion, o nada si no hay texto.
     */
    static String porTexto(String q, String columna, MapSqlParameterSource p) {
        if (q == null || q.isBlank()) {
            return "";
        }
        String literal = q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        p.addValue("q", "%" + literal + "%");
        return " AND lower(inmutable_unaccent(" + columna + ")) LIKE lower(inmutable_unaccent(:q))";
    }

    Contadores contadores(Tabla tabla) {
        return jdbc.queryForObject("SELECT count(*) FILTER (WHERE activo) AS activos, count(*) FILTER (WHERE NOT activo) AS inactivos FROM "
                        + tabla.nombre, new MapSqlParameterSource(),
                (rs, row) -> new Contadores(rs.getLong("activos"), rs.getLong("inactivos")));
    }

    /**
     * Activa o desactiva la fila sin borrarla. Desactivar no toca nada que la referencie (ofertas,
     * pedidos, traducciones). Falla con 409 si ya estaba en ese estado.
     */
    void cambiarActivo(Tabla tabla, long id, boolean activo, String codeYaEstaba, String mensajeYaEstaba) {
        int filas = jdbc.update("UPDATE " + tabla.nombre + """
                 SET activo = :activo, desactivado_en = CASE WHEN :activo THEN NULL ELSE now() END, actualizado_en = now()
                WHERE id = :id AND activo <> :activo""", new MapSqlParameterSource("id", id).addValue("activo", activo));
        if (filas == 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, codeYaEstaba, mensajeYaEstaba);
        }
    }

    /** Deja constancia de quien hizo que y cuando. {@code cambios} vacio se guarda como nulo. */
    void auditar(Entidad entidad, long id, long usuarioId, Accion accion, Map<String, ?> cambios) {
        try {
            jdbc.update("""
                    INSERT INTO catalogo_maestro_auditoria (entidad, entidad_id, usuario_id, accion, cambios)
                    VALUES (:entidad, :id, :usuario, :accion, :cambios::jsonb)""", new MapSqlParameterSource()
                    .addValue("entidad", entidad.name()).addValue("id", id).addValue("usuario", usuarioId)
                    .addValue("accion", accion.name())
                    .addValue("cambios", cambios == null || cambios.isEmpty() ? null : json.writeValueAsString(cambios)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo registrar la auditoria del catalogo maestro", e);
        }
    }

    /** Campos que cambiaron entre dos fotos de la misma entidad, con su valor antes y despues. */
    static Map<String, Object> diferencias(Map<String, Object> antes, Map<String, Object> despues) {
        Map<String, Object> cambios = new LinkedHashMap<>();
        despues.forEach((campo, valor) -> {
            Object anterior = antes.get(campo);
            if (!Objects.equals(anterior, valor)) {
                Map<String, Object> cambio = new LinkedHashMap<>();
                cambio.put("antes", anterior);
                cambio.put("despues", valor);
                cambios.put(campo, cambio);
            }
        });
        return cambios;
    }

    /** Id de una unidad del catalogo cerrado (RNF-32), o 422 si no existe. */
    short idUnidad(String codigo) {
        return jdbc.query("SELECT id FROM unidad_medida WHERE codigo = :codigo",
                        new MapSqlParameterSource("codigo", codigo.strip()), (rs, row) -> rs.getShort(1))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.invalido("UNIDAD_INVALIDA", "Elige una unidad del catálogo."));
    }

    /** Texto sin espacios de sobra, o nulo si esta vacio. */
    static String limpio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
