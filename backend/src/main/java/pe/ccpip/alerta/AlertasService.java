package pe.ccpip.alerta;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.alerta.AlertaModelos.Alerta;
import pe.ccpip.alerta.AlertaModelos.Bandeja;
import pe.ccpip.alerta.AlertaModelos.Tipo;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;

/**
 * La bandeja de alertas del productor (PW-21) y del punto (A-08). El productor ve solo las suyas; el
 * administrador, solo las de los puntos que tiene en usuario_ubicacion (RNF-05). Las genera
 * {@link RevisionAlertas}.
 */
@Service
public class AlertasService {

    /** Cuanto tiempo quedan en la bandeja las alertas ya resueltas. Las abiertas quedan siempre. */
    static final int DIAS_HISTORIAL = 60;
    static final int MAXIMO = 200;

    private static final String DE_PRODUCTOR = "a.productor_id = :productor";
    private static final String DE_PUNTOS =
            "a.ubicacion_id IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = :usuario)";

    /**
     * Cada alerta con lo que la explica: el lote y su punto, los interesados de la oferta, la
     * transferencia o el pedido. Los interesados se cuentan al leer, asi la alerta dice cuantos hay hoy.
     */
    private static final String DETALLE = """
            SELECT a.id, a.tipo, a.fecha, a.creada_en, a.leida_en IS NOT NULL AS leida,
                   a.resuelta_en IS NOT NULL AS resuelta, a.correo <> 'NO_APLICA' AS por_correo,
                   p.id AS producto_id, p.nombre AS producto, l.codigo AS lote,
                   coalesce(ul.nombre, ut.nombre, up.nombre) AS ubicacion, t.id AS transferencia, pe.numero AS pedido,
                   CASE WHEN a.tipo IN ('POR_VENCER', 'ESTANCADO') THEN l.cantidad_actual
                        WHEN a.tipo = 'INTERES' THEN (SELECT sum(i.cantidad) FROM interes_compra i
                                                      WHERE i.producto_id = a.referencia_id AND i.estado = 'PENDIENTE')
                        WHEN a.tipo = 'TRANSFERENCIA_RECIBIDA' THEN (
                            SELECT CASE WHEN count(DISTINCT ti.producto_id) = 1 THEN sum(ti.cantidad) END
                            FROM transferencia_item ti WHERE ti.transferencia_id = t.id)
                   END AS cantidad,
                   CASE WHEN a.tipo = 'INTERES' THEN (SELECT count(*) FROM interes_compra i
                                                      WHERE i.producto_id = a.referencia_id AND i.estado = 'PENDIENTE')
                   END AS personas,
                   CASE WHEN a.tipo = 'TRANSFERENCIA_RECIBIDA' THEN ARRAY(
                            SELECT DISTINCT x.nombre FROM transferencia_item ti JOIN producto x ON x.id = ti.producto_id
                            WHERE ti.transferencia_id = t.id ORDER BY x.nombre)
                        WHEN a.tipo = 'PEDIDO_NUEVO' THEN ARRAY(
                            SELECT DISTINCT x.nombre FROM item_pedido ip JOIN producto x ON x.id = ip.producto_id
                            WHERE ip.pedido_id = pe.id AND ip.productor_id = a.productor_id ORDER BY x.nombre)
                   END AS productos
            FROM alerta a
            LEFT JOIN producto p ON p.id = a.producto_id
            LEFT JOIN lote l ON a.tipo IN ('POR_VENCER', 'ESTANCADO') AND l.id = a.referencia_id
            LEFT JOIN ubicacion ul ON ul.id = l.ubicacion_id
            LEFT JOIN transferencia t ON a.tipo = 'TRANSFERENCIA_RECIBIDA' AND t.id = a.referencia_id
            LEFT JOIN ubicacion ut ON ut.id = t.destino_id
            LEFT JOIN pedido pe ON a.tipo = 'PEDIDO_NUEVO' AND pe.id = a.referencia_id
            LEFT JOIN ubicacion up ON up.id = pe.ubicacion_origen_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;

    public AlertasService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
    }

    @Transactional(readOnly = true)
    public Bandeja deProductor(long productorId, Tipo tipo) {
        return bandeja(DE_PRODUCTOR, new MapSqlParameterSource("productor", productorId), tipo);
    }

    /** 409 ACOPIO_SIN_ASIGNAR si el administrador aun no tiene un punto. */
    @Transactional(readOnly = true)
    public Bandeja dePuntos(long usuarioId, Tipo tipo) {
        puntos.nombres(usuarioId);
        return bandeja(DE_PUNTOS, new MapSqlParameterSource("usuario", usuarioId), tipo);
    }

    /** Marca leida una alerta suya, o todas si {@code id} es nulo. Una ajena no existe (404). */
    @Transactional
    public void marcarLeidaProductor(long productorId, Long id) {
        marcarLeida(DE_PRODUCTOR, new MapSqlParameterSource("productor", productorId), id);
    }

    @Transactional
    public void marcarLeidaPuntos(long usuarioId, Long id) {
        puntos.nombres(usuarioId);
        marcarLeida(DE_PUNTOS, new MapSqlParameterSource("usuario", usuarioId), id);
    }

    /** Las alertas con esos ids, para el correo de {@link RevisionAlertas}. */
    List<Alerta> conIds(List<Long> ids) {
        return jdbc.query(DETALLE + "WHERE a.id IN (:ids) ORDER BY a.tipo, a.fecha, a.id",
                new MapSqlParameterSource("ids", ids), AlertasService::alerta);
    }

    private Bandeja bandeja(String destino, MapSqlParameterSource p, Tipo tipo) {
        p.addValue("dias", DIAS_HISTORIAL).addValue("maximo", MAXIMO).addValue("tipo", tipo == null ? null : tipo.name());
        List<Alerta> alertas = jdbc.query(DETALLE + "WHERE " + destino + """
                  AND (a.resuelta_en IS NULL OR a.creada_en > now() - make_interval(days => :dias))
                  AND (CAST(:tipo AS varchar) IS NULL OR a.tipo = :tipo)
                ORDER BY a.creada_en DESC, a.id DESC
                LIMIT :maximo""", p, AlertasService::alerta);
        Long sinLeer = jdbc.queryForObject("SELECT count(*) FROM alerta a WHERE " + destino
                + " AND a.leida_en IS NULL AND a.resuelta_en IS NULL", p, Long.class);
        return new Bandeja(alertas, sinLeer == null ? 0 : sinLeer);
    }

    private void marcarLeida(String destino, MapSqlParameterSource p, Long id) {
        if (id == null) {
            jdbc.update("UPDATE alerta a SET leida_en = now() WHERE " + destino + " AND a.leida_en IS NULL", p);
            return;
        }
        int cambiadas = jdbc.update("UPDATE alerta a SET leida_en = coalesce(a.leida_en, now()) WHERE "
                + destino + " AND a.id = :id", p.addValue("id", id));
        if (cambiadas == 0) {
            throw ErrorNegocio.noEncontrado("ALERTA_NO_ENCONTRADA", "La alerta no existe.");
        }
    }

    private static Alerta alerta(ResultSet rs, int row) throws SQLException {
        Tipo tipo = Tipo.valueOf(rs.getString("tipo"));
        Object transferencia = rs.getObject("transferencia");
        String referencia = transferencia != null ? "TR-%04d".formatted(((Number) transferencia).longValue())
                : rs.getString("pedido");
        java.sql.Array productos = rs.getArray("productos");
        BigDecimal cantidad = rs.getBigDecimal("cantidad");
        Object personas = rs.getObject("personas");
        Object productoId = rs.getObject("producto_id");
        return new Alerta(rs.getLong("id"), tipo, rs.getObject("fecha", LocalDate.class),
                rs.getObject("creada_en", OffsetDateTime.class), rs.getBoolean("leida"), rs.getBoolean("resuelta"),
                rs.getBoolean("por_correo"), productoId == null ? null : ((Number) productoId).longValue(),
                rs.getString("producto"), rs.getString("lote"), rs.getString("ubicacion"),
                cantidad,
                personas == null ? null : ((Number) personas).intValue(), referencia,
                productos == null ? null : Arrays.stream((Object[]) productos.getArray()).map(String::valueOf).toList());
    }
}
