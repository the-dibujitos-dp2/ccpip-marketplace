package pe.ccpip.merma;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-120 (RF-62, C8): revision de una merma registrada por error.
 *
 * <ul>
 * <li>El administrador de un punto de acopio la pide sobre una merma manual de un punto asignado a su
 * cuenta, con cuantas unidades no debieron darse de baja y por que. Pedirla no toca el inventario.</li>
 * <li>Decision del cliente del 05/11/2026: el subadministrador la aprueba o la rechaza. Al aprobar, las
 * unidades vuelven a su lote con una ENTRADA {@code CORRECCION_MERMA}; la merma original no se edita
 * (RNF-15, RNF-16).</li>
 * </ul>
 */
@Service
public class RevisionMermaService {

    static final int MOTIVO_MIN = 10;
    static final int TEXTO_MAX = 500;
    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    public enum Estado { PENDIENTE, APROBADA, RECHAZADA }

    /** Una revision, con la merma a la que se refiere. */
    public record Revision(long id, long mermaId, Estado estado, BigDecimal cantidad, String motivo,
                           String solicitante, Instant registradaEn,
                           String producto, String lote, String punto, String productor,
                           BigDecimal cantidadMerma, String causa, Instant mermaRegistradaEn,
                           String resueltaPor, Instant resueltaEn, String respuesta, BigDecimal cantidadAprobada) {}

    public record Solicitud(BigDecimal cantidad, String motivo) {}

    public record Aprobacion(BigDecimal cantidad, String respuesta) {}

    public record Rechazo(String respuesta) {}

