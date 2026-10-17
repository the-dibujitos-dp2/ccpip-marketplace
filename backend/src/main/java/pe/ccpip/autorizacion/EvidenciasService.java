package pe.ccpip.autorizacion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.autorizacion.AutorizacionModelos.Autorizacion;
import pe.ccpip.autorizacion.AutorizacionModelos.Estado;
import pe.ccpip.autorizacion.AutorizacionModelos.Evidencia;
import pe.ccpip.autorizacion.AutorizacionModelos.EvidenciasProducto;
import pe.ccpip.autorizacion.AutorizacionModelos.NuevaEvidencia;
import pe.ccpip.autorizacion.AutorizacionModelos.TipoEvidencia;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.imagenes.ImagenStorage;
import pe.ccpip.productor.Auditoria;
import pe.ccpip.productor.Auditoria.Accion;

/**
 * CCPIP-36 (RF-13): el productor adjunta certificados, documentos y evidencias a cada producto cuya
 * autorizacion solicita. Todo se filtra por el productor de la sesion (RNF-05): un producto ajeno
 * no existe para el. El archivo va al bucket privado y solo se sirve con sesion (RNF-08).
 *
 * <p>Subir la primera evidencia crea la solicitud; si estaba RECHAZADA, REVOCADA o su aprobacion ya
 * vencio, vuelve a SOLICITADA para una nueva revision. Mientras esta APROBADA y vigente no se
 * agregan ni se retiran evidencias: sustentan la decision tomada.
 */
@Service
public class EvidenciasService {

    public static final int MAX_EVIDENCIAS = 10;
    static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    private final JdbcTemplate jdbc;
    private final ImagenStorage storage;
    private final Auditoria auditoria;

