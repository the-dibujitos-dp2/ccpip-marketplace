package pe.ccpip.video;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.imagenes.ImagenStorage;

/**
 * Videos de la tienda del productor (acordado el 26/10, RNF-25): MP4 o WebM propios, de hasta 50 MB y 3
 * minutos, hasta 5 por tienda. Cada video pasa por la revision del subadministrador antes de verse en la
 * tienda publica, como las ofertas (CCPIP-46).
 * <ul>
 *   <li>CCPIP-118 (RF-10): subir un video a la galeria de su tienda.</li>
 *   <li>CCPIP-390 (RF-282): consultar los videos de su propia tienda, con su revision.</li>
 *   <li>CCPIP-391 (RF-283): reemplazar un video por otro, que tambien pasa por la revision.</li>
 * </ul>
 * El archivo va al mismo bucket que las imagenes, en {@code productores/<productor>/videos/}, y se sirve por
 * partes (Range) para que el navegador pueda adelantarlo sin descargarlo entero.
 */
@Service
public class VideosService {

    public static final long MAX_BYTES = 50L * 1024 * 1024;
    public static final int MAX_SEGUNDOS = 180;
    public static final int MAX_VIDEOS = 5;
    public static final int MAX_TITULO = 120;

    public enum Estado { EN_REVISION, PUBLICADO, RECHAZADO }

    /** Un video propio, como lo ve el productor. */
    public record Video(long id, String titulo, String formato, long bytes, BigDecimal duracionSegundos, Estado estado,
                        String motivo, Instant subidoEn, Instant decididoEn, Long reemplazaA, String url) {
    }

    /** Un video aprobado de una tienda publicada. */
    public record VideoPublico(long id, String titulo, BigDecimal duracionSegundos, String url) {
    }

    /** Lo necesario para servir el archivo. */
    public record Archivo(String clave, String contentType, boolean publico) {
    }

    /** Ocupan un lugar los que no estan retirados ni rechazados; un reemplazo en revision no ocupa otro. */
    static final String OCUPAN_LUGAR = """
            retirado_en IS NULL AND estado <> 'RECHAZADO' AND NOT (reemplaza_a IS NOT NULL AND estado = 'EN_REVISION')""";

    private final JdbcTemplate jdbc;
    private final ImagenStorage storage;