    private static final String SELECT = """
            SELECT s.id, s.merma_movimiento_id, s.estado, s.cantidad, s.motivo, us.nombre AS solicitante, s.registrada_en,
                   p.nombre AS producto, l.codigo AS lote, ub.nombre AS punto,
                   coalesce(pr.nombre_tienda, pr.comunidad) AS productor,
                   mov.cantidad AS cantidad_merma, m.motivo_nombre AS causa, m.registrado_en AS merma_registrada_en,
                   ur.nombre AS resuelta_por, s.resuelta_en, s.respuesta, s.cantidad_aprobada
            FROM solicitud_revision_merma s
            JOIN merma m ON m.movimiento_id = s.merma_movimiento_id
            JOIN movimiento_inventario mov ON mov.id = m.movimiento_id
            JOIN lote l ON l.id = m.lote_id
            JOIN producto p ON p.id = l.producto_id
            LEFT JOIN productor pr ON pr.id = p.productor_id
            JOIN ubicacion ub ON ub.id = l.ubicacion_id
            JOIN usuario us ON us.id = s.solicitante_id
            LEFT JOIN usuario ur ON ur.id = s.resuelta_por
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RevisionMermaService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- El punto de acopio ----------

    /**
     * Pide revisar una merma manual de un punto asignado. 404 si la merma no es de sus puntos (no se revela
     * si existe), 409 si es automatica o ya tiene una revision pendiente o aprobada.
     */
    @Transactional
    public Revision solicitar(long usuarioId, long mermaId, Solicitud s) {
        String motivo = texto(s.motivo());
        if (motivo == null || motivo.length() < MOTIVO_MIN) {
            throw ErrorNegocio.invalido("MOTIVO_REQUERIDO", "Explica en al menos " + MOTIVO_MIN + " caracteres qué se registró por error.");
        }
        if (motivo.length() > TEXTO_MAX) {
            throw ErrorNegocio.invalido("MOTIVO_LARGO", "El motivo admite hasta " + TEXTO_MAX + " caracteres.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("merma", mermaId);
        var merma = jdbc.query("""
                SELECT m.automatica, mov.cantidad
                FROM merma m
                JOIN movimiento_inventario mov ON mov.id = m.movimiento_id
                JOIN lote l ON l.id = m.lote_id
                JOIN ubicacion u ON u.id = l.ubicacion_id
                JOIN usuario_ubicacion uu ON uu.ubicacion_id = l.ubicacion_id AND uu.usuario_id = :usuario
                WHERE m.movimiento_id = :merma AND u.tipo = 'PUNTO_ACOPIO'""", p,
                (rs, n) -> new Object[] {rs.getBoolean("automatica"), rs.getBigDecimal("cantidad")})
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("MERMA_NO_ENCONTRADA", "No se encontró la merma en tus puntos."));
        if ((Boolean) merma[0]) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "MERMA_AUTOMATICA",
                    "La merma por vencimiento la registra el sistema: no se revisa.");
        }
        BigDecimal cantidadMerma = (BigDecimal) merma[1];
        if (s.cantidad() == null || s.cantidad().signum() <= 0 || s.cantidad().compareTo(cantidadMerma) > 0) {
            throw ErrorNegocio.invalido("CANTIDAD_INVALIDA",
                    "Indica entre 1 y " + cantidadMerma.stripTrailingZeros().toPlainString() + " unidades.");
        }
        Long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO solicitud_revision_merma (merma_movimiento_id, solicitante_id, cantidad, motivo)
                    VALUES (:merma, :usuario, :cantidad, :motivo) RETURNING id""",
                    p.addValue("cantidad", s.cantidad()).addValue("motivo", motivo), Long.class);
        } catch (DuplicateKeyException e) {
            throw yaRevisada();
        }
        return obtener(id);
    }

    /** Las revisiones de la merma de sus puntos, la mas reciente primero. */
    @Transactional(readOnly = true)
    public List<Revision> dePuntos(long usuarioId) {
        return jdbc.query(SELECT + """
                JOIN usuario_ubicacion uu ON uu.ubicacion_id = l.ubicacion_id AND uu.usuario_id = :usuario
                WHERE ub.tipo = 'PUNTO_ACOPIO'
                ORDER BY s.registrada_en DESC, s.id DESC""", new MapSqlParameterSource("usuario", usuarioId), this::fila);
    }

    // ---------- El subadministrador ----------

    /** Las revisiones, pendientes primero; con {@code estado} solo las de ese estado. */
    @Transactional(readOnly = true)
    public List<Revision> listar(Estado estado) {
        return jdbc.query(SELECT + """
                WHERE (CAST(:estado AS varchar) IS NULL OR s.estado = :estado)
                ORDER BY (s.estado = 'PENDIENTE') DESC, s.registrada_en DESC, s.id DESC
                LIMIT 500""", new MapSqlParameterSource("estado", estado == null ? null : estado.name()), this::fila);
    }

    /**
     * Aprueba la revision: {@code cantidad} unidades (por defecto las pedidas, nunca mas) vuelven al lote
     * con una ENTRADA CORRECCION_MERMA. No se reintegra a un lote vencido: esa revision solo se rechaza.
     */
    @Transactional
    public Revision aprobar(long subadminId, long id, Aprobacion a) {
        var pendiente = pendiente(id);
        BigDecimal cantidad = a.cantidad() == null ? pendiente.cantidad() : a.cantidad();
        if (cantidad.signum() <= 0 || cantidad.compareTo(pendiente.cantidad()) > 0) {
            throw ErrorNegocio.invalido("CANTIDAD_INVALIDA",
                    "Aprueba entre 1 y " + pendiente.cantidad().stripTrailingZeros().toPlainString() + " unidades.");
        }
        String respuesta = texto(a.respuesta());
        if (respuesta != null && respuesta.length() > TEXTO_MAX) {
            throw ErrorNegocio.invalido("RESPUESTA_LARGA", "La respuesta admite hasta " + TEXTO_MAX + " caracteres.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("usuario", subadminId)
                .addValue("cantidad", cantidad).addValue("respuesta", respuesta);
        var lote = jdbc.queryForObject("""
                SELECT l.id, l.producto_id, l.ubicacion_id, (l.fecha_vencimiento IS NOT NULL AND l.fecha_vencimiento < %s) AS vencido
                FROM solicitud_revision_merma s
                JOIN merma m ON m.movimiento_id = s.merma_movimiento_id
                JOIN lote l ON l.id = m.lote_id
                WHERE s.id = :id
                FOR UPDATE OF l""".formatted(HOY), p,
                (rs, n) -> new long[] {rs.getLong("id"), rs.getLong("producto_id"), rs.getLong("ubicacion_id"), rs.getBoolean("vencido") ? 1 : 0});
        if (lote[3] == 1) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "LOTE_VENCIDO",
                    "El lote ya venció: sus unidades no pueden volver al inventario. Rechaza la revisión explicando el motivo.");
        }
        p.addValue("lote", lote[0]).addValue("producto", lote[1]).addValue("ubicacion", lote[2]);
        // El lote nunca supera lo que ingreso: solo vuelve lo que la merma saco
        if (jdbc.update("""
                UPDATE lote SET cantidad_actual = cantidad_actual + :cantidad
                WHERE id = :lote AND cantidad_actual + :cantidad <= cantidad_inicial""", p) == 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "SUPERA_LOTE",
                    "Con esa cantidad el lote superaría lo que ingresó. Aprueba menos unidades.");
        }
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (:producto, :ubicacion, :cantidad)
                ON CONFLICT (producto_id, ubicacion_id)
                DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad, actualizado_en = now()""", p);
        Long movimiento = jdbc.queryForObject("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, motivo, cantidad, usuario_id, observacion)
                VALUES (:producto, :ubicacion, :lote, 'ENTRADA', 'CORRECCION_MERMA', :cantidad, :usuario, :observacion)
                RETURNING id""", p.addValue("observacion", "Corrección de merma: revisión #" + id), Long.class);
        jdbc.update("""
                UPDATE solicitud_revision_merma
                SET estado = 'APROBADA', resuelta_por = :usuario, resuelta_en = now(), respuesta = :respuesta,
                    cantidad_aprobada = :cantidad, correccion_movimiento_id = :movimiento
                WHERE id = :id""", p.addValue("movimiento", movimiento));
        return obtener(id);
    }

    /** Rechaza la revision con una respuesta para el punto. El inventario no cambia. */
    @Transactional
    public Revision rechazar(long subadminId, long id, Rechazo r) {
        pendiente(id);
        String respuesta = texto(r.respuesta());
        if (respuesta == null) {
            throw ErrorNegocio.invalido("RESPUESTA_REQUERIDA", "Explica al punto por qué se rechaza.");
        }
        if (respuesta.length() > TEXTO_MAX) {
            throw ErrorNegocio.invalido("RESPUESTA_LARGA", "La respuesta admite hasta " + TEXTO_MAX + " caracteres.");
        }
        jdbc.update("""
                UPDATE solicitud_revision_merma
                SET estado = 'RECHAZADA', resuelta_por = :usuario, resuelta_en = now(), respuesta = :respuesta
                WHERE id = :id""", new MapSqlParameterSource("id", id).addValue("usuario", subadminId).addValue("respuesta", respuesta));
        return obtener(id);
    }

    // ---------- Apoyo ----------

    private record Pendiente(BigDecimal cantidad) {}

    /** Bloquea la revision; 404 si no existe, 409 si ya se resolvio. */
    private Pendiente pendiente(long id) {
        var fila = jdbc.query("SELECT estado, cantidad FROM solicitud_revision_merma WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id),
                (rs, n) -> new Object[] {rs.getString("estado"), rs.getBigDecimal("cantidad")})
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("REVISION_NO_ENCONTRADA", "No se encontró la revisión."));
        if (!Estado.PENDIENTE.name().equals(fila[0])) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "REVISION_RESUELTA", "Esta revisión ya se resolvió.");
        }
        return new Pendiente((BigDecimal) fila[1]);
    }

    private Revision obtener(long id) {
        return jdbc.query(SELECT + "WHERE s.id = :id", new MapSqlParameterSource("id", id), this::fila).get(0);
    }

    private Revision fila(ResultSet rs, int n) throws SQLException {
        return new Revision(rs.getLong("id"), rs.getLong("merma_movimiento_id"), Estado.valueOf(rs.getString("estado")),
                rs.getBigDecimal("cantidad"), rs.getString("motivo"), rs.getString("solicitante"),
                instante(rs, "registrada_en"), rs.getString("producto"), rs.getString("lote"), rs.getString("punto"),
                rs.getString("productor"), rs.getBigDecimal("cantidad_merma"), rs.getString("causa"),
                instante(rs, "merma_registrada_en"), rs.getString("resuelta_por"), instante(rs, "resuelta_en"),
                rs.getString("respuesta"), rs.getBigDecimal("cantidad_aprobada"));
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        var t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }

    private static String texto(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ErrorNegocio yaRevisada() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "REVISION_EXISTENTE",
                "Esta merma ya tiene una revisión pendiente o aprobada.");
    }
}
