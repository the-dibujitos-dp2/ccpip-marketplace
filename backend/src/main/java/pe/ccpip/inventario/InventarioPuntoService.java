package pe.ccpip.inventario;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.inventario.InventarioModelos.EstadoLote;
import pe.ccpip.inventario.InventarioModelos.FiltroEstado;
import pe.ccpip.inventario.InventarioModelos.InventarioPunto;
import pe.ccpip.inventario.InventarioModelos.LoteOpcion;
import pe.ccpip.inventario.InventarioModelos.LotePunto;
import pe.ccpip.inventario.InventarioModelos.Productor;
import pe.ccpip.inventario.InventarioModelos.ResumenPunto;
import pe.ccpip.alerta.PreferenciasAlerta;

/**
 * CCPIP-162 (RF-131), Figma A-02 "Inventario por producto, productor y lote": el administrador
 * consulta las existencias de los puntos que tiene en usuario_ubicacion, desglosadas por lote y
 * estado. La parte del productor es PW-16 (InventarioProductorService).
 *
 * <ul>
 *   <li>Aislamiento (RNF-05): el filtro por usuario_ubicacion se aplica aqui, en el servidor.</li>
 *   <li>Sin duplicar unidades: el saldo es lote.cantidad_actual, una vez por lote. Entradas y salidas
 *       se suman aparte, en subconsultas, para que unirlas a los movimientos no multiplique el saldo.</li>
 *   <li>Solo es vendible lo vigente con saldo. Lo que esta en transito o es merma no llega a un lote
 *       del punto, asi que nunca suma a lo vendible.</li>
 * </ul>
 */
@Service
public class InventarioPuntoService {

    static final int TAMANO = 20;

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /** Los lotes de los puntos del administrador, con los filtros de A-02. */
    private static final String DESDE = """
            FROM lote l
            JOIN producto p ON p.id = l.producto_id
            JOIN productor pr ON pr.id = p.productor_id
            JOIN ubicacion u ON u.id = l.ubicacion_id
            WHERE l.ubicacion_id IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = :usuario)
              AND u.tipo = 'PUNTO_ACOPIO'
            """;

    private static final String VENCIDO = "(l.fecha_vencimiento IS NOT NULL AND l.fecha_vencimiento < " + HOY + ")";

    private static final String PRODUCTOR = "coalesce(pr.nombre_tienda, pr.comunidad)";

    /** Por vencer segun la anticipacion que cada punto configuro en sus alertas (A-08, CCPIP-145). */
    private static final String POR_VENCER = "(l.fecha_vencimiento IS NOT NULL AND l.fecha_vencimiento <= " + HOY
            + " + " + PreferenciasAlerta.diasVencimientoDelPunto("l.ubicacion_id") + ")";

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;
    private final PreferenciasAlerta preferencias;

