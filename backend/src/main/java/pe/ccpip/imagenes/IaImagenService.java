package pe.ccpip.imagenes;

import java.awt.image.BufferedImage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.ia.IaNoDisponible;
import pe.ccpip.ia.MejoradorImagen;
import pe.ccpip.ia.ValidadorImagen;
import pe.ccpip.productor.Auditoria;
import pe.ccpip.productor.Auditoria.Accion;

/**
 * IA sobre las fotos de producto del productor. Todo es una recomendacion o una propuesta: nada
 * bloquea la publicacion, nada sustituye la foto sin que el productor lo acepte y cualquier
 * fallo deja la original como estaba (RNF-24, RNF-39).
 *
 * <ul>
 * <li>CCPIP-144 y CCPIP-168: validar si la foto corresponde a la linea y consultar el resultado.
 * Cada evaluacion, incluso la que falla, queda en {@code imagen_validacion}.</li>
 * <li>CCPIP-176, CCPIP-272, CCPIP-281 y CCPIP-282: mejora opcional. La variante vive aparte en
 * {@code imagen_mejora} hasta que el productor la compara y la acepta o la rechaza.</li>
 * </ul>
 */
@Service
public class IaImagenService {

    private static final Logger log = LoggerFactory.getLogger(IaImagenService.class);

    public enum EstadoValidacion { ANALIZANDO, VALIDA, SOSPECHOSA, ERROR }

    /**
     * Resultado de una validacion. {@code linea} es la que coincide (valida) o a la que se parece
     * (sospechosa); {@code proveedor} permite avisar en la interfaz que es una validacion de prueba.
     * {@code codigoError}: solo en ERROR, p. ej. IA_NO_DISPONIBLE, para explicar que se sigue con la original.
     */
    public record Validacion(long id, UUID imagenId, EstadoValidacion estado, Linea lineaEsperada, Linea linea,
                             String proveedor, String codigoError, OffsetDateTime solicitadaEn, OffsetDateTime terminadaEn) {
    }

    public record Linea(String codigo, String nombre) {
    }

    public record Vista(String url, String urlMiniatura, int ancho, int alto) {
    }

    /** CCPIP-272: lo necesario para comparar la original con la mejorada. */
    public record Comparacion(UUID mejoraId, UUID imagenId, String estado, Vista original, Vista mejorada,
                              List<String> ajustes, String proveedor, OffsetDateTime solicitadaEn) {
    }

    private final JdbcTemplate jdbc;
    private final ImagenService imagenes;
    private final ImagenStorage storage;
    private final ProcesadorImagen procesador;
    private final ValidadorImagen validador;
    private final MejoradorImagen mejorador;
    private final Auditoria auditoria;

    public IaImagenService(JdbcTemplate jdbc, ImagenService imagenes, ImagenStorage storage, ProcesadorImagen procesador,
                           ValidadorImagen validador, MejoradorImagen mejorador, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.imagenes = imagenes;
        this.storage = storage;
        this.procesador = procesador;
        this.validador = validador;
        this.mejorador = mejorador;
        this.auditoria = auditoria;
    }

    // ---------- Validacion (CCPIP-144, CCPIP-168) ----------

