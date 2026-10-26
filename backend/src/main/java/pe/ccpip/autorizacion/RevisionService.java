package pe.ccpip.autorizacion;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.autorizacion.AutorizacionModelos.Analisis;
import pe.ccpip.autorizacion.AutorizacionModelos.Bandeja;
import pe.ccpip.autorizacion.AutorizacionModelos.Contadores;
import pe.ccpip.autorizacion.AutorizacionModelos.Decision;
import pe.ccpip.autorizacion.AutorizacionModelos.Estado;
import pe.ccpip.autorizacion.AutorizacionModelos.Evidencia;
import pe.ccpip.autorizacion.AutorizacionModelos.Expediente;
import pe.ccpip.autorizacion.AutorizacionModelos.Fila;
import pe.ccpip.autorizacion.AutorizacionModelos.Hallazgo;
import pe.ccpip.autorizacion.AutorizacionModelos.Productor;
import pe.ccpip.autorizacion.AutorizacionModelos.TipoEvidencia;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.ia.AnalistaEvidencias;
import pe.ccpip.ia.IaNoDisponible;
import pe.ccpip.productor.Auditoria;
import pe.ccpip.productor.Auditoria.Accion;

/**
 * Revision de solicitudes de autorizacion por el subadministrador (S-01 bandeja, S-02 expediente).
 *
 * <ul>
 *   <li>CCPIP-50 (RF-74): pide un analisis preliminar asistido. Es una sugerencia que no cambia el
 *       estado; si el servicio no responde queda registrado y la solicitud se revisa a mano.</li>
 *   <li>CCPIP-174 (RF-166) y CCPIP-284 (RF-204): aprueba o rechaza con motivo. Esa decision humana es
 *       la unica que autoriza. Aprobar deja autorizacion_producto APROBADA, con vigente_hasta igual al
 *       certificado que vence primero, y habilita la compra por la regla de CCPIP-269/270.</li>
 *   <li>CCPIP-39 (RF-16): revoca con motivo una autorizacion APROBADA, aunque su certificado haya
 *       vencido: vencer no es revocar (C5). Desde ese momento la oferta queda en Venta pausada; los
 *       pedidos confirmados no cambian y el productor puede volver a solicitarla con evidencias nuevas.</li>
 * </ul>
 * Cada decision queda en autorizacion_decision (solo insercion) y en el historial del producto.
 */
@Service
public class RevisionService {

    private static final Logger log = LoggerFactory.getLogger(RevisionService.class);
    private static final String HOY = EvidenciasService.HOY;

    private final JdbcTemplate jdbc;
    private final EvidenciasService evidencias;
    private final AnalistaEvidencias analista;
    private final Auditoria auditoria;

    public RevisionService(JdbcTemplate jdbc, EvidenciasService evidencias, AnalistaEvidencias analista, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.evidencias = evidencias;
        this.analista = analista;
        this.auditoria = auditoria;
    }

    /**
     * S-01. Pendientes primero las mas antiguas; las resueltas son solo las decididas en la bandeja (lo
     * aprobado por migracion o semilla no tiene decision registrada).
     */
    @Transactional(readOnly = true)
    public Bandeja bandeja(Estado estado) {
        String filtro = switch (estado) {
            case SOLICITADA -> "a.estado = 'SOLICITADA' ORDER BY a.solicitada_en NULLS LAST, a.id";
            case APROBADA, RECHAZADA, REVOCADA -> "a.estado = '" + estado.name() + "' AND a.decision_id IS NOT NULL ORDER BY a.decidido_en DESC, a.id";
            default -> throw ErrorNegocio.invalido("ESTADO_INVALIDO", "Elige pendientes, aprobadas, rechazadas o revocadas.");
        };
        List<Fila> filas = jdbc.query("""
                SELECT a.id, a.estado, a.solicitada_en, a.decidido_en, p.id AS producto_id, p.nombre AS producto,
                       l.nombre AS linea, pr.id AS productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion,
                       pr.pueblo, pr.region,
                       (SELECT count(*) FROM evidencia_producto e WHERE e.autorizacion_id = a.id AND e.retirada_en IS NULL) AS evidencias,
                       (SELECT count(*) FROM evidencia_producto e WHERE e.autorizacion_id = a.id AND e.retirada_en IS NULL
                          AND e.tipo = 'CERTIFICADO' AND e.vigente_hasta >= %1$s
                          AND (e.vigente_desde IS NULL OR e.vigente_desde <= %1$s)) AS certificados,
                       (SELECT x.recomendacion FROM analisis_evidencias x WHERE x.autorizacion_id = a.id
                        ORDER BY x.solicitado_en DESC, x.id DESC LIMIT 1) AS recomendacion
                FROM autorizacion_producto a
                JOIN producto p ON p.id = a.producto_id
                JOIN productor pr ON pr.id = a.productor_id
                JOIN linea_producto l ON l.id = p.linea_id
                WHERE\s""".formatted(HOY) + filtro,
                (rs, row) -> new Fila(rs.getLong("id"), Estado.valueOf(rs.getString("estado")), rs.getLong("producto_id"),
                        rs.getString("producto"), rs.getString("linea"), rs.getLong("productor_id"),
                        rs.getString("organizacion"), rs.getString("pueblo"), rs.getString("region"),
                        rs.getInt("evidencias"), rs.getInt("certificados"), rs.getString("recomendacion"),
                        EvidenciasService.instante(rs, "solicitada_en"), EvidenciasService.instante(rs, "decidido_en")));
        return new Bandeja(contadores(), filas);
    }