    public InventarioPuntoService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos,
                                  PreferenciasAlerta preferencias) {
        this.jdbc = jdbc;
        this.puntos = puntos;
        this.preferencias = preferencias;
    }

    /**
     * {@code q} busca en el nombre del producto o el codigo del lote, sin distinguir mayusculas ni
     * tildes. El resumen cubre todos los lotes filtrados, no solo la pagina.
     */
    @Transactional(readOnly = true)
    public InventarioPunto listar(long usuarioId, String q, Long productorId, FiltroEstado estado, int pagina) {
        String ubicacion = puntos.nombres(usuarioId);

        MapSqlParameterSource p = preferencias.provisionales(new MapSqlParameterSource("usuario", usuarioId));
        StringBuilder donde = new StringBuilder(DESDE);
        if (q != null && !q.isBlank()) {
            p.addValue("patron", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            donde.append("""
                      AND (lower(inmutable_unaccent(p.nombre)) LIKE lower(inmutable_unaccent(:patron)) ESCAPE '\\'
                           OR lower(l.codigo) LIKE lower(:patron) ESCAPE '\\')
                    """);
        }
        if (productorId != null) {
            p.addValue("productor", productorId);
            donde.append("  AND p.productor_id = :productor\n");
        }
        switch (estado) {
            case ACTIVOS -> donde.append("  AND l.cantidad_actual > 0\n");
            case AGOTADOS -> donde.append("  AND l.cantidad_actual = 0\n");
            case POR_VENCER -> donde.append("  AND l.cantidad_actual > 0 AND NOT " + VENCIDO + " AND " + POR_VENCER + "\n");
            case TODOS -> { }
        }

        ResumenPunto resumen = jdbc.queryForObject("""
                SELECT coalesce(sum(l.cantidad_actual), 0) AS stock,
                       coalesce(sum(l.cantidad_actual) FILTER (WHERE NOT %s), 0) AS vendible,
                       count(*) AS lotes
                """.formatted(VENCIDO) + donde, p,
                (rs, row) -> {
                    BigDecimal stock = rs.getBigDecimal("stock");
                    BigDecimal vendible = rs.getBigDecimal("vendible");
                    return new ResumenPunto(stock, vendible, stock.subtract(vendible), rs.getLong("lotes"));
                });
        long total = resumen.lotes();

        p.addValue("limite", TAMANO).addValue("desde", (long) pagina * TAMANO);
        List<LotePunto> items = jdbc.query("""
                SELECT l.id, l.codigo, l.cantidad_actual, l.fecha_vencimiento, %s AS vencido,
                       %s AS por_vencer,
                       p.id AS producto_id, p.nombre AS producto, pr.id AS productor_id, %s AS productor,
                       u.id AS ubicacion_id, u.nombre AS ubicacion,
                       (SELECT coalesce(sum(m.cantidad), 0) FROM movimiento_inventario m
                        WHERE m.lote_id = l.id AND m.ubicacion_id = l.ubicacion_id
                          AND m.tipo IN ('ENTRADA', 'TRANSFERENCIA_ENTRADA')) AS entradas,
                       (SELECT coalesce(sum(m.cantidad), 0) FROM movimiento_inventario m
                        WHERE m.lote_id = l.id AND m.ubicacion_id = l.ubicacion_id
                          AND m.tipo IN ('SALIDA', 'SALIDA_VENTA', 'TRANSFERENCIA_SALIDA', 'MERMA')) AS salidas
                """.formatted(VENCIDO, POR_VENCER, PRODUCTOR) + donde + """
                ORDER BY p.nombre, p.id, l.fecha_vencimiento NULLS LAST, l.codigo
                LIMIT :limite OFFSET :desde""", p,
                (rs, row) -> {
                    BigDecimal stock = rs.getBigDecimal("cantidad_actual");
                    EstadoLote e = estado(stock, rs.getBoolean("vencido"), rs.getBoolean("por_vencer"));
                    return new LotePunto(rs.getLong("id"), rs.getString("codigo"), rs.getLong("producto_id"),
                            rs.getString("producto"), rs.getLong("productor_id"), rs.getString("productor"),
                            rs.getLong("ubicacion_id"), rs.getString("ubicacion"),
                            rs.getObject("fecha_vencimiento", LocalDate.class), rs.getBigDecimal("entradas"),
                            rs.getBigDecimal("salidas"), stock, e,
                            e == EstadoLote.DISPONIBLE || e == EstadoLote.POR_VENCER);
                });

        return new InventarioPunto(ubicacion, diasAviso(usuarioId), resumen, productores(usuarioId),
                items, pagina, TAMANO, total, (int) ((total + TAMANO - 1) / TAMANO));
    }

    /**
     * La anticipacion que se muestra en el encabezado. Cada lote usa la de su punto; si el
     * administrador tiene varios con valores distintos se muestra la mayor.
     */
    private int diasAviso(long usuarioId) {
        Integer dias = jdbc.queryForObject("SELECT max(" + PreferenciasAlerta.diasVencimientoDelPunto("uu.ubicacion_id")
                        + ") FROM usuario_ubicacion uu WHERE uu.usuario_id = :usuario",
                preferencias.provisionales(new MapSqlParameterSource("usuario", usuarioId)), Integer.class);
        return dias == null ? preferencias.diasVencimientoProvisional() : dias;
    }

    /**
     * Los lotes que el formulario de entrada y salida ofrece: todos los de sus puntos, aun sin saldo
     * (una entrada puede volver a llenarlos), agrupables por productor y producto.
     */
    @Transactional(readOnly = true)
    public List<LoteOpcion> lotes(long usuarioId) {
        puntos.nombres(usuarioId);
        return jdbc.query("""
                SELECT l.id, l.codigo, p.id AS producto_id, p.nombre AS producto, pr.id AS productor_id,
                       %s AS productor, u.nombre AS ubicacion, l.cantidad_actual, l.fecha_vencimiento, %s AS vencido
                """.formatted(PRODUCTOR, VENCIDO) + DESDE + """
                ORDER BY productor, p.nombre, l.fecha_vencimiento NULLS LAST, l.codigo""",
                new MapSqlParameterSource("usuario", usuarioId),
                (rs, row) -> new LoteOpcion(rs.getLong("id"), rs.getString("codigo"), rs.getLong("producto_id"),
                        rs.getString("producto"), rs.getLong("productor_id"), rs.getString("productor"),
                        rs.getString("ubicacion"), rs.getBigDecimal("cantidad_actual"),
                        rs.getObject("fecha_vencimiento", LocalDate.class), rs.getBoolean("vencido")));
    }

    /** Filtro "Productor": solo quienes tienen algun lote en sus puntos. */
    private List<Productor> productores(long usuarioId) {
        return jdbc.query("""
                SELECT DISTINCT pr.id, %s AS nombre
                """.formatted(PRODUCTOR) + DESDE + " ORDER BY nombre",
                new MapSqlParameterSource("usuario", usuarioId),
                (rs, row) -> new Productor(rs.getLong("id"), rs.getString("nombre")));
    }

    /** Agotado primero (nada que vender), luego vencido, luego por vencer dentro del aviso. */
    static EstadoLote estado(BigDecimal stock, boolean vencido, boolean porVencer) {
        if (stock.signum() == 0) {
            return EstadoLote.AGOTADO;
        }
        if (vencido) {
            return EstadoLote.VENCIDO;
        }
        return porVencer ? EstadoLote.POR_VENCER : EstadoLote.DISPONIBLE;
    }
}
