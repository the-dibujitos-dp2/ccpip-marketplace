package pe.ccpip.catalogomaestro;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import pe.ccpip.catalogomaestro.CatalogoComun.Accion;
import pe.ccpip.catalogomaestro.CatalogoComun.Entidad;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.Idioma;
import pe.ccpip.traduccion.Traducciones;

/**
 * CCPIP-119 (RF-162): el productor pide a la Camara una unidad de medida que no esta en el catalogo cerrado
 * (RNF-32). CCPIP-137 (RF-114): el superadministrador la aprueba y la unidad entra al catalogo, con
 * auditoria. CCPIP-353 (RF-243): la rechaza indicando el motivo. Mientras esta pendiente no existe en
 * unidad_medida, asi que ninguna oferta puede usarla; el productor sigue eligiendo del catalogo (RF-27).
 */
@Service
public class UnidadesSolicitadasService {

    public enum Estado { PENDIENTE, APROBADA, RECHAZADA }

    public record NuevaSolicitud(
            @NotBlank(message = "Escribe el nombre de la unidad")
            @Size(max = 60, message = "El nombre admite hasta 60 caracteres") String nombre,
            @NotBlank(message = "Cuéntale a la Cámara para qué la necesitas")
            @Size(max = 500, message = "La explicación admite hasta 500 caracteres") String justificacion) {
    }

    /** `nombre`, `nombreEn` y `codigo` son opcionales: la Camara puede ajustar como entra al catalogo. */
    public record Aprobacion(
            @Size(max = 60, message = "El nombre admite hasta 60 caracteres") String nombre,
            @Size(max = 60, message = "El nombre en inglés admite hasta 60 caracteres") String nombreEn,
            @Size(max = 20, message = "El código admite hasta 20 caracteres") String codigo,
            @NotBlank(message = "Indica el motivo de la decisión")
            @Size(max = 500, message = "El motivo admite hasta 500 caracteres") String motivo) {
    }

    public record Rechazo(
            @NotBlank(message = "Indica el motivo del rechazo")
            @Size(max = 500, message = "El motivo admite hasta 500 caracteres") String motivo) {
    }

    public record UnidadCreada(String codigo, String nombre) {
    }

    public record Solicitud(long id, long productorId, String productor, String nombre, String justificacion,
                            Estado estado, String motivo, String decididaPor, OffsetDateTime decididaEn,
                            UnidadCreada unidad, OffsetDateTime creadaEn) {
    }

    private static final String SOLICITUDES = """
            SELECT s.id, s.productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS productor, s.nombre,
                   s.justificacion, s.estado, s.motivo, d.nombre AS decidida_por, s.decidida_en, s.creada_en,
                   u.codigo AS unidad_codigo, u.nombre AS unidad_nombre
            FROM solicitud_unidad_medida s
            JOIN productor pr ON pr.id = s.productor_id
            LEFT JOIN usuario d ON d.id = s.decidida_por
            LEFT JOIN unidad_medida u ON u.id = s.unidad_medida_id
            """;

    private static final RowMapper<Solicitud> SOLICITUD = (rs, row) -> new Solicitud(rs.getLong("id"),
            rs.getLong("productor_id"), rs.getString("productor"), rs.getString("nombre"), rs.getString("justificacion"),
            Estado.valueOf(rs.getString("estado")), rs.getString("motivo"), rs.getString("decidida_por"),
            rs.getObject("decidida_en", OffsetDateTime.class),
            rs.getString("unidad_codigo") == null ? null : new UnidadCreada(rs.getString("unidad_codigo"), rs.getString("unidad_nombre")),
            rs.getObject("creada_en", OffsetDateTime.class));

    /** Compara nombres sin distinguir mayusculas, tildes ni espacios al borde. */
    private static final String MISMO_NOMBRE = "lower(inmutable_unaccent(btrim(%s))) = lower(inmutable_unaccent(btrim(:nombre)))";

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogoComun comun;
    private final Traducciones traducciones;

    public UnidadesSolicitadasService(NamedParameterJdbcTemplate jdbc, CatalogoComun comun, Traducciones traducciones) {
        this.jdbc = jdbc;
        this.comun = comun;
        this.traducciones = traducciones;
    }