    @Transactional(readOnly = true)
    public Expediente expediente(long id) {
        Cabecera c = cabecera(id, false);
        List<Evidencia> vigentes = evidencias.vigentes(id, ev -> urlAdmin(id, ev));
        Analisis analisis = ultimoAnalisis(id, vigentes);
        List<Decision> decisiones = jdbc.query("""
                        SELECT d.id, d.decision, d.motivo, u.nombre, d.decidido_en, d.analisis_id, d.vigente_hasta
                        FROM autorizacion_decision d JOIN usuario u ON u.id = d.decidido_por
                        WHERE d.autorizacion_id = ? ORDER BY d.decidido_en DESC, d.id DESC""",
                (rs, row) -> new Decision(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        EvidenciasService.instante(rs, "decidido_en"), rs.getObject(6) != null,
                        rs.getObject(7, LocalDate.class)), id);
        return new Expediente(id, c.estado(), c.motivo(), c.solicitadaEn(), c.decididaEn(), c.vigenteHasta(),
                c.productoId(), c.producto(), c.linea(), c.productor(), vigentes,
                (int) certificadosVigentes(vigentes).count(), analisis, decisiones, c.estado() == Estado.SOLICITADA,
                c.estado() == Estado.APROBADA);
    }

    /**
     * CCPIP-50: analisis preliminar de las evidencias vigentes. Si el proveedor no esta disponible se
     * registra el intento como NO_DISPONIBLE (por eso no se revierte) y responde 503: la solicitud
     * sigue pendiente y se decide a mano.
     */
    @Transactional(noRollbackFor = IaNoDisponible.class)
    public Analisis analizar(long id, long usuarioId) {
        Cabecera c = cabecera(id, true);
        exigirPendiente(c);
        List<Evidencia> vigentes = evidencias.vigentes(id, ev -> urlAdmin(id, ev));
        UUID[] analizadas = vigentes.stream().map(Evidencia::id).toArray(UUID[]::new);
        AnalistaEvidencias.Analisis resultado;
        try {
            resultado = analista.analizar(new AnalistaEvidencias.Solicitud(c.producto(), c.productor().organizacion(),
                    EvidenciasService.hoyLima(), vigentes.stream().map(RevisionService::paraAnalista).toList()));
        } catch (RuntimeException e) {
            if (!(e instanceof IaNoDisponible)) {
                log.warn("El análisis de evidencias de la solicitud {} falló: {}", id, e.getMessage());
            }
            jdbc.update("""
                    INSERT INTO analisis_evidencias (autorizacion_id, estado, proveedor, simulado, evidencias, solicitado_por)
                    VALUES (?, 'NO_DISPONIBLE', ?, ?, ?, ?)""",
                    id, analista.proveedor(), analista.simulado(), analizadas, usuarioId);
            throw IaNoDisponible.paraRevision();
        }
        Long analisisId = jdbc.queryForObject("""
                INSERT INTO analisis_evidencias (autorizacion_id, estado, proveedor, simulado, recomendacion, resumen,
                                                 evidencias, solicitado_por)
                VALUES (?, 'COMPLETADO', ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                id, analista.proveedor(), analista.simulado(), resultado.recomendacion().name(), resultado.resumen(),
                analizadas, usuarioId);
        Set<UUID> propias = new HashSet<>(Arrays.asList(analizadas));
        for (AnalistaEvidencias.Hallazgo h : resultado.hallazgos()) {
            // Un proveedor no puede atribuir un hallazgo a una evidencia de otra solicitud
            UUID evidencia = h.evidenciaId() != null && propias.contains(h.evidenciaId()) ? h.evidenciaId() : null;
            jdbc.update("INSERT INTO analisis_hallazgo (analisis_id, evidencia_id, tipo, detalle) VALUES (?, ?, ?, ?)",
                    analisisId, evidencia, h.tipo().name(), recortar(h.detalle(), 500));
        }
        return ultimoAnalisis(id, vigentes);
    }

    /**
     * CCPIP-174 (RF-166): aprobar exige al menos un certificado vigente DEL producto solicitado: las
     * evidencias de otro producto cuelgan de otra solicitud y no cuentan. No exige analisis previo.
     */
    @Transactional
    public Expediente aprobar(long id, long usuarioId, String motivo) {
        Cabecera c = cabecera(id, true);
        exigirPendiente(c);
        List<Evidencia> vigentes = evidencias.vigentes(id, ev -> null);
        LocalDate vigenteHasta = certificadosVigentes(vigentes).map(Evidencia::vigenteHasta)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT, "SIN_CERTIFICADO_VIGENTE",
                        "Para aprobar, el producto necesita al menos un certificado vigente propio."));
        decidir(c, usuarioId, "APROBADA", motivo, vigentes, vigenteHasta, true);
        return expediente(id);
    }

    /** CCPIP-284 (RF-204): rechazar con motivo; el productor lo ve y puede adjuntar nuevas evidencias. */
    @Transactional
    public Expediente rechazar(long id, long usuarioId, String motivo) {
        Cabecera c = cabecera(id, true);
        exigirPendiente(c);
        decidir(c, usuarioId, "RECHAZADA", motivo, evidencias.vigentes(id, ev -> null), null, true);
        return expediente(id);
    }

    /**
     * CCPIP-39 (RF-16): solo se revoca lo que esta APROBADO. La decision cita las evidencias que
     * sustentaban la aprobacion; no se borra nada.
     */
    @Transactional
    public Expediente revocar(long id, long usuarioId, String motivo) {
        Cabecera c = cabecera(id, true);
        if (c.estado() != Estado.APROBADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "AUTORIZACION_NO_APROBADA",
                    "Solo se puede revocar una autorización aprobada.");
        }
        decidir(c, usuarioId, "REVOCADA", motivo, evidencias.vigentes(id, ev -> null), null, false);
        return expediente(id);
    }

    /** Archivo de una evidencia de la solicitud, aunque este retirada: las decisiones la citan. */
    public Optional<EvidenciasService.Archivo> archivo(long id, UUID evidenciaId) {
        return evidencias.archivo("id = ? AND autorizacion_id = ?", evidenciaId, id);
    }

    private void decidir(Cabecera c, long usuarioId, String decision, String motivo, List<Evidencia> vigentes,
                         LocalDate vigenteHasta, boolean citaAnalisis) {
        String texto = motivoRequerido(motivo);
        Long analisisId = !citaAnalisis ? null : jdbc.query("""
                        SELECT id FROM analisis_evidencias WHERE autorizacion_id = ? AND estado = 'COMPLETADO'
                        ORDER BY solicitado_en DESC, id DESC LIMIT 1""", (rs, row) -> rs.getLong(1), c.id())
                .stream().findFirst().orElse(null);
        UUID[] revisadas = vigentes.stream().map(Evidencia::id).toArray(UUID[]::new);
        Long decisionId = jdbc.queryForObject("""
                INSERT INTO autorizacion_decision (autorizacion_id, decision, motivo, evidencias, vigente_hasta,
                                                   analisis_id, decidido_por)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                c.id(), decision, texto, revisadas, vigenteHasta, analisisId, usuarioId);
        jdbc.update("""
                UPDATE autorizacion_producto SET estado = ?, motivo = ?, decidido_por = ?, decidido_en = now(),
                       vigente_hasta = ?, decision_id = ?
                WHERE id = ?""", decision, recortar(texto, 255), usuarioId, vigenteHasta, decisionId, c.id());
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put("decision", decision);
        cambios.put("motivo", texto);
        cambios.put("decisionId", decisionId);
        cambios.put("conAnalisis", analisisId != null);
        auditoria.registrar(c.productoId(), usuarioId, Accion.AUTORIZACION, cambios);
    }