    /**
     * CCPIP-144: pide la evaluacion. Sin transaccion a proposito: la fila ANALIZANDO se ve
     * mientras dura y, si la IA falla, el ERROR queda registrado aunque se responda 503.
     */
    public Validacion validar(long productorId, long usuarioId, UUID id) {
        ImagenService.FotoProducto foto = imagenes.fotoDeProducto(productorId, id);
        List<String> lineas = lineasDelProducto(foto.productoId());
        if (lineas.isEmpty()) {
            throw ErrorNegocio.invalido("LINEA_REQUERIDA", "Elige la línea del producto para validar sus fotos.");
        }
        Long validacion = jdbc.queryForObject("""
                INSERT INTO imagen_validacion (imagen_id, linea_esperada, proveedor, solicitada_por)
                VALUES (?, ?, ?, ?) RETURNING id""", Long.class, id, lineas.get(0), validador.proveedor(), usuarioId);
        try {
            BufferedImage imagen = leer(foto.claveMiniatura());
            ValidadorImagen.Resultado resultado = validador.validar(imagen, lineas);
            jdbc.update("""
                    UPDATE imagen_validacion SET estado = ?, linea_detectada = ?, motivo = left(?, 300), terminada_en = now()
                    WHERE id = ?""", resultado.veredicto().name(), resultado.linea(), resultado.motivo(), validacion);
        } catch (RuntimeException e) {
            // CCPIP-168: un error no impide continuar; se registra y la foto original sigue igual
            log.warn("No se pudo validar la imagen {}: {}", id, e.getMessage());
            jdbc.update("""
                    UPDATE imagen_validacion SET estado = 'ERROR', motivo = left(?, 300), terminada_en = now()
                    WHERE id = ?""", e instanceof ErrorNegocio n ? n.code() : e.getClass().getSimpleName(), validacion);
            throw e instanceof IaNoDisponible n ? n : IaNoDisponible.imagenes();
        }
        return validacion(validacion).orElseThrow();
    }

