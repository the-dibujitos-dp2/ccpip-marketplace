package pe.ccpip.autorizacion;

import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.autorizacion.AutorizacionModelos.Asociacion;
import pe.ccpip.autorizacion.AutorizacionModelos.DecisionProductor;
import pe.ccpip.autorizacion.AutorizacionModelos.DecisionSobreProductor;
import pe.ccpip.autorizacion.AutorizacionModelos.Estado;
import pe.ccpip.autorizacion.AutorizacionModelos.FichaProductor;
import pe.ccpip.autorizacion.AutorizacionModelos.ProductorFila;
import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.productor.Auditoria;
import pe.ccpip.productor.Auditoria.Accion;

/**
 * Lo que el subadministrador decide sobre un productor completo (S-01 ampliada, vista por productor).
 *
 * <ul>
 *   <li>CCPIP-292 (RF-148): la asociacion entre el productor y cada producto que puede vender es su
 *       {@code autorizacion_producto}, individual y con evidencias. Aqui se ve producto por producto,
 *       con las lineas que cubrio la aprobacion: cambiar de linea no extiende el permiso.</li>
 *   <li>CCPIP-291 (RF-147): habilita la tienda de un productor autorizado. No hay postulacion (decision
 *       del 1 de octubre): el productor autorizado es el que la Camara incorporo, sigue activo y no
 *       tiene la venta revocada. Sin la habilitacion el productor no puede publicar su tienda, y cada
 *       producto sigue necesitando su propia autorizacion para venderse.</li>
 *   <li>CCPIP-330 (RF-220): revoca con motivo la venta de todo el productor. Sus autorizaciones por
 *       producto no se tocan (quedan citadas como alcance), asi que restituir devuelve exactamente lo
 *       que habia. Los pedidos confirmados no cambian.</li>
 *   <li>CCPIP-85 (RF-78) y CCPIP-322 (RF-215): bloquea o desbloquea con motivo las ventas del productor
 *       (Figma S-04, desde las denuncias). Pausa la venta como la revocacion, pero es otro estado.</li>
 * </ul>
 * Cada decision queda en productor_decision (solo insercion).
 */
@Service
public class ProductoresService {

    private static final String HOY = EvidenciasService.HOY;

    /** Autorizaciones APROBADAS y vigentes del productor pr. */
    private static final String AUTORIZADOS = """
            (SELECT count(*) FROM autorizacion_producto a WHERE a.productor_id = pr.id AND a.estado = 'APROBADA'
               AND (a.vigente_hasta IS NULL OR a.vigente_hasta >= %s))""".formatted(HOY);

    private final JdbcTemplate jdbc;
    private final Auditoria auditoria;

