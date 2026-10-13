package pe.ccpip.inventario;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.inventario.InventarioModelos.Grupo;
import pe.ccpip.inventario.InventarioModelos.Movimiento;
import pe.ccpip.inventario.InventarioModelos.Movimientos;

/**
 * CCPIP-158 (RF-127) y Figma A-03: movimientos de existencias del punto de acopio, con el descuento
 * que genero cada compra confirmada. Cada administrador ve solo los puntos que tiene asignados en
 * usuario_ubicacion; el filtro se aplica aqui, en el servidor (RNF-05).
 */
@Service
public class MovimientosService {

    static final int TAMANO = 20;

    private static final String DESDE = """
            FROM movimiento_inventario m
            JOIN producto p ON p.id = m.producto_id
            LEFT JOIN lote l ON l.id = m.lote_id
            LEFT JOIN pedido pe ON pe.id = m.pedido_id
            LEFT JOIN usuario u ON u.id = m.usuario_id
            WHERE m.ubicacion_id IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = :usuario)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MovimientosService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lo mas reciente primero. {@code pedido} filtra por numero exacto, sin distinguir mayusculas. */
    @Transactional(readOnly = true)
    public Movimientos listar(long usuarioId, Grupo grupo, String pedido, int pagina) {
        List<String> ubicaciones = jdbc.queryForList("""
                SELECT ub.nombre FROM usuario_ubicacion uu JOIN ubicacion ub ON ub.id = uu.ubicacion_id
                WHERE uu.usuario_id = :usuario ORDER BY ub.nombre""",
                new MapSqlParameterSource("usuario", usuarioId), String.class);
        if (ubicaciones.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "ACOPIO_SIN_ASIGNAR",
                    "Tu cuenta aún no tiene un punto de acopio asignado.");
        }

        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId);
        List<String> filtros = new ArrayList<>();
        if (grupo != null) {
            p.addValue("tipos", grupo.tipos());
            filtros.add(" AND m.tipo IN (:tipos)");
        }
        if (pedido != null && !pedido.isBlank()) {
            p.addValue("pedido", pedido.trim().toUpperCase(Locale.ROOT));
            filtros.add(" AND upper(pe.numero) = :pedido");
        }
        String donde = DESDE + String.join("", filtros);

        Long total = jdbc.queryForObject("SELECT count(*) " + donde, p, Long.class);
        long cuantos = total == null ? 0 : total;
        p.addValue("limite", TAMANO).addValue("desde", (long) pagina * TAMANO);
        List<Movimiento> items = jdbc.query("""
                SELECT m.id, m.registrado_en, m.tipo, p.id AS producto_id, p.nombre AS producto, l.codigo AS lote,
                       m.cantidad, pe.numero AS pedido, m.observacion,
                       CASE WHEN m.tipo = 'SALIDA_VENTA' THEN NULL ELSE u.nombre END AS registrado_por
                """ + donde + " ORDER BY m.registrado_en DESC, m.id DESC LIMIT :limite OFFSET :desde", p,
                (rs, row) -> new Movimiento(rs.getLong("id"), rs.getObject("registrado_en", Timestamp.class).toInstant(),
                        rs.getString("tipo"), rs.getLong("producto_id"), rs.getString("producto"), rs.getString("lote"),
                        rs.getBigDecimal("cantidad"), rs.getString("pedido"), rs.getString("registrado_por"),
                        rs.getString("observacion")));
        return new Movimientos(String.join(", ", ubicaciones), items, pagina, TAMANO, cuantos,
                (int) ((cuantos + TAMANO - 1) / TAMANO));
    }
}