    public EvidenciasService(JdbcTemplate jdbc, ImagenStorage storage, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public EvidenciasProducto listar(long productorId, long productoId) {
        String nombre = productoPropio(productorId, productoId);
        Optional<Autorizacion> autorizacion = autorizacion(productorId, productoId);
        List<Evidencia> evidencias = autorizacion
                .map(a -> vigentes(a.id(), EvidenciasService::urlProductor))
                .orElse(List.of());
        Autorizacion vista = autorizacion.orElse(new Autorizacion(null, Estado.SIN_SOLICITUD, null, null, null, null));
        return new EvidenciasProducto(productoId, nombre, vista, evidencias,
                admiteCambios(vista) && evidencias.size() < MAX_EVIDENCIAS, MAX_EVIDENCIAS);
    }

    @Transactional
    public Evidencia subir(long productorId, long usuarioId, long productoId, NuevaEvidencia datos,
                           String nombreArchivo, byte[] archivo) {
        productoPropio(productorId, productoId);
        validar(datos);
        FormatoEvidencia formato = FormatoEvidencia.detectar(archivo);

        Autorizacion autorizacion = solicitud(productorId, productoId);
        if (!admiteCambios(autorizacion)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "AUTORIZACION_VIGENTE",
                    "Este producto ya está autorizado. Sus evidencias sustentan esa decisión y no se cambian.");
        }
        if (vigentes(autorizacion.id(), id -> null).size() >= MAX_EVIDENCIAS) {
            throw ErrorNegocio.invalido("LIMITE_DE_EVIDENCIAS",
                    "Un producto admite hasta " + MAX_EVIDENCIAS + " evidencias. Retira una para agregar otra.");
        }
        if (autorizacion.estado() != Estado.SOLICITADA) {
            // Vuelve a revision: el motivo anterior queda en autorizacion_decision
            jdbc.update("""
                    UPDATE autorizacion_producto SET estado = 'SOLICITADA', solicitada_en = now(), motivo = NULL,
                           decidido_por = NULL, decidido_en = NULL, vigente_hasta = NULL, decision_id = NULL
                    WHERE id = ?""", autorizacion.id());
        }

        UUID id = UUID.randomUUID();
        String clave = "evidencias/productores/" + productorId + "/" + id;
        storage.guardar(clave, archivo, formato.contentType());
        jdbc.update("""
                INSERT INTO evidencia_producto (id, autorizacion_id, productor_id, producto_id, tipo, titulo, emisor,
                                                vigente_desde, vigente_hasta, nombre_archivo, content_type, bytes,
                                                clave, subida_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, autorizacion.id(), productorId, productoId, datos.tipo().name(), datos.titulo().strip(),
                vacioANulo(datos.emisor()), datos.vigenteDesde(), datos.vigenteHasta(), nombreSeguro(nombreArchivo),
                formato.contentType(), archivo.length, clave, usuarioId);
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put("evidencia", id.toString());
        cambios.put("tipo", datos.tipo().name());
        cambios.put("titulo", datos.titulo().strip());
        auditoria.registrar(productoId, usuarioId, Accion.EVIDENCIA, cambios);
        return buscar("e.id = ?", id, EvidenciasService::urlProductor).orElseThrow();
    }

    /** Retiro logico: deja de sustentar la solicitud, pero se conserva por las decisiones que la citan. */
    @Transactional
    public void retirar(long productorId, long usuarioId, long productoId, UUID evidenciaId) {
        productoPropio(productorId, productoId);
        Autorizacion autorizacion = autorizacion(productorId, productoId).orElseThrow(EvidenciasService::noEncontrada);
        Evidencia evidencia = buscar("e.id = ? AND e.retirada_en IS NULL", evidenciaId, id -> null)
                .filter(e -> e.productoId() == productoId)
                .orElseThrow(EvidenciasService::noEncontrada);
        if (!admiteCambios(autorizacion)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "EVIDENCIA_EN_USO",
                    "Esta evidencia sustenta la autorización vigente del producto y no se puede retirar.");
        }
        jdbc.update("UPDATE evidencia_producto SET retirada_en = now(), retirada_por = ? WHERE id = ?", usuarioId, evidencia.id());
        auditoria.registrar(productoId, usuarioId, Accion.EVIDENCIA, Map.of("retirada", evidencia.id().toString()));
    }

    /** Archivo de una evidencia propia, aunque este retirada. */
    public Optional<Archivo> archivoPropio(long productorId, UUID evidenciaId) {
        return archivo("id = ? AND productor_id = ?", evidenciaId, productorId);
    }

    public record Archivo(String nombre, String contentType, byte[] contenido) {
        // Un record con un arreglo compara por referencia: se compara por contenido
        @Override
        public boolean equals(Object o) {
            return o instanceof Archivo a && Objects.equals(nombre, a.nombre)
                    && Objects.equals(contentType, a.contentType) && Arrays.equals(contenido, a.contenido);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(nombre, contentType) + Arrays.hashCode(contenido);
        }

        @Override
        public String toString() {
            return "Archivo[nombre=" + nombre + ", contentType=" + contentType + ", bytes="
                    + (contenido == null ? 0 : contenido.length) + "]";
        }
    }

    // --- Consultas compartidas con la revision del subadministrador ---

    Optional<Archivo> archivo(String condicion, Object... parametros) {
        record Ref(String clave, String nombre, String contentType) {
        }
        return jdbc.query("SELECT clave, nombre_archivo, content_type FROM evidencia_producto WHERE " + condicion,
                        (rs, row) -> new Ref(rs.getString(1), rs.getString(2), rs.getString(3)), parametros)
                .stream().findFirst()
                .flatMap(ref -> storage.leer(ref.clave()).map(bytes -> new Archivo(ref.nombre(), ref.contentType(), bytes)));
    }

    List<Evidencia> vigentes(long autorizacionId, Function<UUID, String> url) {
        return jdbc.query(SELECT + " WHERE e.autorizacion_id = ? AND e.retirada_en IS NULL ORDER BY e.subida_en, e.id",
                (rs, row) -> mapear(rs, url), autorizacionId);
    }

    private Optional<Evidencia> buscar(String condicion, Object parametro, Function<UUID, String> url) {
        return jdbc.query(SELECT + " WHERE " + condicion, (rs, row) -> mapear(rs, url), parametro).stream().findFirst();
    }

    static boolean admiteCambios(Autorizacion a) {
        return a.estado() != Estado.APROBADA || a.vigenteHasta() != null && a.vigenteHasta().isBefore(hoyLima());
    }

    private static final String SELECT = """
            SELECT e.id, e.producto_id, e.tipo, e.titulo, e.emisor, e.vigente_desde, e.vigente_hasta,
                   e.vigente_hasta < %s AS vencida, e.nombre_archivo, e.content_type, e.bytes, e.subida_en
            FROM evidencia_producto e""".formatted(HOY);

    private static Evidencia mapear(ResultSet rs, Function<UUID, String> url) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Evidencia(id, rs.getLong("producto_id"), TipoEvidencia.valueOf(rs.getString("tipo")),
                rs.getString("titulo"), rs.getString("emisor"), rs.getObject("vigente_desde", LocalDate.class),
                rs.getObject("vigente_hasta", LocalDate.class), rs.getBoolean("vencida"), rs.getString("nombre_archivo"),
                FormatoEvidencia.deContentType(rs.getString("content_type")).name(), rs.getInt("bytes"),
                instante(rs, "subida_en"), url.apply(id));
    }

    static String urlProductor(UUID id) {
        return "/api/productor/evidencias/" + id + "/archivo";
    }

    static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }

    static LocalDate hoyLima() {
        return LocalDate.now(java.time.ZoneId.of("America/Lima"));
    }

    private String productoPropio(long productorId, long productoId) {
        return jdbc.query("SELECT nombre FROM producto WHERE id = ? AND productor_id = ?",
                        (rs, row) -> rs.getString(1), productoId, productorId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe."));
    }

    private Optional<Autorizacion> autorizacion(long productorId, long productoId) {
        return jdbc.query("""
                        SELECT id, estado, motivo, solicitada_en, decidido_en, vigente_hasta
                        FROM autorizacion_producto WHERE productor_id = ? AND producto_id = ?""",
                (rs, row) -> new Autorizacion(rs.getLong("id"), Estado.valueOf(rs.getString("estado")),
                        rs.getString("motivo"), instante(rs, "solicitada_en"), instante(rs, "decidido_en"),
                        rs.getObject("vigente_hasta", LocalDate.class)),
                productorId, productoId).stream().findFirst();
    }

    /** La solicitud del producto, creada si no existia, bloqueada para esta transaccion. */
    private Autorizacion solicitud(long productorId, long productoId) {
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, solicitada_en)
                VALUES (?, ?, 'SOLICITADA', now())
                ON CONFLICT (productor_id, producto_id) DO NOTHING""", productorId, productoId);
        jdbc.query("SELECT id FROM autorizacion_producto WHERE productor_id = ? AND producto_id = ? FOR UPDATE",
                (rs, row) -> rs.getLong(1), productorId, productoId);
        return autorizacion(productorId, productoId).orElseThrow();
    }

