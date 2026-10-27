package pe.ccpip.inventario;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.Busqueda;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.inventario.InventarioModelos.FiltroMovimientos;
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

    private static final String COLUMNAS = """
            SELECT m.id, m.registrado_en, m.tipo, p.id AS producto_id, p.nombre AS producto, l.codigo AS lote,
                   m.cantidad, pe.numero AS pedido, m.observacion, m.motivo,
                   CASE WHEN m.tipo = 'SALIDA_VENTA' THEN NULL ELSE u.nombre END AS registrado_por
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;

    public MovimientosService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
    }

    /**
     * Lo mas reciente primero. {@code pedido} filtra por numero exacto, sin distinguir mayusculas;
     * {@code q} busca en el producto (sin tildes, RNF-22), el lote y el numero de pedido (CCPIP-417).
     */
    @Transactional(readOnly = true)
    public Movimientos listar(long usuarioId, FiltroMovimientos f, int pagina) {
        exigirRango(f.desde(), f.hasta(), "La fecha inicial debe ser anterior o igual a la final.");
        exigirNoNegativos("La cantidad no puede ser negativa.", f.cantidadMin(), f.cantidadMax());
        exigirRango(f.cantidadMin(), f.cantidadMax(), "La cantidad mínima debe ser menor o igual a la máxima.");
        String ubicacion = puntos.nombres(usuarioId);

        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId);
        List<String> filtros = new ArrayList<>();
        if (f.grupo() != null) {
            p.addValue("tipos", f.grupo().tipos());
            filtros.add(" AND m.tipo IN (:tipos)");
        }
        if (f.pedido() != null && !f.pedido().isBlank()) {
            p.addValue("pedido", f.pedido().trim().toUpperCase(Locale.ROOT));
            filtros.add(" AND upper(pe.numero) = :pedido");
        }
        String patron = Busqueda.patron(f.q());
        if (patron != null) {
            p.addValue("patron", patron);
            filtros.add("""
                     AND (lower(inmutable_unaccent(p.nombre)) LIKE lower(inmutable_unaccent(:patron)) ESCAPE '\\'
                          OR lower(coalesce(l.codigo, '')) LIKE lower(:patron) ESCAPE '\\'
                          OR lower(coalesce(pe.numero, '')) LIKE lower(:patron) ESCAPE '\\')""");
        }
        if (f.desde() != null) {
            p.addValue("fechaDesde", f.desde());
            filtros.add(" AND (m.registrado_en AT TIME ZONE 'America/Lima')::date >= :fechaDesde");
        }
        if (f.hasta() != null) {
            p.addValue("fechaHasta", f.hasta());
            filtros.add(" AND (m.registrado_en AT TIME ZONE 'America/Lima')::date <= :fechaHasta");
        }
        if (f.cantidadMin() != null) {
            p.addValue("cantidadMin", f.cantidadMin());
            filtros.add(" AND m.cantidad >= :cantidadMin");
        }
        if (f.cantidadMax() != null) {
            p.addValue("cantidadMax", f.cantidadMax());
            filtros.add(" AND m.cantidad <= :cantidadMax");
        }
        String donde = DESDE + String.join("", filtros);

        Long total = jdbc.queryForObject("SELECT count(*) " + donde, p, Long.class);
        long cuantos = total == null ? 0 : total;
        p.addValue("limite", TAMANO).addValue("desde", (long) pagina * TAMANO);
        List<Movimiento> items = jdbc.query(COLUMNAS + donde + " ORDER BY m.registrado_en DESC, m.id DESC LIMIT :limite OFFSET :desde",
                p, MovimientosService::movimiento);
        return new Movimientos(ubicacion, items, pagina, TAMANO, cuantos,
                (int) ((cuantos + TAMANO - 1) / TAMANO));
    }

    /** Un movimiento por su id, como lo muestra A-03. Sin filtro: lo usa quien ya valido el lote (CCPIP-121, 287, 283). */
    Movimiento obtener(long id) {
        return jdbc.query(COLUMNAS + """
                FROM movimiento_inventario m
                JOIN producto p ON p.id = m.producto_id
                LEFT JOIN lote l ON l.id = m.lote_id
                LEFT JOIN pedido pe ON pe.id = m.pedido_id
                LEFT JOIN usuario u ON u.id = m.usuario_id
                WHERE m.id = :id""", new MapSqlParameterSource("id", id), MovimientosService::movimiento).get(0);
    }

    /** CCPIP-417: un rango de filtro (fechas, cantidades, stock) con el inicio despues del fin no tiene sentido. */
    static <T extends Comparable<T>> void exigirRango(T desde, T hasta, String mensaje) {
        if (desde != null && hasta != null && desde.compareTo(hasta) > 0) {
            throw ErrorNegocio.invalido("RANGO_INVALIDO", mensaje);
        }
    }

    /** CCPIP-417: los limites de cantidad o de stock de un filtro no son negativos. */
    static void exigirNoNegativos(String mensaje, BigDecimal... limites) {
        for (BigDecimal limite : limites) {
            if (limite != null && limite.signum() < 0) {
                throw ErrorNegocio.invalido("VALOR_INVALIDO", mensaje);
            }
        }
    }

    private static Movimiento movimiento(ResultSet rs, int row) throws SQLException {
        return new Movimiento(rs.getLong("id"), rs.getObject("registrado_en", Timestamp.class).toInstant(),
                rs.getString("tipo"), rs.getLong("producto_id"), rs.getString("producto"), rs.getString("lote"),
                rs.getBigDecimal("cantidad"), rs.getString("pedido"), rs.getString("registrado_por"),
                rs.getString("observacion"), rs.getString("motivo"));
    }
}