    public ProductoresService(JdbcTemplate jdbc, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public List<ProductorFila> listar() {
        return jdbc.query("""
                SELECT pr.id, coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion, pr.comunidad, pr.pueblo,
                       pr.region, pr.tienda_habilitada, pr.tienda_publicada, pr.venta_revocada, pr.venta_bloqueada,
                       (SELECT count(*) FROM producto p WHERE p.productor_id = pr.id) AS productos,
                       %s AS autorizados,
                       (SELECT count(*) FROM autorizacion_producto a WHERE a.productor_id = pr.id
                          AND a.estado = 'SOLICITADA') AS pendientes
                FROM productor pr WHERE pr.activo
                ORDER BY organizacion, pr.id""".formatted(AUTORIZADOS),
                (rs, row) -> new ProductorFila(rs.getLong("id"), rs.getString("organizacion"), rs.getString("comunidad"),
                        rs.getString("pueblo"), rs.getString("region"), rs.getBoolean("tienda_habilitada"),
                        rs.getBoolean("tienda_publicada"), rs.getBoolean("venta_revocada"),
                        rs.getBoolean("venta_bloqueada"), rs.getInt("productos"),
                        rs.getInt("autorizados"), rs.getInt("pendientes")));
    }

    @Transactional(readOnly = true)
    public FichaProductor ficha(long productorId) {
        List<Asociacion> productos = jdbc.query("""
                SELECT p.id, p.nombre, p.estado AS estado_producto,
                       (SELECT array_agg(l.nombre ORDER BY l.orden) FROM producto_linea pl
                        JOIN linea_producto l ON l.id = pl.linea_id WHERE pl.producto_id = p.id) AS lineas,
                       a.id AS autorizacion_id, a.estado, a.motivo, a.decidido_en, a.vigente_hasta,
                       a.vigente_hasta < %s AS vencida,
                       (SELECT array_agg(l.nombre ORDER BY l.orden) FROM linea_producto l
                        WHERE l.id = ANY (a.lineas)) AS lineas_autorizadas,
                       %s AS cubre_lineas,
                       %s AS vendible
                FROM producto p
                JOIN productor pr ON pr.id = p.productor_id
                LEFT JOIN autorizacion_producto a ON a.producto_id = p.id AND a.productor_id = pr.id
                WHERE pr.id = ?
                ORDER BY p.nombre, p.id""".formatted(HOY, EvidenciasService.CUBRE_LINEAS, CatalogoRepository.HABILITADA),
                (rs, row) -> new Asociacion(rs.getLong("id"), rs.getString("nombre"), rs.getString("estado_producto"),
                        textos(rs.getArray("lineas")), (Long) rs.getObject("autorizacion_id"),
                        rs.getString("estado") == null ? Estado.SIN_SOLICITUD : Estado.valueOf(rs.getString("estado")),
                        rs.getString("motivo"), EvidenciasService.instante(rs, "decidido_en"),
                        rs.getObject("vigente_hasta", LocalDate.class), rs.getBoolean("vencida"),
                        textos(rs.getArray("lineas_autorizadas")), rs.getBoolean("cubre_lineas"), rs.getBoolean("vendible")),
                productorId);
        List<DecisionSobreProductor> decisiones = jdbc.query("""
                SELECT d.id, d.decision, d.motivo, u.nombre, d.decidido_en, cardinality(d.autorizaciones) AS alcance
                FROM productor_decision d JOIN usuario u ON u.id = d.decidido_por
                WHERE d.productor_id = ? ORDER BY d.decidido_en DESC, d.id DESC""",
                (rs, row) -> new DecisionSobreProductor(rs.getLong("id"), DecisionProductor.valueOf(rs.getString("decision")),
                        rs.getString("motivo"), rs.getString("nombre"), EvidenciasService.instante(rs, "decidido_en"),
                        rs.getInt("alcance")),
                productorId);
        return jdbc.query("""
                SELECT pr.id, coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion, pr.comunidad, pr.pueblo,
                       pr.region, pr.tienda_habilitada, pr.tienda_publicada, pr.venta_revocada, pr.venta_bloqueada,
                       EXISTS (SELECT 1 FROM cuenta_bancaria cb WHERE cb.productor_id = pr.id AND cb.estado = 'ACTIVA') AS cuenta
                FROM productor pr WHERE pr.id = ? AND pr.activo""",
                (rs, row) -> {
                    boolean habilitada = rs.getBoolean("tienda_habilitada");
                    boolean revocada = rs.getBoolean("venta_revocada");
                    boolean bloqueada = rs.getBoolean("venta_bloqueada");
                    return new FichaProductor(rs.getLong("id"), rs.getString("organizacion"), rs.getString("comunidad"),
                            rs.getString("pueblo"), rs.getString("region"), habilitada, rs.getBoolean("tienda_publicada"),
                            revocada, bloqueada, rs.getBoolean("cuenta"), productos, decisiones,
                            !habilitada && !revocada && !bloqueada);
                }, productorId)
                .stream().findFirst().orElseThrow(ProductoresService::noEncontrado);
    }

    /**
     * Una decision sobre el productor, con motivo:
     * <ul>
     *   <li>HABILITAR_TIENDA (CCPIP-291): solo a un productor incorporado y con la venta no revocada ni bloqueada.</li>
     *   <li>REVOCAR_VENTA (CCPIP-330): deja de venderse todo lo del productor, sin tocar sus
     *       autorizaciones ni sus pedidos.</li>
     *   <li>RESTITUIR_VENTA: revocar no es irreversible; una nueva decision humana la restituye.</li>
     *   <li>BLOQUEAR_VENTA (CCPIP-85) y DESBLOQUEAR_VENTA (CCPIP-322): la misma pausa, pero como medida
     *       de gobernanza (por ejemplo, tras denuncias). Tiene su propio estado: desbloquear no restituye
     *       una venta revocada.</li>
     * </ul>
     */
    @Transactional
    public FichaProductor decidir(long productorId, long usuarioId, DecisionProductor decision, String motivo) {
        String texto = RevisionService.motivoRequerido(motivo);
        EstadoProductor estado = fijar(productorId);
        String conflicto = switch (decision) {
            case HABILITAR_TIENDA -> estado.tiendaHabilitada() ? "TIENDA_YA_HABILITADA"
                    : estado.ventaRevocada() ? "VENTA_REVOCADA"
                    : estado.ventaBloqueada() ? "VENTA_BLOQUEADA" : null;
            case REVOCAR_VENTA -> estado.ventaRevocada() ? "VENTA_YA_REVOCADA" : null;
            case RESTITUIR_VENTA -> estado.ventaRevocada() ? null : "VENTA_NO_REVOCADA";
            case BLOQUEAR_VENTA -> estado.ventaBloqueada() ? "VENTA_YA_BLOQUEADA" : null;
            case DESBLOQUEAR_VENTA -> estado.ventaBloqueada() ? null : "VENTA_NO_BLOQUEADA";
        };
        if (conflicto != null) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, conflicto, CONFLICTOS.get(conflicto));
        }
        Long[] alcance = aprobadas(productorId);
        long decisionId = registrar(productorId, usuarioId, decision, texto, alcance);
        if (decision == DecisionProductor.HABILITAR_TIENDA) {
            jdbc.update("UPDATE productor SET tienda_habilitada = true, tienda_decision_id = ?, actualizado_en = now() WHERE id = ?",
                    decisionId, productorId);
        } else {
            cambiarVenta(productorId, usuarioId, decision, texto, decisionId, alcance);
        }
        return ficha(productorId);
    }

    private static final Map<String, String> CONFLICTOS = Map.of(
            "TIENDA_YA_HABILITADA", "La tienda de este productor ya está habilitada.",
            "VENTA_REVOCADA", "La venta de este productor está revocada: restitúyela antes de habilitar su tienda.",
            "VENTA_BLOQUEADA", "Las ventas de este productor están bloqueadas: levanta el bloqueo antes de habilitar su tienda.",
            "VENTA_YA_REVOCADA", "La venta de este productor ya está revocada.",
            "VENTA_NO_REVOCADA", "La venta de este productor no está revocada.",
            "VENTA_YA_BLOQUEADA", "La venta de este productor ya está bloqueada.",
            "VENTA_NO_BLOQUEADA", "La venta de este productor no está bloqueada.");

    private void cambiarVenta(long productorId, long usuarioId, DecisionProductor decision, String motivo, long decisionId,
                              Long[] alcance) {
        boolean bloqueo = decision == DecisionProductor.BLOQUEAR_VENTA || decision == DecisionProductor.DESBLOQUEAR_VENTA;
        if (bloqueo) {
            jdbc.update("UPDATE productor SET venta_bloqueada = ?, bloqueo_decision_id = ?, actualizado_en = now() WHERE id = ?",
                    decision == DecisionProductor.BLOQUEAR_VENTA, decisionId, productorId);
        } else {
            jdbc.update("UPDATE productor SET venta_revocada = ?, venta_decision_id = ?, actualizado_en = now() WHERE id = ?",
                    decision == DecisionProductor.REVOCAR_VENTA, decisionId, productorId);
        }
        // El historial de cada producto alcanzado tambien lo registra (CCPIP-47)
        List<Long> productos = jdbc.queryForList("SELECT producto_id FROM autorizacion_producto WHERE id = ANY (?) ORDER BY producto_id",
                Long.class, (Object) alcance);
        for (Long producto : productos) {
            Map<String, Object> cambios = new LinkedHashMap<>();
            cambios.put("decision", decision.name());
            cambios.put("motivo", motivo);
            cambios.put("decisionProductorId", decisionId);
            auditoria.registrar(producto, usuarioId, Accion.AUTORIZACION, cambios);
        }
    }

    private long registrar(long productorId, long usuarioId, DecisionProductor decision, String motivo, Long[] alcance) {
        Long id = jdbc.queryForObject("""
                INSERT INTO productor_decision (productor_id, decision, motivo, autorizaciones, decidido_por)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, productorId, decision.name(), motivo, alcance, usuarioId);
        return id == null ? 0 : id;
    }

    private Long[] aprobadas(long productorId) {
        return jdbc.queryForList("""
                SELECT a.id FROM autorizacion_producto a WHERE a.productor_id = ? AND a.estado = 'APROBADA'
                  AND (a.vigente_hasta IS NULL OR a.vigente_hasta >= %s) ORDER BY a.id""".formatted(HOY),
                Long.class, productorId).toArray(Long[]::new);
    }

    private record EstadoProductor(boolean tiendaHabilitada, boolean ventaRevocada, boolean ventaBloqueada) {
    }

    /** Dos subadministradores no deciden sobre el mismo productor a la vez. */
    private EstadoProductor fijar(long productorId) {
        return jdbc.query("SELECT tienda_habilitada, venta_revocada, venta_bloqueada FROM productor WHERE id = ? AND activo FOR UPDATE",
                        (rs, row) -> new EstadoProductor(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)), productorId)
                .stream().findFirst().orElseThrow(ProductoresService::noEncontrado);
    }

    private static List<String> textos(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    private static ErrorNegocio noEncontrado() {
        return ErrorNegocio.noEncontrado("PRODUCTOR_NO_ENCONTRADO", "El productor no existe.");
    }
}