    /** Toda decision humana lleva motivo (CCPIP-174, CCPIP-284, CCPIP-39, CCPIP-291, CCPIP-330). */
    public static String motivoRequerido(String motivo) {
        String texto = motivo == null ? "" : motivo.strip();
        if (texto.isEmpty()) {
            throw ErrorNegocio.invalido("MOTIVO_REQUERIDO", "Escribe el motivo de la decisión.");
        }
        return texto;
    }

    private Contadores contadores() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM autorizacion_producto WHERE estado = 'SOLICITADA') AS pendientes,
                       count(*) FILTER (WHERE decision = 'APROBADA') AS aprobadas,
                       count(*) FILTER (WHERE decision = 'RECHAZADA') AS rechazadas,
                       count(*) FILTER (WHERE decision = 'REVOCADA') AS revocadas
                FROM autorizacion_decision
                WHERE to_char(decidido_en AT TIME ZONE 'America/Lima', 'YYYY-MM') = to_char(now() AT TIME ZONE 'America/Lima', 'YYYY-MM')""",
                (rs, row) -> new Contadores(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
    }

    private Analisis ultimoAnalisis(long id, List<Evidencia> vigentes) {
        Set<UUID> actuales = vigentes.stream().map(Evidencia::id).collect(Collectors.toSet());
        return jdbc.query("""
                        SELECT id, estado, proveedor, simulado, recomendacion, resumen, evidencias, solicitado_en
                        FROM analisis_evidencias WHERE autorizacion_id = ? ORDER BY solicitado_en DESC, id DESC LIMIT 1""",
                (rs, row) -> {
                    long analisisId = rs.getLong("id");
                    List<Hallazgo> hallazgos = jdbc.query(
                            "SELECT evidencia_id, tipo, detalle FROM analisis_hallazgo WHERE analisis_id = ? ORDER BY id",
                            (h, n) -> new Hallazgo(h.getObject(1, UUID.class), h.getString(2), h.getString(3)), analisisId);
                    return new Analisis(analisisId, rs.getString("estado"), rs.getString("proveedor"),
                            rs.getBoolean("simulado"), rs.getString("recomendacion"), rs.getString("resumen"), hallazgos,
                            EvidenciasService.instante(rs, "solicitado_en"), !uuids(rs.getArray("evidencias")).equals(actuales));
                }, id).stream().findFirst().orElse(null);
    }

    private static Set<UUID> uuids(Array array) throws SQLException {
        Object[] valores = (Object[]) array.getArray();
        return Arrays.stream(valores).map(v -> v instanceof UUID u ? u : UUID.fromString(v.toString()))
                .collect(Collectors.toSet());
    }

    private static java.util.stream.Stream<Evidencia> certificadosVigentes(List<Evidencia> vigentes) {
        LocalDate hoy = EvidenciasService.hoyLima();
        return vigentes.stream().filter(e -> e.tipo() == TipoEvidencia.CERTIFICADO && !e.vencida()
                && (e.vigenteDesde() == null || !e.vigenteDesde().isAfter(hoy)) && Objects.nonNull(e.vigenteHasta()));
    }

    private static AnalistaEvidencias.Evidencia paraAnalista(Evidencia e) {
        return new AnalistaEvidencias.Evidencia(e.id(), e.tipo().name(), e.titulo(), e.emisor(), e.vigenteDesde(),
                e.vigenteHasta(), e.formato());
    }

    private static void exigirPendiente(Cabecera c) {
        if (c.estado() != Estado.SOLICITADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "SOLICITUD_YA_RESUELTA",
                    "Esta solicitud ya no está pendiente de revisión.");
        }
    }

    /** Enlace del subadministrador al archivo de una evidencia (S-02 y la moderacion de CCPIP-46). */
    public static String urlAdmin(long autorizacionId, UUID evidenciaId) {
        return "/api/admin/autorizaciones/" + autorizacionId + "/evidencias/" + evidenciaId + "/archivo";
    }

    private static String recortar(String texto, int max) {
        return texto.length() > max ? texto.substring(0, max) : texto;
    }

    private record Cabecera(long id, Estado estado, String motivo, java.time.Instant solicitadaEn,
                            java.time.Instant decididaEn, LocalDate vigenteHasta, long productoId, String producto,
                            String linea, Productor productor) {
    }

    /** Con {@code bloquear}, dos subadministradores no deciden la misma solicitud a la vez. */
    private Cabecera cabecera(long id, boolean bloquear) {
        return jdbc.query("""
                        SELECT a.id, a.estado, a.motivo, a.solicitada_en, a.decidido_en, a.vigente_hasta,
                               p.id AS producto_id, p.nombre AS producto, l.nombre AS linea,
                               pr.id AS productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion,
                               pr.comunidad, pr.pueblo, pr.region
                        FROM autorizacion_producto a
                        JOIN producto p ON p.id = a.producto_id
                        JOIN productor pr ON pr.id = a.productor_id
                        JOIN linea_producto l ON l.id = p.linea_id
                        WHERE a.id = ?""" + (bloquear ? " FOR UPDATE OF a" : ""),
                        (rs, row) -> cabecera(rs), id)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("SOLICITUD_NO_ENCONTRADA", "La solicitud no existe."));
    }

    private static Cabecera cabecera(ResultSet rs) throws SQLException {
        return new Cabecera(rs.getLong("id"), Estado.valueOf(rs.getString("estado")), rs.getString("motivo"),
                EvidenciasService.instante(rs, "solicitada_en"), EvidenciasService.instante(rs, "decidido_en"),
                rs.getObject("vigente_hasta", LocalDate.class), rs.getLong("producto_id"), rs.getString("producto"),
                rs.getString("linea"), new Productor(rs.getLong("productor_id"), rs.getString("organizacion"),
                        rs.getString("comunidad"), rs.getString("pueblo"), rs.getString("region")));
    }
}