    /** CCPIP-168: la ultima evaluacion de una foto propia. */
    public Validacion ultimaValidacion(long productorId, UUID id) {
        imagenes.fotoDeProducto(productorId, id);
        return jdbc.query(SELECT_VALIDACION + " WHERE v.imagen_id = ? ORDER BY v.solicitada_en DESC, v.id DESC LIMIT 1",
                        IaImagenService::validacion, id)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("SIN_VALIDACION", "Esta foto aún no se ha validado."));
    }

    /** CCPIP-168: la ultima evaluacion de cada foto vigente de un producto propio, para PW-04. */
    public List<Validacion> validacionesDelProducto(long productorId, long productoId) {
        imagenes.dePublicacion(productorId, productoId);
        return jdbc.query("""
                SELECT DISTINCT ON (v.imagen_id) %s
                WHERE i.producto_id = ? AND i.retirada_en IS NULL
                ORDER BY v.imagen_id, v.solicitada_en DESC, v.id DESC""".formatted(SELECT_VALIDACION.substring("SELECT ".length())),
                IaImagenService::validacion, productoId);
    }

    private Optional<Validacion> validacion(long id) {
        return jdbc.query(SELECT_VALIDACION + " WHERE v.id = ?", IaImagenService::validacion, id).stream().findFirst();
    }

    private static final String SELECT_VALIDACION = """
            SELECT v.id, v.imagen_id, v.estado, v.linea_esperada, le.nombre AS nombre_esperada,
                   v.linea_detectada, ld.nombre AS nombre_detectada, v.proveedor, v.motivo, v.solicitada_en, v.terminada_en
            FROM imagen_validacion v
            JOIN imagen i ON i.id = v.imagen_id
            LEFT JOIN linea_producto le ON le.codigo = v.linea_esperada
            LEFT JOIN linea_producto ld ON ld.codigo = v.linea_detectada""";

    private static Validacion validacion(ResultSet rs, int row) throws SQLException {
        String detectada = rs.getString("linea_detectada");
        EstadoValidacion estado = EstadoValidacion.valueOf(rs.getString("estado"));
        return new Validacion(rs.getLong("id"), rs.getObject("imagen_id", UUID.class), estado,
                new Linea(rs.getString("linea_esperada"), rs.getString("nombre_esperada")),
                detectada == null ? null : new Linea(detectada, rs.getString("nombre_detectada")),
                rs.getString("proveedor"), estado == EstadoValidacion.ERROR ? rs.getString("motivo") : null,
                rs.getObject("solicitada_en", OffsetDateTime.class),
                rs.getObject("terminada_en", OffsetDateTime.class));
    }

    /** Codigos de las lineas del producto, la principal primero. */
    private List<String> lineasDelProducto(long productoId) {
        return jdbc.queryForList("""
                SELECT l.codigo FROM producto_linea pl
                JOIN linea_producto l ON l.id = pl.linea_id
                JOIN producto p ON p.id = pl.producto_id
                WHERE pl.producto_id = ?
                ORDER BY (l.id = p.linea_id) DESC, l.orden""", String.class, productoId);
    }

    // ---------- Mejora opcional (CCPIP-176, CCPIP-272, CCPIP-281, CCPIP-282) ----------

    /**
     * Bloquea la foto propia hasta el final de la transaccion: pedir la mejora, aceptarla o conservar la original
     * sobre una misma foto se ordenan, y cada una decide con lo que dejo la anterior. Sin el bloqueo, dos pedidos
     * simultaneos de mejora chocaban en el indice de la pendiente (500), y dos decisiones simultaneas reemplazaban la
     * foto dos veces o la dejaban reemplazada con la mejora rechazada.
     */
    private void bloquear(long productorId, UUID id) {
        jdbc.queryForList("SELECT id FROM imagen WHERE id = ? AND productor_id = ? FOR UPDATE", UUID.class, id, productorId);
    }

    /**
     * CCPIP-176: solo por pedido expreso del productor. Guarda la variante aparte y la deja
     * pendiente; si ya hay una pendiente se devuelve esa. Si la IA falla no se guarda nada y la
     * original sigue publicada (503).
     */
    @Transactional
    public Comparacion mejorar(long productorId, long usuarioId, UUID id) {
        bloquear(productorId, id);
        ImagenService.FotoProducto foto = imagenes.fotoDeProducto(productorId, id);
        Optional<Comparacion> pendiente = pendiente(foto);
        if (pendiente.isPresent()) {
            return pendiente.get();
        }
        MejoradorImagen.Mejora mejora;
        try {
            mejora = mejorador.mejorar(leer(foto.clave()));
        } catch (IaNoDisponible e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("No se pudo mejorar la imagen {}: {}", id, e.getMessage());
            throw IaNoDisponible.imagenes();
        }
        UUID mejoraId = UUID.randomUUID();
        ImagenService.Archivo archivo = imagenes.subirAlBucket(productorId, "mejoras/" + mejoraId, procesador.desde(mejora.imagen()));
        jdbc.update("""
                INSERT INTO imagen_mejora (id, imagen_id, clave, clave_miniatura, ancho, alto, bytes, ajustes, proveedor, solicitada_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                mejoraId, id, archivo.clave(), archivo.claveMiniatura(), archivo.ancho(), archivo.alto(), archivo.bytes(),
                String.join(",", mejora.ajustes()), mejorador.proveedor(), usuarioId);
        return pendiente(foto).orElseThrow();
    }

    /** CCPIP-272: la original y la mejorada pendiente, lado a lado. */
    public Comparacion comparacion(long productorId, UUID id) {
        return pendiente(imagenes.fotoDeProducto(productorId, id)).orElseThrow(IaImagenService::sinMejora);
    }

    /**
     * CCPIP-282: el productor acepta expresamente la mejorada. Se registra como imagen nueva que
     * toma el lugar de la original, igual que un reemplazo (CCPIP-388): la original queda
     * retirada, se sigue sirviendo y apunta a la nueva.
     */
    @Transactional
    public ImagenService.Imagen aceptar(long productorId, long usuarioId, UUID id) {
        bloquear(productorId, id);
        ImagenService.FotoProducto foto = imagenes.fotoDeProducto(productorId, id);
        Pendiente mejora = pendienteDe(id).orElseThrow(IaImagenService::sinMejora);
        UUID nueva = UUID.randomUUID();
        imagenes.ocuparLugar(productorId, usuarioId, id, nueva, mejora.archivo());
        jdbc.update("""
                UPDATE imagen_mejora SET estado = 'ACEPTADA', decidida_por = ?, decidida_en = now(), imagen_resultante = ?
                WHERE id = ? AND estado = 'PENDIENTE'""", usuarioId, nueva, mejora.id());
        auditoria.registrar(foto.productoId(), usuarioId, Accion.IMAGEN,
                Map.of("mejorada", id.toString(), "nueva", nueva.toString(), "mejora", mejora.id().toString()));
        return imagenes.buscar(nueva).orElseThrow();
    }

    /** CCPIP-281: conservar la original. La variante queda registrada como rechazada y nunca se publica. */
    @Transactional
    public void rechazar(long productorId, long usuarioId, UUID id) {
        bloquear(productorId, id);
        ImagenService.FotoProducto foto = imagenes.fotoDeProducto(productorId, id);
        Pendiente mejora = pendienteDe(id).orElseThrow(IaImagenService::sinMejora);
        jdbc.update("""
                UPDATE imagen_mejora SET estado = 'RECHAZADA', decidida_por = ?, decidida_en = now()
                WHERE id = ? AND estado = 'PENDIENTE'""", usuarioId, mejora.id());
        auditoria.registrar(foto.productoId(), usuarioId, Accion.IMAGEN,
                Map.of("mejoraRechazada", mejora.id().toString(), "conservada", id.toString()));
    }

    /** Bytes de una variante, solo para su dueno: no es publica mientras no se acepte. */
    public Optional<byte[]> contenidoMejora(long productorId, UUID mejoraId, boolean miniatura) {
        return jdbc.query("""
                        SELECT m.clave, m.clave_miniatura FROM imagen_mejora m JOIN imagen i ON i.id = m.imagen_id
                        WHERE m.id = ? AND i.productor_id = ?""",
                        (rs, row) -> miniatura ? rs.getString(2) : rs.getString(1), mejoraId, productorId)
                .stream().findFirst()
                .flatMap(storage::leer);
    }

    public static String urlMejora(UUID mejoraId) {
        return "/api/productor/mejoras/" + mejoraId;
    }

    private record Pendiente(UUID id, ImagenService.Archivo archivo, List<String> ajustes, String proveedor,
                             OffsetDateTime solicitadaEn) {
    }

    private Optional<Pendiente> pendienteDe(UUID imagenId) {
        return jdbc.query("""
                        SELECT id, clave, clave_miniatura, ancho, alto, bytes, ajustes, proveedor, solicitada_en
                        FROM imagen_mejora WHERE imagen_id = ? AND estado = 'PENDIENTE'""",
                        (rs, row) -> new Pendiente(rs.getObject("id", UUID.class),
                                new ImagenService.Archivo(rs.getString("clave"), rs.getString("clave_miniatura"),
                                        rs.getInt("ancho"), rs.getInt("alto"), rs.getInt("bytes")),
                                Arrays.asList(rs.getString("ajustes").split(",")), rs.getString("proveedor"),
                                rs.getObject("solicitada_en", OffsetDateTime.class)),
                        imagenId)
                .stream().findFirst();
    }

    private Optional<Comparacion> pendiente(ImagenService.FotoProducto foto) {
        return pendienteDe(foto.id()).map(m -> new Comparacion(m.id(), foto.id(), "PENDIENTE",
                new Vista(ImagenService.url(foto.id()), ImagenService.urlMiniatura(foto.id()), foto.ancho(), foto.alto()),
                new Vista(urlMejora(m.id()), urlMejora(m.id()) + "?tam=min", m.archivo().ancho(), m.archivo().alto()),
                m.ajustes(), m.proveedor(), m.solicitadaEn()));
    }

    private static ErrorNegocio sinMejora() {
        return ErrorNegocio.noEncontrado("SIN_MEJORA_PENDIENTE", "No hay una versión mejorada pendiente para esta foto.");
    }

    private BufferedImage leer(String clave) {
        byte[] bytes = storage.leer(clave).orElseThrow(() -> new IllegalStateException("Falta en el bucket: " + clave));
        return ProcesadorImagen.leerGuardada(bytes);
    }
}
