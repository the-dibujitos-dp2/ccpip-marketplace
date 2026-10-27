package pe.ccpip.autorizacion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.autorizacion.EvidenciasService.Archivo;
import pe.ccpip.autorizacion.PremiosModelos.LoteOpcion;
import pe.ccpip.autorizacion.PremiosModelos.NuevoPremio;
import pe.ccpip.autorizacion.PremiosModelos.Premio;
import pe.ccpip.autorizacion.PremiosModelos.PremioPublico;
import pe.ccpip.autorizacion.PremiosModelos.PremiosProducto;
import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.imagenes.ImagenStorage;
import pe.ccpip.productor.Auditoria;
import pe.ccpip.productor.Auditoria.Accion;

/**
 * CCPIP-43 (RF-29): el productor registra un premio vinculado a un producto propio y a uno de sus lotes,
 * con la entidad que lo otorgo, la fecha, la vigencia y el archivo que lo respalda (PDF, JPG o PNG). Todo
 * se filtra por el productor de la sesion (RNF-05) y el archivo va al bucket privado (RNF-08).
 *
 * <p>CCPIP-169 (RF-138): el visitante ve los premios de un producto visible del catalogo con su emisor,
 * fecha y vigencia. Nunca el archivo ni el codigo del lote. Todo premio publico tiene su archivo de
 * respaldo, y se presenta como declarado por el productor: no como aprobado por la Camara (acuerdo del
 * 26/10).
 */
@Service
public class PremiosService {

    public static final int MAX_PREMIOS = 10;
    private static final int MAX_TEXTO = 160;
    private static final String HOY = EvidenciasService.HOY;

    private final JdbcTemplate jdbc;
    private final ImagenStorage storage;
    private final Auditoria auditoria;

