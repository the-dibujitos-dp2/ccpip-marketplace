package pe.ccpip.merma;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.merma.MermaModelos.DatosMerma;
import pe.ccpip.merma.MermaModelos.Merma;
import pe.ccpip.merma.MermaModelos.MermaRegistrada;
import pe.ccpip.merma.MermaModelos.PaginaMerma;

/**
 * CCPIP-76 (RF-61), Figma A-04 y A-07: marcar existencias de un lote como merma con cantidad y causa.
 *
 * <ul>
 *   <li>Quien: el administrador en los lotes de sus puntos (usuario_ubicacion) y el productor en los
 *       lotes de su almacen. Un lote fuera de su alcance no existe para el (404, RNF-05).</li>
 *   <li>Confirmacion expresa: sin {@code confirmada} no se marca nada (422). La pantalla la pide con el
 *       resumen de A-07.</li>
 *   <li>Irreversible: un movimiento MERMA y su detalle, que no se editan ni se borran (RNF-16). Lote,
 *       stock y movimiento cambian juntos (RNF-15) y el lote se bloquea: la cantidad nunca excede lo
 *       disponible (RNF-09).</li>
 *   <li>Responsable y fecha quedan en la merma; la causa se copia del catalogo (CCPIP-299).</li>
 * </ul>
 *
 * CCPIP-75: la consulta incluye la merma automatica por vencimiento ({@link MermaPorVencimiento}).
 */
@Service
public class MermaService {

    static final int TAMANO = 20;

    private static final String PUNTOS_DEL_USUARIO =
            "l.ubicacion_id IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = :usuario)";

    private static final String COLUMNAS = """
            SELECT m.movimiento_id, m.registrado_en, p.id AS producto_id, p.nombre AS producto, l.codigo AS lote,
                   u.nombre AS ubicacion, mi.cantidad, m.motivo_nombre, m.motivo_nombre_en, m.detalle, m.automatica,
                   m.registrado_por, r.nombre AS responsable,
                   pr.id AS productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS productor
            FROM merma m
            JOIN movimiento_inventario mi ON mi.id = m.movimiento_id
            JOIN lote l ON l.id = m.lote_id
            JOIN producto p ON p.id = l.producto_id
            JOIN productor pr ON pr.id = p.productor_id
            JOIN ubicacion u ON u.id = l.ubicacion_id
            LEFT JOIN usuario r ON r.id = m.registrado_por
            """;

    /** El lote ya bloqueado. */
    private record Lote(long id, String codigo, long productoId, long ubicacionId, BigDecimal cantidad) {
    }

    private record Causa(long id, String nombre, String nombreEn, boolean requiereDetalle) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;