    public VideosService(JdbcTemplate jdbc, ImagenStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /** CCPIP-118: el video queda en revision; se ve en la tienda cuando lo aprueba el subadministrador. */
    @Transactional
    public Video subir(long productorId, long usuarioId, String titulo, MultipartFile archivo) {
        String texto = tituloValido(titulo);
        bloquearProductor(productorId);
        Integer ocupados = jdbc.queryForObject("SELECT count(*) FROM video_tienda WHERE productor_id = ? AND " + OCUPAN_LUGAR,
                Integer.class, productorId);
        if (ocupados != null && ocupados >= MAX_VIDEOS) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "LIMITE_DE_VIDEOS",
                    "Tu tienda puede tener hasta " + MAX_VIDEOS + " videos. Retira uno para agregar otro.");
        }
        return buscar(guardar(productorId, usuarioId, texto, archivo, null)).orElseThrow();
    }

    /**
     * CCPIP-391: cambia un video propio por otro, con el mismo titulo si no se da uno nuevo. Si el anterior se
     * ve en la tienda, sigue ahi hasta que la Camara aprueba el nuevo, que apunta a el ({@code reemplaza_a}); si
     * aun no se veia (en revision o rechazado), se retira ya y queda apuntando al nuevo
     * ({@code reemplazado_por}); si era el reemplazo en revision de uno publicado, el nuevo pasa a reemplazar a
     * ese. Asi el historial de cada video se conserva.
     */
    @Transactional
    public Video reemplazar(long productorId, long usuarioId, long id, String titulo, MultipartFile archivo) {
        bloquearProductor(productorId);
        Video anterior = jdbc.query("SELECT * FROM video_tienda WHERE id = ? AND productor_id = ? AND retirado_en IS NULL",
                VideosService::mapear, id, productorId).stream().findFirst().orElseThrow(VideosService::noEncontrado);
        Integer enCurso = jdbc.queryForObject("""
                SELECT count(*) FROM video_tienda WHERE reemplaza_a = ? AND estado = 'EN_REVISION' AND retirado_en IS NULL""",
                Integer.class, id);
        if (enCurso != null && enCurso > 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "REEMPLAZO_EN_REVISION",
                    "Este video ya tiene un reemplazo en revisión. Espera la decisión de la Cámara o retíralo.");
        }
        String texto = titulo == null || titulo.isBlank() ? anterior.titulo() : tituloValido(titulo);
        if (anterior.estado() == Estado.PUBLICADO) {
            return buscar(guardar(productorId, usuarioId, texto, archivo, id)).orElseThrow();
        }
        // Aun no se veia: se retira antes, y si era a su vez un reemplazo, el nuevo hereda a quien reemplaza
        retirar(id, usuarioId, null);
        long nuevo = guardar(productorId, usuarioId, texto, archivo, anterior.reemplazaA());
        jdbc.update("UPDATE video_tienda SET reemplazado_por = ? WHERE id = ?", nuevo, id);
        return buscar(nuevo).orElseThrow();
    }

    /** Retira un video vigente; {@code reemplazadoPor} es el que toma su lugar, si lo hay (CCPIP-391). */
    public void retirar(long id, long usuarioId, Long reemplazadoPor) {
        jdbc.update("""
                UPDATE video_tienda SET retirado_por = ?, retirado_en = now(), reemplazado_por = ?
                WHERE id = ? AND retirado_en IS NULL""", usuarioId, reemplazadoPor, id);
    }

    /**
     * CCPIP-390: los videos vigentes (no retirados) de la tienda del productor, con su estado y el motivo de la
     * revision. Solo los suyos: el productor sale de la sesion (RNF-05).
     */
    public List<Video> propios(long productorId) {
        return jdbc.query("SELECT * FROM video_tienda WHERE productor_id = ? AND retirado_en IS NULL ORDER BY subido_en, id",
                VideosService::mapear, productorId);
    }

    /** CCPIP-118: los videos aprobados y vigentes de una tienda, para su pagina publica. */
    public List<VideoPublico> publicados(long productorId) {
        return jdbc.query("""
                        SELECT id, titulo, duracion_segundos FROM video_tienda
                        WHERE productor_id = ? AND estado = 'PUBLICADO' AND retirado_en IS NULL
                        ORDER BY subido_en, id""",
                (rs, row) -> new VideoPublico(rs.getLong("id"), rs.getString("titulo"),
                        rs.getBigDecimal("duracion_segundos"), url(rs.getLong("id"))), productorId);
    }

    /**
     * El archivo de un video. Uno aprobado y vigente de una tienda publicada es publico; cualquier otro solo
     * lo ve su productor o el subadministrador que lo revisa. Para los demas no existe.
     */
    public Archivo archivo(long id, AuthenticatedSession sesion) {
        record Fila(String clave, String formato, boolean publico, long usuarioProductor) {
        }
        Fila f = jdbc.query("""
                        SELECT v.clave, v.formato, pr.usuario_id,
                               v.estado = 'PUBLICADO' AND v.retirado_en IS NULL AND pr.activo AND pr.tienda_publicada AS publico
                        FROM video_tienda v JOIN productor pr ON pr.id = v.productor_id WHERE v.id = ?""",
                        (rs, row) -> new Fila(rs.getString("clave"), rs.getString("formato"), rs.getBoolean("publico"),
                                rs.getLong("usuario_id")), id)
                .stream().findFirst().orElseThrow(VideosService::noEncontrado);
        boolean autorizado = f.publico() || sesion != null
                && (sesion.role() == Rol.SUB_ADMIN || sesion.role() == Rol.PRODUCTOR && sesion.userId() == f.usuarioProductor());
        if (!autorizado) {
            throw noEncontrado();
        }
        return new Archivo(f.clave(), FormatoVideo.Formato.valueOf(f.formato()).contentType, f.publico());
    }

    public static String url(long id) {
        return "/api/videos/" + id;
    }

    static ErrorNegocio noEncontrado() {
        return ErrorNegocio.noEncontrado("VIDEO_NO_ENCONTRADO", "El video no existe.");
    }

    Optional<Video> buscar(long id) {
        return jdbc.query("SELECT * FROM video_tienda WHERE id = ?", VideosService::mapear, id).stream().findFirst();
    }

    /** Dos subidas a la vez del mismo productor no pasan juntas el limite de videos. */
    void bloquearProductor(long productorId) {
        jdbc.query("SELECT id FROM productor WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), productorId);
    }

    /**
     * Valida el archivo (peso, formato y duracion leidos del contenido), lo sube al bucket y lo registra en
     * revision. El archivo se copia a disco para leerlo sin cargar 50 MB en memoria.
     */
    long guardar(long productorId, long usuarioId, String titulo, MultipartFile archivo, Long reemplazaA) {
        if (archivo == null || archivo.isEmpty()) {
            throw ErrorNegocio.invalido("ARCHIVO_VACIO", "El archivo está vacío.");
        }
        if (archivo.getSize() > MAX_BYTES) {
            throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "VIDEO_MUY_PESADO",
                    "El video pesa más de 50 MB. Recórtalo o bájale la calidad e inténtalo otra vez.");
        }
        Path temporal = null;
        try {
            temporal = crearTemporal();
            archivo.transferTo(temporal);
            FormatoVideo.Leido leido = FormatoVideo.leer(temporal);
            if (leido.duracionSegundos().compareTo(BigDecimal.valueOf(MAX_SEGUNDOS)) > 0) {
                throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_MUY_LARGO",
                        "El video dura más de 3 minutos. Recórtalo e inténtalo otra vez.");
            }
            String clave = "productores/" + productorId + "/videos/" + UUID.randomUUID() + "." + leido.formato().extension;
            storage.guardar(clave, temporal, leido.formato().contentType);
            return jdbc.queryForObject("""
                    INSERT INTO video_tienda (productor_id, titulo, clave, formato, bytes, duracion_segundos, reemplaza_a,
                                              subido_por)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                    productorId, titulo, clave, leido.formato().name(), Files.size(temporal), leido.duracionSegundos(),
                    reemplazaA, usuarioId);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el video subido", e);
        } finally {
            borrarTemporal(temporal);
        }
    }

    static String tituloValido(String titulo) {
        String texto = titulo == null ? "" : titulo.strip();
        if (texto.isEmpty()) {
            throw ErrorNegocio.invalido("TITULO_REQUERIDO", "Escribe un título para el video.");
        }
        if (texto.length() > MAX_TITULO) {
            throw ErrorNegocio.invalido("TITULO_MUY_LARGO", "El título admite hasta " + MAX_TITULO + " caracteres.");
        }
        return texto;
    }

    /**
     * Un archivo temporal que solo lee el backend. En Linux (el servidor y el CI) se crea con permisos de su
     * dueno; donde no hay permisos POSIX (Windows, en desarrollo), en la carpeta del usuario.
     */
    private static Path crearTemporal() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile("video-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(Path.of(System.getProperty("user.home")), ".ccpip-video-", ".tmp");
    }

    private static void borrarTemporal(Path temporal) {
        if (temporal != null) {
            try {
                Files.deleteIfExists(temporal);
            } catch (IOException e) {
                temporal.toFile().deleteOnExit();
            }
        }
    }

    static Video mapear(ResultSet rs, int row) throws SQLException {
        long id = rs.getLong("id");
        return new Video(id, rs.getString("titulo"), rs.getString("formato"), rs.getLong("bytes"),
                rs.getBigDecimal("duracion_segundos"), Estado.valueOf(rs.getString("estado")), rs.getString("motivo"),
                instante(rs, "subido_en"), instante(rs, "decidido_en"), (Long) rs.getObject("reemplaza_a"), url(id));
    }

    public static Instant instante(ResultSet rs, String columna) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