    public PremiosService(JdbcTemplate jdbc, ImagenStorage storage, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public PremiosProducto listar(long productorId, long productoId) {
        String nombre = productoPropio(productorId, productoId);
        List<Premio> premios = jdbc.query(SELECT + " WHERE pp.producto_id = ? AND pp.retirado_en IS NULL"
                + " ORDER BY pp.otorgado_en DESC, pp.id DESC", PremiosService::mapear, productoId);
        return new PremiosProducto(productoId, nombre, premios, lotes(productoId), premios.size() < MAX_PREMIOS, MAX_PREMIOS);
    }

    @Transactional
    public Premio registrar(long productorId, long usuarioId, long productoId, NuevoPremio datos, String nombreArchivo,
                            byte[] archivo) {
        productoPropio(productorId, productoId);
        validar(datos);
        if (lotes(productoId).stream().noneMatch(l -> l.id() == datos.loteId())) {
            throw ErrorNegocio.invalido("PREMIO_LOTE_AJENO", "Elige un lote de este producto.");
        }
        FormatoEvidencia formato = FormatoEvidencia.detectar(archivo);
        // Una fila por productor a la vez: el limite no se pasa con dos registros simultaneos
        jdbc.query("SELECT id FROM productor WHERE id = ? FOR UPDATE", (rs, row) -> rs.getLong(1), productorId);
        Integer registrados = jdbc.queryForObject(
                "SELECT count(*) FROM premio_producto WHERE producto_id = ? AND retirado_en IS NULL", Integer.class, productoId);
        if (registrados != null && registrados >= MAX_PREMIOS) {
            throw ErrorNegocio.invalido("LIMITE_DE_PREMIOS",
                    "Un producto admite hasta " + MAX_PREMIOS + " premios. Retira uno para registrar otro.");
        }

        String clave = "premios/productores/" + productorId + "/" + UUID.randomUUID();
        storage.guardar(clave, archivo, formato.contentType());
        Long id = jdbc.queryForObject("""
                INSERT INTO premio_producto (productor_id, producto_id, lote_id, titulo, emisor, otorgado_en, vigente_hasta,
                                             nombre_archivo, content_type, bytes, clave, registrado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                productorId, productoId, datos.loteId(), datos.titulo().strip(), datos.emisor().strip(), datos.otorgadoEn(),
                datos.vigenteHasta(), EvidenciasService.nombreSeguro(nombreArchivo), formato.contentType(), archivo.length,
                clave, usuarioId);
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put("premio", id);
        cambios.put("titulo", datos.titulo().strip());
        cambios.put("lote", datos.loteId());
        auditoria.registrar(productoId, usuarioId, Accion.PREMIO, cambios);
        return jdbc.query(SELECT + " WHERE pp.id = ?", PremiosService::mapear, id).get(0);
    }

    /** Retiro logico: deja de mostrarse en la ficha, pero se conserva. */
    @Transactional
    public void retirar(long productorId, long usuarioId, long productoId, long premioId) {
        productoPropio(productorId, productoId);
        int retirados = jdbc.update("""
                UPDATE premio_producto SET retirado_en = now(), retirado_por = ?
                WHERE id = ? AND producto_id = ? AND productor_id = ? AND retirado_en IS NULL""",
                usuarioId, premioId, productoId, productorId);
        if (retirados == 0) {
            throw noEncontrado();
        }
        auditoria.registrar(productoId, usuarioId, Accion.PREMIO, Map.of("retirado", premioId));
    }

    /** El archivo de un premio propio, aunque este retirado. */
    public Optional<Archivo> archivoPropio(long productorId, long premioId) {
        record Ref(String clave, String nombre, String contentType) {
        }
        return jdbc.query("SELECT clave, nombre_archivo, content_type FROM premio_producto WHERE id = ? AND productor_id = ?",
                        (rs, row) -> new Ref(rs.getString(1), rs.getString(2), rs.getString(3)), premioId, productorId)
                .stream().findFirst()
                .flatMap(ref -> storage.leer(ref.clave()).map(bytes -> new Archivo(ref.nombre(), ref.contentType(), bytes)));
    }

    /**
     * CCPIP-169: los premios sin retirar de un producto visible, primero los vigentes y luego los mas
     * recientes. Un producto que no esta en el catalogo responde 404, como su ficha.
     */
    @Transactional(readOnly = true)
    public List<PremioPublico> publicos(long productoId) {
        Integer visible = jdbc.queryForObject("SELECT count(*) " + CatalogoRepository.VISIBLE + " AND p.id = ?",
                Integer.class, productoId);
        if (visible == null || visible == 0) {
            throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o ya no está disponible");
        }
        return jdbc.query("""
                SELECT pp.titulo, pp.emisor, pp.otorgado_en, pp.vigente_hasta, %s AS vigente, l.fecha_produccion
                FROM premio_producto pp JOIN lote l ON l.id = pp.lote_id
                WHERE pp.producto_id = ? AND pp.retirado_en IS NULL
                ORDER BY vigente DESC, pp.otorgado_en DESC, pp.id DESC""".formatted(VIGENTE),
                (rs, row) -> new PremioPublico(rs.getString("titulo"), rs.getString("emisor"),
                        rs.getObject("otorgado_en", LocalDate.class), rs.getObject("vigente_hasta", LocalDate.class),
                        rs.getBoolean("vigente"), rs.getObject("fecha_produccion", LocalDate.class)),
                productoId);
    }

    // ---------- Partes ----------

    private static final String VIGENTE = "(pp.vigente_hasta IS NULL OR pp.vigente_hasta >= %s)".formatted(HOY);

    private static final String SELECT = """
            SELECT pp.id, pp.producto_id, pp.titulo, pp.emisor, pp.otorgado_en, pp.vigente_hasta, %s AS vigente,
                   pp.nombre_archivo, pp.content_type, pp.bytes, pp.registrado_en,
                   l.id AS lote_id, l.codigo AS lote_codigo, l.fecha_produccion, l.fecha_vencimiento
            FROM premio_producto pp JOIN lote l ON l.id = pp.lote_id""".formatted(VIGENTE);

    private static Premio mapear(ResultSet rs, int row) throws SQLException {
        long id = rs.getLong("id");
        LoteOpcion lote = new LoteOpcion(rs.getLong("lote_id"), rs.getString("lote_codigo"),
                rs.getObject("fecha_produccion", LocalDate.class), rs.getObject("fecha_vencimiento", LocalDate.class));
        return new Premio(id, rs.getLong("producto_id"), lote, rs.getString("titulo"), rs.getString("emisor"),
                rs.getObject("otorgado_en", LocalDate.class), rs.getObject("vigente_hasta", LocalDate.class),
                rs.getBoolean("vigente"), rs.getString("nombre_archivo"),
                FormatoEvidencia.deContentType(rs.getString("content_type")).name(), rs.getInt("bytes"),
                EvidenciasService.instante(rs, "registrado_en"), url(id));
    }

    static String url(long id) {
        return "/api/productor/premios/" + id + "/archivo";
    }

    /** Todos los lotes del producto, de donde esten: el premio puede ser de un lote ya vendido. */
    private List<LoteOpcion> lotes(long productoId) {
        return jdbc.query("""
                SELECT id, codigo, fecha_produccion, fecha_vencimiento FROM lote WHERE producto_id = ?
                ORDER BY fecha_produccion DESC NULLS LAST, registrado_en DESC, id DESC""",
                (rs, row) -> new LoteOpcion(rs.getLong("id"), rs.getString("codigo"),
                        rs.getObject("fecha_produccion", LocalDate.class), rs.getObject("fecha_vencimiento", LocalDate.class)),
                productoId);
    }

    private String productoPropio(long productorId, long productoId) {
        return jdbc.query("SELECT nombre FROM producto WHERE id = ? AND productor_id = ?",
                        (rs, row) -> rs.getString(1), productoId, productorId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe."));
    }

    private static void validar(NuevoPremio d) {
        if (d.titulo() == null || d.titulo().isBlank()) {
            throw ErrorNegocio.invalido("PREMIO_TITULO_REQUERIDO", "Escribe el nombre del premio.");
        }
        if (d.emisor() == null || d.emisor().isBlank()) {
            throw ErrorNegocio.invalido("PREMIO_EMISOR_REQUERIDO", "Indica la entidad que otorgó el premio.");
        }
        if (d.titulo().strip().length() > MAX_TEXTO || d.emisor().strip().length() > MAX_TEXTO) {
            throw ErrorNegocio.invalido("TEXTO_MUY_LARGO", "El nombre y el emisor admiten hasta " + MAX_TEXTO + " caracteres.");
        }
        if (d.otorgadoEn() == null) {
            throw ErrorNegocio.invalido("PREMIO_FECHA_REQUERIDA", "Indica cuándo se otorgó el premio.");
        }
        if (d.otorgadoEn().isAfter(EvidenciasService.hoyLima())) {
            throw ErrorNegocio.invalido("PREMIO_FECHA_FUTURA", "La fecha del premio no puede ser posterior a hoy.");
        }
        if (d.vigenteHasta() != null && d.vigenteHasta().isBefore(d.otorgadoEn())) {
            throw ErrorNegocio.invalido("VIGENCIA_INVALIDA", "La vigencia termina antes de que se otorgara el premio.");
        }
        if (d.loteId() == null) {
            throw ErrorNegocio.invalido("PREMIO_LOTE_REQUERIDO", "Elige el lote al que corresponde el premio.");
        }
    }

    private static ErrorNegocio noEncontrado() {
        return ErrorNegocio.noEncontrado("PREMIO_NO_ENCONTRADO", "El premio no existe.");
    }
}
