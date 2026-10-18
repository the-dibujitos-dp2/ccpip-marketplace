package pe.ccpip.cuentas;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.Idioma;
import pe.ccpip.cuentas.CuentasModelos.Accion;
import pe.ccpip.cuentas.CuentasModelos.CuentaResumen;
import pe.ccpip.cuentas.CuentasModelos.EstadoCuenta;
import pe.ccpip.cuentas.CuentasModelos.Evento;
import pe.ccpip.cuentas.CuentasModelos.Punto;

/** SQL de la gestion de cuentas. Las reglas (alcance, estados, motivo) estan en {@link CuentasService}. */
@Repository
class CuentasRepository {

    /** Una cuenta tal como esta en la base; lo que la pantalla ve sale de aqui filtrado. */
    record Cuenta(long id, String nombre, String correo, String telefono, Rol rol, String rolNombre, EstadoCuenta estado,
                  boolean correoConfirmado, Idioma idioma, Instant creadaEn) {
    }

    /** Filtros del buscador ya validados contra el alcance de quien busca. */
    record Filtro(Collection<Rol> roles, String texto, EstadoCuenta estado, int pagina, int tamano) {
    }

    private static final TypeReference<Map<String, Object>> MAPA = new TypeReference<>() {
    };

    private static final String PUNTOS_DEL_USUARIO = """
            ARRAY(SELECT ub.nombre FROM usuario_ubicacion uu JOIN ubicacion ub ON ub.id = uu.ubicacion_id
                  WHERE uu.usuario_id = u.id ORDER BY ub.nombre)""";

