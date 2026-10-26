package pe.ccpip.moderacion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.autorizacion.AutorizacionModelos.Evidencia;
import pe.ccpip.autorizacion.AutorizacionModelos.Productor;
import pe.ccpip.autorizacion.EvidenciasService;
import pe.ccpip.autorizacion.RevisionService;
import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.moderacion.ModeracionModelos.AutorizacionVenta;
import pe.ccpip.moderacion.ModeracionModelos.Bandeja;
import pe.ccpip.moderacion.ModeracionModelos.Contadores;
import pe.ccpip.moderacion.ModeracionModelos.Estado;
import pe.ccpip.moderacion.ModeracionModelos.Expediente;
import pe.ccpip.moderacion.ModeracionModelos.Fila;
import pe.ccpip.moderacion.ModeracionModelos.Oferta;
import pe.ccpip.moderacion.ModeracionModelos.Solicitud;
import pe.ccpip.productor.ProductoModelos.ProductoEdicion;
import pe.ccpip.productor.ProductosService;

/**
 * Moderacion de la publicacion de ofertas por el subadministrador. Publicar deja la oferta EN_MODERACION
 * (ProductosService); aqui se revisa.
 * <ul>
 *   <li>CCPIP-46 (RF-21): consultar las ofertas pendientes y el expediente de cada una: la oferta como se
 *       publicaria, el productor, su autorizacion de venta con las evidencias vigentes y el historial de
 *       solicitudes.</li>
 * </ul>
 * La revision no sustituye la evaluacion documental de la autorizacion (CCPIP-174): solo la muestra.
 */
@Service
public class ModeracionService {

    /** La solicitud m ya era una republicacion: la oferta salio antes del catalogo o ya se aprobo una vez. */
    private static final String REPUBLICACION = """
            (m.estado_anterior = 'RETIRADO' OR EXISTS (
                SELECT 1 FROM moderacion_publicacion x
                WHERE x.producto_id = m.producto_id AND x.decision = 'APROBADA' AND x.solicitada_en < m.solicitada_en))""";

    private final JdbcTemplate jdbc;
    private final ProductosService productos;
    private final EvidenciasService evidencias;

    public ModeracionService(JdbcTemplate jdbc, ProductosService productos, EvidenciasService evidencias) {
        this.jdbc = jdbc;
        this.productos = productos;
        this.evidencias = evidencias;
    }

    /** CCPIP-46: pendientes primero las mas antiguas; las decididas, de la mas reciente a la mas antigua. */
    @Transactional(readOnly = true)
    public Bandeja bandeja(Estado estado) {
        String filtro = estado == Estado.PENDIENTE
                ? "m.decision IS NULL ORDER BY m.solicitada_en, m.id"
                : "m.decision = '" + estado.name() + "' ORDER BY m.decidida_en DESC, m.id DESC";
        List<Fila> filas = jdbc.query("""
                SELECT m.id, coalesce(m.decision, 'PENDIENTE') AS estado, m.solicitada_en, m.decidida_en,
                       p.id AS producto_id, p.nombre AS producto, l.nombre AS linea, pr.id AS productor_id,
                       coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion, pr.pueblo, pr.region,
                       %s AS republicacion,
                       coalesce((SELECT a.estado FROM autorizacion_producto a
                                 WHERE a.producto_id = p.id AND a.productor_id = pr.id), 'SIN_SOLICITUD') AS autorizacion,
                       %s AS autorizada
                FROM moderacion_publicacion m
                JOIN producto p ON p.id = m.producto_id
                JOIN productor pr ON pr.id = m.productor_id
                LEFT JOIN linea_producto l ON l.id = p.linea_id
                WHERE\s""".formatted(REPUBLICACION, CatalogoRepository.AUTORIZADA) + filtro,
                (rs, row) -> new Fila(rs.getLong("id"), Estado.valueOf(rs.getString("estado")), rs.getLong("producto_id"),
                        rs.getString("producto"), rs.getString("linea"), rs.getLong("productor_id"),
                        rs.getString("organizacion"), rs.getString("pueblo"), rs.getString("region"),
                        rs.getBoolean("republicacion"), rs.getString("autorizacion"), rs.getBoolean("autorizada"),
                        instante(rs, "solicitada_en"), instante(rs, "decidida_en")));
        return new Bandeja(contadores(), filas);
    }

    /** CCPIP-46: la oferta como se publicaria, su autorizacion de venta y el historial de sus solicitudes. */
    @Transactional(readOnly = true)
    public Expediente expediente(long id) {
        Cabecera c = cabecera(id, false);
        ProductoEdicion p = productos.obtener(c.productor().id(), c.productoId());
        AutorizacionVenta autorizacion = autorizacion(c);
        List<String> faltan = c.estado() == Estado.PENDIENTE ? faltan(p, autorizacion) : List.of();
        boolean pendiente = c.estado() == Estado.PENDIENTE;
        return new Expediente(c.id(), c.estado(), c.solicitadaEn(), c.solicitadaPor(), c.republicacion(), oferta(p),
                c.productor(), autorizacion, historial(c.productoId()), faltan, pendiente, pendiente && faltan.isEmpty());
    }

    /**
     * Por que no se podria aprobar ahora: la oferta debe estar autorizada para la venta (CCPIP-331) y seguir
     * cumpliendo lo que se exige al publicar (datos completos, stock en un punto de acopio, tienda publicada).
     */
    private static List<String> faltan(ProductoEdicion p, AutorizacionVenta autorizacion) {
        List<String> faltan = new ArrayList<>();
        if (!autorizacion.vigente()) {
            faltan.add("Una autorización de venta vigente para este producto");
        }
        faltan.addAll(p.publicable().faltan());
        return faltan;
    }