    /** CCPIP-119: no se pide una que ya existe ni se repite una pendiente propia. */
    @Transactional
    public Solicitud solicitar(long productorId, long usuarioId, NuevaSolicitud nueva) {
        String nombre = nueva.nombre().strip();
        exigirNueva(nombre);
        MapSqlParameterSource p = new MapSqlParameterSource("productor", productorId).addValue("usuario", usuarioId)
                .addValue("nombre", nombre).addValue("justificacion", nueva.justificacion().strip());
        boolean repetida = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM solicitud_unidad_medida
                               WHERE productor_id = :productor AND estado = 'PENDIENTE' AND %s)"""
                .formatted(MISMO_NOMBRE.formatted("nombre")), p, Boolean.class));
        if (repetida) {
            throw pendienteRepetida();
        }
        long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO solicitud_unidad_medida (productor_id, solicitada_por, nombre, justificacion)
                    VALUES (:productor, :usuario, :nombre, :justificacion) RETURNING id""", p, Long.class);
        } catch (DuplicateKeyException e) {
            // Dos pedidos iguales al mismo tiempo: el indice unico deja pasar solo uno
            throw pendienteRepetida();
        }
        return buscar(id);
    }

    /** CCPIP-119: las solicitudes del productor, la ultima primero, con lo que decidio la Camara. */
    @Transactional(readOnly = true)
    public List<Solicitud> deProductor(long productorId) {
        return jdbc.query(SOLICITUDES + " WHERE s.productor_id = :productor ORDER BY s.creada_en DESC, s.id DESC",
                new MapSqlParameterSource("productor", productorId), SOLICITUD);
    }

    /** Para la Camara: las pendientes primero la mas antigua; las decididas, la ultima decidida primero. */
    @Transactional(readOnly = true)
    public List<Solicitud> listar(Estado estado) {
        String orden = estado == Estado.PENDIENTE ? "s.creada_en, s.id" : "s.decidida_en DESC, s.id DESC";
        return jdbc.query(SOLICITUDES + " WHERE s.estado = :estado ORDER BY " + orden,
                new MapSqlParameterSource("estado", estado.name()), SOLICITUD);
    }

    /**
     * CCPIP-137: la unidad entra al catalogo con el nombre pedido o el que ajuste la Camara, y su codigo (derivado
     * del nombre si no se indica). Queda en la auditoria del catalogo. Su nombre en ingles es opcional.
     */
    @Transactional
    public Solicitud aprobar(long id, long usuarioId, Aprobacion a) {
        Solicitud s = pendiente(id);
        String nombre = CatalogoComun.limpio(a.nombre()) != null ? CatalogoComun.limpio(a.nombre()) : s.nombre();
        exigirNueva(nombre);
        String codigo = CatalogoComun.limpio(a.codigo()) != null ? a.codigo().strip().toUpperCase(Locale.ROOT) : codigoDe(nombre);
        if (!codigo.matches("[A-Z0-9_]{1,20}")) {
            throw ErrorNegocio.invalido("CODIGO_INVALIDO", "El código usa solo letras sin tilde, números y guion bajo.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("codigo", codigo).addValue("nombre", nombre);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM unidad_medida WHERE codigo = :codigo)", p, Boolean.class))) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CODIGO_DUPLICADO", "Ya existe una unidad con el código " + codigo + ".");
        }
        int unidad = jdbc.queryForObject("INSERT INTO unidad_medida (codigo, nombre) VALUES (:codigo, :nombre) RETURNING id", p, Integer.class);
        String nombreEn = CatalogoComun.limpio(a.nombreEn());
        if (nombreEn != null) {
            traducciones.aprobarTexto(Traducciones.Entidad.UNIDAD, codigo, "nombre", Idioma.EN, nombreEn,
                    traducciones.version(Traducciones.Entidad.UNIDAD, codigo, "nombre"));
        }
        jdbc.update("""
                UPDATE solicitud_unidad_medida SET estado = 'APROBADA', motivo = :motivo, decidida_por = :usuario,
                    decidida_en = now(), unidad_medida_id = :unidad WHERE id = :id""",
                new MapSqlParameterSource("id", id).addValue("motivo", a.motivo().strip()).addValue("usuario", usuarioId)
                        .addValue("unidad", unidad));
        Map<String, Object> foto = new LinkedHashMap<>();
        foto.put("codigo", codigo);
        foto.put("nombre", nombre);
        foto.put("nombreEn", nombreEn);
        foto.put("solicitud", id);
        comun.auditar(Entidad.UNIDAD, unidad, usuarioId, Accion.CREAR, foto);
        return buscar(id);
    }

    /** CCPIP-353: el motivo es obligatorio; el catalogo no cambia. */
    @Transactional
    public Solicitud rechazar(long id, long usuarioId, Rechazo r) {
        pendiente(id);
        jdbc.update("""
                UPDATE solicitud_unidad_medida SET estado = 'RECHAZADA', motivo = :motivo, decidida_por = :usuario,
                    decidida_en = now() WHERE id = :id""",
                new MapSqlParameterSource("id", id).addValue("motivo", r.motivo().strip()).addValue("usuario", usuarioId));
        return buscar(id);
    }

    /** Codigo del catalogo a partir del nombre: "Atado de 10" → "ATADO_DE_10". */
    static String codigoDe(String nombre) {
        String sinTildes = Normalizer.normalize(nombre, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String codigo = sinTildes.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return codigo.length() > 20 ? codigo.substring(0, 20).replaceAll("_+$", "") : codigo;
    }

    private void exigirNueva(String nombre) {
        List<String> existente = jdbc.queryForList("SELECT nombre FROM unidad_medida WHERE " + MISMO_NOMBRE.formatted("nombre"),
                new MapSqlParameterSource("nombre", nombre), String.class);
        if (!existente.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "UNIDAD_YA_EXISTE",
                    "La unidad «" + existente.get(0) + "» ya está en el catálogo: elígela al publicar.");
        }
    }

    private static ErrorNegocio pendienteRepetida() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "SOLICITUD_UNIDAD_PENDIENTE", "Ya pediste esa unidad y la Cámara aún no la revisa.");
    }

    /** Bloquea la solicitud para decidirla una sola vez. */
    private Solicitud pendiente(long id) {
        List<String> estado = jdbc.queryForList("SELECT estado FROM solicitud_unidad_medida WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), String.class);
        if (estado.isEmpty()) {
            throw ErrorNegocio.noEncontrado("SOLICITUD_NO_ENCONTRADA", "La solicitud no existe.");
        }
        if (!Estado.PENDIENTE.name().equals(estado.get(0))) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "SOLICITUD_YA_RESUELTA", "La solicitud ya fue decidida.");
        }
        return buscar(id);
    }

    private Solicitud buscar(long id) {
        return jdbc.queryForObject(SOLICITUDES + " WHERE s.id = :id", new MapSqlParameterSource("id", id), SOLICITUD);
    }
}