    private static final String FILTRO_BUSQUEDA = """
            FROM usuario u JOIN rol r ON r.id = u.rol_id
            WHERE r.codigo IN (:roles)
              AND (CAST(:estado AS varchar) IS NULL OR u.estado = :estado)
              AND (CAST(:texto AS varchar) IS NULL
                   OR lower(u.nombre) LIKE :texto ESCAPE '\\' OR lower(u.correo) LIKE :texto ESCAPE '\\')""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    CuentasRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<CuentaResumen> buscar(Filtro f) {
        MapSqlParameterSource p = parametros(f)
                .addValue("limite", f.tamano()).addValue("desde", f.pagina() * f.tamano());
        return jdbc.query("""
                SELECT u.id, u.nombre, u.correo, r.codigo AS rol, r.nombre AS rol_nombre, u.estado,
                       u.correo_confirmado, u.creado_en, %s AS puntos
                %s
                ORDER BY lower(u.nombre), u.id
                LIMIT :limite OFFSET :desde""".formatted(PUNTOS_DEL_USUARIO, FILTRO_BUSQUEDA), p,
                (rs, row) -> new CuentaResumen(rs.getLong("id"), rs.getString("nombre"), rs.getString("correo"),
                        Rol.valueOf(rs.getString("rol")), rs.getString("rol_nombre"),
                        EstadoCuenta.valueOf(rs.getString("estado")), !rs.getBoolean("correo_confirmado"),
                        textos(rs.getArray("puntos")), instante(rs, "creado_en")));
    }

    long contar(Filtro f) {
        Long total = jdbc.queryForObject("SELECT count(*) " + FILTRO_BUSQUEDA, parametros(f), Long.class);
        return total == null ? 0 : total;
    }

    Optional<Cuenta> cuenta(long id) {
        return jdbc.query("""
                SELECT u.id, u.nombre, u.correo, u.telefono, r.codigo AS rol, r.nombre AS rol_nombre, u.estado,
                       u.correo_confirmado, u.idioma, u.creado_en
                FROM usuario u JOIN rol r ON r.id = u.rol_id WHERE u.id = :id""",
                new MapSqlParameterSource("id", id),
                (rs, row) -> new Cuenta(rs.getLong("id"), rs.getString("nombre"), rs.getString("correo"),
                        rs.getString("telefono"), Rol.valueOf(rs.getString("rol")), rs.getString("rol_nombre"),
                        EstadoCuenta.valueOf(rs.getString("estado")), rs.getBoolean("correo_confirmado"),
                        Idioma.deCodigo(rs.getString("idioma")).orElse(Idioma.ES), instante(rs, "creado_en")))
                .stream().findFirst();
    }

    List<Punto> puntosDe(long usuarioId) {
        return jdbc.query("""
                SELECT ub.id, ub.nombre, ub.departamento FROM usuario_ubicacion uu
                JOIN ubicacion ub ON ub.id = uu.ubicacion_id
                WHERE uu.usuario_id = :id ORDER BY ub.nombre""", new MapSqlParameterSource("id", usuarioId),
                (rs, row) -> new Punto(rs.getLong("id"), rs.getString("nombre"), rs.getString("departamento")));
    }

    /** Puntos de acopio activos: los unicos que se asignan (CCPIP-304, "solo ubicacion pertinente"). */
    List<Punto> puntosAsignables() {
        return jdbc.query("""
                SELECT id, nombre, departamento FROM ubicacion
                WHERE tipo = 'PUNTO_ACOPIO' AND activo ORDER BY nombre""", new MapSqlParameterSource(),
                (rs, row) -> new Punto(rs.getLong("id"), rs.getString("nombre"), rs.getString("departamento")));
    }

    boolean correoEnUso(String correo) {
        Boolean existe = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM usuario WHERE lower(correo) = lower(:correo))",
                new MapSqlParameterSource("correo", correo), Boolean.class);
        return Boolean.TRUE.equals(existe);
    }

    long crear(String nombre, String correo, String telefono, Rol rol, String contrasenaHash) {
        Long id = jdbc.queryForObject("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, telefono, rol_id, correo_confirmado)
                SELECT :correo, :hash, :nombre, :telefono, id, false FROM rol WHERE codigo = :rol
                RETURNING id""", new MapSqlParameterSource("correo", correo).addValue("hash", contrasenaHash)
                .addValue("nombre", nombre).addValue("telefono", telefono).addValue("rol", rol.name()), Long.class);
        if (id == null) {
            throw new IllegalStateException("No se pudo crear la cuenta");
        }
        return id;
    }

    void actualizarDatos(long id, String nombre, String telefono) {
        jdbc.update("UPDATE usuario SET nombre = :nombre, telefono = :telefono, actualizado_en = now() WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("nombre", nombre).addValue("telefono", telefono));
    }

    /** Reemplaza los puntos asignados. La asignacion anterior queda en el historial. */
    void asignarPuntos(long usuarioId, Collection<Long> puntos) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", usuarioId).addValue("puntos", puntos);
        jdbc.update("DELETE FROM usuario_ubicacion WHERE usuario_id = :id AND ubicacion_id NOT IN (:puntos)", p);
        jdbc.update("""
                INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
                SELECT :id, ub.id FROM ubicacion ub WHERE ub.id IN (:puntos)
                ON CONFLICT DO NOTHING""", p);
    }

    /**
     * Cambia el estado solo si sigue en {@code desde}: dos administradores a la vez no aplican dos cambios.
     * Devuelve false si otro cambio gano la carrera.
     */
    boolean cambiarEstado(long id, EstadoCuenta desde, EstadoCuenta hacia) {
        return jdbc.update("""
                UPDATE usuario SET estado = :hacia, activo = :activo, actualizado_en = now()
                WHERE id = :id AND estado = :desde""", new MapSqlParameterSource("id", id)
                .addValue("desde", desde.name()).addValue("hacia", hacia.name())
                .addValue("activo", hacia == EstadoCuenta.ACTIVA)) == 1;
    }

    /** Una cuenta que deja de estar activa pierde de inmediato sus sesiones abiertas (RNF-03). */
    void cerrarSesiones(long usuarioId) {
        jdbc.update("DELETE FROM sesion WHERE usuario_id = :id", new MapSqlParameterSource("id", usuarioId));
    }

    void registrar(long usuarioId, Accion accion, String motivo, Map<String, ?> cambios, long realizadoPor) {
        try {
            jdbc.update("""
                    INSERT INTO cuenta_historial (usuario_id, accion, motivo, cambios, realizado_por)
                    VALUES (:usuario, :accion, :motivo, CAST(:cambios AS jsonb), :por)""",
                    new MapSqlParameterSource("usuario", usuarioId).addValue("accion", accion.name())
                            .addValue("motivo", motivo).addValue("por", realizadoPor)
                            .addValue("cambios", cambios == null || cambios.isEmpty() ? null : json.writeValueAsString(cambios)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo registrar el historial de la cuenta", e);
        }
    }

    List<Evento> historial(long usuarioId) {
        return jdbc.query("""
                SELECT h.id, h.accion, h.motivo, h.cambios::text AS cambios, x.nombre AS realizado_por, h.realizado_en
                FROM cuenta_historial h JOIN usuario x ON x.id = h.realizado_por
                WHERE h.usuario_id = :id ORDER BY h.realizado_en DESC, h.id DESC""",
                new MapSqlParameterSource("id", usuarioId),
                (rs, row) -> new Evento(rs.getLong("id"), Accion.valueOf(rs.getString("accion")), rs.getString("motivo"),
                        mapa(rs.getString("cambios")), rs.getString("realizado_por"), instante(rs, "realizado_en")));
    }

    private Map<String, Object> mapa(String texto) {
        if (texto == null) {
            return Map.of();
        }
        try {
            return json.readValue(texto, MAPA);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Historial de cuenta ilegible", e);
        }
    }

    private static MapSqlParameterSource parametros(Filtro f) {
        return new MapSqlParameterSource("roles", f.roles().stream().map(Rol::name).toList())
                .addValue("estado", f.estado() == null ? null : f.estado().name())
                .addValue("texto", f.texto() == null ? null : "%" + escaparLike(f.texto().toLowerCase(Locale.ROOT)) + "%");
    }

    /** El texto se busca literal: % y _ escritos por la persona no son comodines. */
    static String escaparLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> textos(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        OffsetDateTime valor = rs.getObject(columna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }
}