    public MermaService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
    }

    // ---------- Marcar ----------

    @Transactional
    public MermaRegistrada marcarEnPunto(long usuarioId, DatosMerma d) {
        puntos.nombres(usuarioId);
        return marcar(usuarioId, "u.tipo = 'PUNTO_ACOPIO' AND " + PUNTOS_DEL_USUARIO,
                new MapSqlParameterSource("usuario", usuarioId), "El lote no existe en tu punto de acopio.", d);
    }

    @Transactional
    public MermaRegistrada marcarEnAlmacen(long productorId, long usuarioId, DatosMerma d) {
        return marcar(usuarioId, "u.tipo = 'ALMACEN_PRODUCTOR' AND u.productor_id = :productor AND p.productor_id = :productor",
                new MapSqlParameterSource("productor", productorId), "El lote no existe en tu almacén.", d);
    }

    private MermaRegistrada marcar(long usuarioId, String alcance, MapSqlParameterSource p, String noExiste, DatosMerma d) {
        if (!d.confirmada()) {
            throw ErrorNegocio.invalido("CONFIRMACION_REQUERIDA", "Confirma la merma: no podrá revertirse.");
        }
        Causa causa = causa(d);
        Lote lote = bloquear(alcance, p, d.loteId(), noExiste);

        // Despues del bloqueo: un reintento simultaneo con la misma clave ya ve la merma del primero
        Optional<Long> previa = previa(usuarioId, d.clave());
        if (previa.isPresent()) {
            Merma merma = obtener(previa.get());
            boolean misma = merma.lote().equals(lote.codigo()) && merma.cantidad().compareTo(d.cantidad()) == 0;
            if (!misma) {
                throw envioReutilizado();
            }
            return new MermaRegistrada(merma, lote.cantidad(), true);
        }

        BigDecimal cantidad = d.cantidad();
        MapSqlParameterSource q = new MapSqlParameterSource("lote", lote.id()).addValue("cantidad", cantidad)
                .addValue("producto", lote.productoId()).addValue("ubicacion", lote.ubicacionId());
        // RNF-09: la merma nunca deja el lote ni el stock por debajo de cero
        if (lote.cantidad().compareTo(cantidad) < 0
                || jdbc.update("UPDATE lote SET cantidad_actual = cantidad_actual - :cantidad WHERE id = :lote AND cantidad_actual >= :cantidad", q) == 0
                || jdbc.update("""
                        UPDATE stock SET cantidad = cantidad - :cantidad, actualizado_en = now()
                        WHERE producto_id = :producto AND ubicacion_id = :ubicacion AND cantidad >= :cantidad""", q) == 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "SALDO_INSUFICIENTE",
                    "El lote " + lote.codigo() + " tiene " + lote.cantidad().stripTrailingZeros().toPlainString()
                            + " unidades: la merma no puede ser mayor.");
        }

        String detalle = d.detalle() == null || d.detalle().isBlank() ? null : d.detalle().trim();
        Long movimiento = jdbc.queryForObject("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, usuario_id, observacion)
                VALUES (:producto, :ubicacion, :lote, 'MERMA', :cantidad, :usuario, :observacion) RETURNING id""",
                q.addValue("usuario", usuarioId).addValue("observacion", "Merma: " + causa.nombre()), Long.class);
        try {
            jdbc.update("""
                    INSERT INTO merma (movimiento_id, lote_id, motivo_id, motivo_nombre, motivo_nombre_en, detalle, automatica,
                                       registrado_por, clave_idempotencia)
                    VALUES (:movimiento, :lote, :motivo, :nombre, :nombreEn, :detalle, false, :usuario, :clave)""",
                    q.addValue("movimiento", movimiento).addValue("motivo", causa.id()).addValue("nombre", causa.nombre())
                            .addValue("nombreEn", causa.nombreEn()).addValue("detalle", detalle).addValue("clave", d.clave()));
        } catch (DuplicateKeyException e) {
            // La misma clave en otro lote, en paralelo: el indice unico la frena
            throw envioReutilizado();
        }
        return new MermaRegistrada(obtener(movimiento), lote.cantidad().subtract(cantidad), false);
    }

    /** La causa debe estar activa; "Otra" exige describirla. */
    private Causa causa(DatosMerma d) {
        Causa causa = jdbc.query("""
                SELECT id, nombre, nombre_en, requiere_detalle FROM motivo_merma WHERE id = :id AND activo""",
                new MapSqlParameterSource("id", d.motivoId()),
                (rs, row) -> new Causa(rs.getLong("id"), rs.getString("nombre"), rs.getString("nombre_en"),
                        rs.getBoolean("requiere_detalle")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.invalido("MOTIVO_INVALIDO", "Elige una causa de la lista."));
        if (causa.requiereDetalle() && (d.detalle() == null || d.detalle().isBlank())) {
            throw ErrorNegocio.invalido("DETALLE_REQUERIDO", "Describe la causa de la merma.");
        }
        return causa;
    }

    private Lote bloquear(String alcance, MapSqlParameterSource p, long loteId, String noExiste) {
        return jdbc.query("""
                SELECT l.id, l.codigo, l.producto_id, l.ubicacion_id, l.cantidad_actual
                FROM lote l
                JOIN ubicacion u ON u.id = l.ubicacion_id
                JOIN producto p ON p.id = l.producto_id
                WHERE l.id = :id AND %s
                FOR UPDATE OF l""".formatted(alcance), new MapSqlParameterSource(p.getValues()).addValue("id", loteId),
                (rs, row) -> new Lote(rs.getLong("id"), rs.getString("codigo"), rs.getLong("producto_id"),
                        rs.getLong("ubicacion_id"), rs.getBigDecimal("cantidad_actual")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("LOTE_NO_ENCONTRADO", noExiste));
    }

    private Optional<Long> previa(long usuarioId, UUID clave) {
        return jdbc.query("SELECT movimiento_id FROM merma WHERE registrado_por = :usuario AND clave_idempotencia = :clave",
                new MapSqlParameterSource("usuario", usuarioId).addValue("clave", clave),
                (rs, row) -> rs.getLong("movimiento_id")).stream().findFirst();
    }

    private static ErrorNegocio envioReutilizado() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "ENVIO_REUTILIZADO",
                "Este envío ya se usó para otra merma. Vuelve a abrir el formulario.");
    }

    // ---------- Consultar (A-04 y PW "Merma", CCPIP-75) ----------

    /**
     * La merma de los lotes de sus puntos, manual y automatica, con el productor de cada lote
     * (CCPIP-423). 409 ACOPIO_SIN_ASIGNAR sin puntos.
     */
    @Transactional(readOnly = true)
    public PaginaMerma dePuntos(long usuarioId, int pagina) {
        String ubicacion = puntos.nombres(usuarioId);
        return pagina(ubicacion, PUNTOS_DEL_USUARIO, new MapSqlParameterSource("usuario", usuarioId), pagina, null);
    }

    /**
     * La merma de los lotes de sus productos, en su almacen y en los puntos. La que registro un punto
     * no muestra quien (RNF-05): solo el punto.
     */
    @Transactional(readOnly = true)
    public PaginaMerma deProductor(long productorId, long usuarioId, int pagina) {
        return pagina(null, "p.productor_id = :productor", new MapSqlParameterSource("productor", productorId), pagina,
                usuarioId);
    }

    private PaginaMerma pagina(String ubicacion, String filtro, MapSqlParameterSource p, int pagina, Long soloResponsable) {
        String donde = " WHERE " + filtro;
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM merma m JOIN lote l ON l.id = m.lote_id JOIN producto p ON p.id = l.producto_id""" + donde,
                p, Long.class);
        long cuantos = total == null ? 0 : total;
        p.addValue("limite", TAMANO).addValue("desde", (long) pagina * TAMANO);
        List<Merma> items = jdbc.query(COLUMNAS + donde + " ORDER BY m.registrado_en DESC, m.movimiento_id DESC LIMIT :limite OFFSET :desde",
                p, (rs, row) -> merma(rs, soloResponsable));
        return new PaginaMerma(ubicacion, items, pagina, TAMANO, cuantos, (int) ((cuantos + TAMANO - 1) / TAMANO));
    }

    private Merma obtener(long movimientoId) {
        return jdbc.query(COLUMNAS + " WHERE m.movimiento_id = :id", new MapSqlParameterSource("id", movimientoId),
                (rs, row) -> merma(rs, null)).get(0);
    }

    /** {@code soloResponsable}: si no es nulo, el nombre del responsable solo se muestra si es esa persona. */
    private static Merma merma(ResultSet rs, Long soloResponsable) throws SQLException {
        Object registradoPor = rs.getObject("registrado_por");
        boolean mostrar = soloResponsable == null
                || (registradoPor != null && ((Number) registradoPor).longValue() == soloResponsable);
        return new Merma(rs.getLong("movimiento_id"), rs.getObject("registrado_en", Timestamp.class).toInstant(),
                rs.getLong("producto_id"), rs.getString("producto"), rs.getString("lote"), rs.getString("ubicacion"),
                rs.getBigDecimal("cantidad"), rs.getString("motivo_nombre"), rs.getString("motivo_nombre_en"),
                rs.getString("detalle"), rs.getBoolean("automatica"), mostrar ? rs.getString("responsable") : null,
                rs.getLong("productor_id"), rs.getString("productor"));
    }
}