    private Oferta oferta(ProductoEdicion p) {
        List<String> lineas = jdbc.queryForList("""
                SELECT l.nombre FROM producto_linea pl JOIN linea_producto l ON l.id = pl.linea_id
                JOIN producto p ON p.id = pl.producto_id
                WHERE pl.producto_id = ? ORDER BY (l.id = p.linea_id) DESC, l.orden""", String.class, p.id());
        String unidad = p.unidad() == null ? null : jdbc.query("SELECT nombre FROM unidad_medida WHERE codigo = ?",
                (rs, row) -> rs.getString(1), p.unidad()).stream().findFirst().orElse(p.unidad());
        boolean conHistoria = p.historia() != null && p.historia().aprobada();
        return new Oferta(p.id(), p.nombre(), p.descripcion(), lineas, unidad, p.contenido(), p.modalidad().name(),
                p.precio(), p.precioMayorista(), p.cantidadMinimaMayorista(), p.enVenta(),
                conHistoria ? p.historia().titulo() : null, conHistoria ? p.historia().textoFinal() : null, p.imagenes());
    }

    /** La autorizacion de venta del producto con sus evidencias vigentes, que se abren por la ruta de S-02. */
    private AutorizacionVenta autorizacion(Cabecera c) {
        boolean vigente = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT %s FROM producto p JOIN productor pr ON pr.id = p.productor_id WHERE p.id = ?"""
                .formatted(CatalogoRepository.AUTORIZADA), Boolean.class, c.productoId()));
        record Registro(long id, String estado, LocalDate vigenteHasta) {
        }
        Optional<Registro> a = jdbc.query("""
                        SELECT id, estado, vigente_hasta FROM autorizacion_producto WHERE producto_id = ? AND productor_id = ?""",
                (rs, row) -> new Registro(rs.getLong("id"), rs.getString("estado"), rs.getObject("vigente_hasta", LocalDate.class)),
                c.productoId(), c.productor().id()).stream().findFirst();
        if (a.isEmpty()) {
            return new AutorizacionVenta(null, "SIN_SOLICITUD", null, false, List.of());
        }
        long autorizacionId = a.get().id();
        List<Evidencia> vigentes = evidencias.vigentes(autorizacionId, ev -> RevisionService.urlAdmin(autorizacionId, ev));
        return new AutorizacionVenta(autorizacionId, a.get().estado(), a.get().vigenteHasta(), vigente, vigentes);
    }

    private List<Solicitud> historial(long productoId) {
        return jdbc.query("""
                        SELECT m.id, coalesce(m.decision, 'PENDIENTE') AS estado, m.solicitada_en, s.nombre AS solicitada_por,
                               m.motivo, d.nombre AS decidida_por, m.decidida_en
                        FROM moderacion_publicacion m
                        JOIN usuario s ON s.id = m.solicitada_por
                        LEFT JOIN usuario d ON d.id = m.decidida_por
                        WHERE m.producto_id = ? ORDER BY m.solicitada_en DESC, m.id DESC""",
                (rs, row) -> new Solicitud(rs.getLong("id"), Estado.valueOf(rs.getString("estado")),
                        instante(rs, "solicitada_en"), rs.getString("solicitada_por"), rs.getString("motivo"),
                        rs.getString("decidida_por"), instante(rs, "decidida_en")), productoId);
    }

    private Contadores contadores() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM moderacion_publicacion WHERE decision IS NULL) AS pendientes,
                       count(*) FILTER (WHERE decision = 'APROBADA') AS aprobadas,
                       count(*) FILTER (WHERE decision = 'RECHAZADA') AS rechazadas
                FROM moderacion_publicacion
                WHERE decision IS NOT NULL
                  AND to_char(decidida_en AT TIME ZONE 'America/Lima', 'YYYY-MM') = to_char(now() AT TIME ZONE 'America/Lima', 'YYYY-MM')""",
                (rs, row) -> new Contadores(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
    }

    record Cabecera(long id, Estado estado, String estadoAnterior, Instant solicitadaEn, String solicitadaPor,
                    boolean republicacion, long productoId, String producto, Productor productor) {
    }

    /** Con {@code bloquear}, dos subadministradores no deciden la misma solicitud a la vez. */
    Cabecera cabecera(long id, boolean bloquear) {
        return jdbc.query("""
                        SELECT m.id, coalesce(m.decision, 'PENDIENTE') AS estado, m.estado_anterior, m.solicitada_en,
                               s.nombre AS solicitada_por, %s AS republicacion, p.id AS producto_id, p.nombre AS producto,
                               pr.id AS productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion,
                               pr.comunidad, pr.pueblo, pr.region
                        FROM moderacion_publicacion m
                        JOIN usuario s ON s.id = m.solicitada_por
                        JOIN producto p ON p.id = m.producto_id
                        JOIN productor pr ON pr.id = m.productor_id
                        WHERE m.id = ?""".formatted(REPUBLICACION) + (bloquear ? " FOR UPDATE OF m" : ""),
                        (rs, row) -> new Cabecera(rs.getLong("id"), Estado.valueOf(rs.getString("estado")),
                                rs.getString("estado_anterior"), instante(rs, "solicitada_en"), rs.getString("solicitada_por"),
                                rs.getBoolean("republicacion"), rs.getLong("producto_id"), rs.getString("producto"),
                                new Productor(rs.getLong("productor_id"), rs.getString("organizacion"), rs.getString("comunidad"),
                                        rs.getString("pueblo"), rs.getString("region"))), id)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("SOLICITUD_NO_ENCONTRADA", "La solicitud de publicación no existe."));
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