    private static void validar(NuevaEvidencia d) {
        if (d.tipo() == null) {
            throw ErrorNegocio.invalido("TIPO_REQUERIDO", "Indica si es un certificado, un documento o una evidencia.");
        }
        if (d.titulo() == null || d.titulo().isBlank()) {
            throw ErrorNegocio.invalido("TITULO_REQUERIDO", "Escribe el nombre del documento.");
        }
        if (d.titulo().strip().length() > 160 || d.emisor() != null && d.emisor().strip().length() > 160) {
            throw ErrorNegocio.invalido("TEXTO_MUY_LARGO", "El nombre y el emisor admiten hasta 160 caracteres.");
        }
        if (d.tipo() == TipoEvidencia.CERTIFICADO && d.vigenteHasta() == null) {
            throw ErrorNegocio.invalido("VIGENCIA_REQUERIDA", "Indica hasta cuándo vale el certificado.");
        }
        if (d.vigenteDesde() != null && d.vigenteHasta() != null && d.vigenteDesde().isAfter(d.vigenteHasta())) {
            throw ErrorNegocio.invalido("VIGENCIA_INVALIDA", "La vigencia termina antes de empezar.");
        }
    }

    private static String vacioANulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    /** Solo el nombre, sin rutas ni caracteres de control, para mostrarlo y descargarlo. */
    static String nombreSeguro(String original) {
        String nombre = original == null ? "" : original.replace('\\', '/');
        nombre = nombre.substring(nombre.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (nombre.isEmpty()) {
            nombre = "evidencia";
        }
        return nombre.length() > 200 ? nombre.substring(nombre.length() - 200) : nombre;
    }

    private static ErrorNegocio noEncontrada() {
        return ErrorNegocio.noEncontrado("EVIDENCIA_NO_ENCONTRADA", "La evidencia no existe.");
    }
}
