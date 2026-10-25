package pe.ccpip.administracion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.ccpip.administracion.TableroModelos.MermaLinea;
import pe.ccpip.administracion.TableroModelos.MermaPunto;
import pe.ccpip.administracion.TableroModelos.NivelMerma;
import pe.ccpip.administracion.TableroModelos.Productores;
import pe.ccpip.administracion.TableroModelos.ProductorTop;
import pe.ccpip.administracion.TableroModelos.PuntoAcopio;
import pe.ccpip.administracion.TableroModelos.VentaLinea;
import pe.ccpip.administracion.TableroModelos.Ventas;

/**
 * Consultas agregadas del tablero SA-01. Una venta es un pedido ya pagado: excluye los que esperan
 * pago y los cancelados. El mes es el calendario de Lima.
 */
@Repository
public class TableroRepository {

    /** Inicio del mes en curso en Lima, como instante. */
    private static final String INICIO_MES =
            "(date_trunc('month', now() AT TIME ZONE 'America/Lima') AT TIME ZONE 'America/Lima')";
    private static final String INICIO_MES_ANTERIOR =
            "((date_trunc('month', now() AT TIME ZONE 'America/Lima') - interval '1 month') AT TIME ZONE 'America/Lima')";
    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /** Lotes que aun cuentan como stock (los vencidos ya pasaron a merma), con alias l. */
    private static final String LOTE_VIGENTE = "(l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= %s)".formatted(HOY);

    /** Pedidos pagados del mes en curso, con alias pe. */
    private static final String VENDIDO_EN_EL_MES = """
            pe.estado NOT IN ('PENDIENTE_PAGO', 'CANCELADO') AND pe.confirmado_en >= %s""".formatted(INICIO_MES);

    private final JdbcTemplate jdbc;

    public TableroRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String periodo() {
        return jdbc.queryForObject("SELECT to_char(now() AT TIME ZONE 'America/Lima', 'YYYY-MM')", String.class);
    }

    public Ventas ventas() {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(pe.total) FILTER (WHERE pe.confirmado_en >= %1$s), 0) AS mes,
                       coalesce(sum(pe.total) FILTER (WHERE pe.confirmado_en < %1$s), 0) AS anterior,
                       count(*) FILTER (WHERE pe.confirmado_en >= %1$s) AS pedidos
                FROM pedido pe
                WHERE pe.estado NOT IN ('PENDIENTE_PAGO', 'CANCELADO') AND pe.confirmado_en >= %2$s"""
                .formatted(INICIO_MES, INICIO_MES_ANTERIOR),
                (rs, row) -> {
                    BigDecimal mes = rs.getBigDecimal("mes");
                    BigDecimal anterior = rs.getBigDecimal("anterior");
                    return new Ventas(mes, anterior, variacion(mes, anterior), rs.getLong("pedidos"));
                });
    }

    public Productores productores() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE activo) AS activos,
                       count(*) FILTER (WHERE activo AND creado_en >= %s) AS nuevos
                FROM productor""".formatted(INICIO_MES),
                (rs, row) -> new Productores(rs.getLong("activos"), rs.getLong("nuevos")));
    }

    /** Todas las lineas activas, con 0 si no vendieron, para comparar sus barras. */
    public List<VentaLinea> ventasPorLinea() {
        return jdbc.query("""
                SELECT l.codigo, l.nombre, coalesce(sum(ip.subtotal), 0) AS monto
                FROM linea_producto l
                LEFT JOIN producto p ON p.linea_id = l.id
                LEFT JOIN item_pedido ip ON ip.producto_id = p.id
                     AND EXISTS (SELECT 1 FROM pedido pe WHERE pe.id = ip.pedido_id AND %s)
                WHERE l.activo
                GROUP BY l.id, l.codigo, l.nombre
                ORDER BY monto DESC, l.nombre""".formatted(VENDIDO_EN_EL_MES),
                (rs, row) -> new VentaLinea(rs.getString("codigo"), rs.getString("nombre"), rs.getBigDecimal("monto")));
    }

    public List<ProductorTop> productoresTop(int limite) {
        return jdbc.query("""
                SELECT pr.id, coalesce(pr.nombre_tienda, pr.comunidad) AS nombre, pr.pueblo, sum(ip.subtotal) AS monto
                FROM item_pedido ip
                JOIN pedido pe ON pe.id = ip.pedido_id
                JOIN productor pr ON pr.id = ip.productor_id
                WHERE %s
                GROUP BY pr.id
                ORDER BY monto DESC, nombre
                LIMIT ?""".formatted(VENDIDO_EN_EL_MES),
                (rs, row) -> new ProductorTop(rs.getLong("id"), rs.getString("nombre"), rs.getString("pueblo"),
                        rs.getBigDecimal("monto")),
                limite);
    }

    /**
     * Estado de cada punto de acopio activo. La merma del mes suma todas sus lineas; su nivel es el de la linea
     * mas comprometida de {@code mermaPorLinea} (CCPIP-430).
     */
    public List<PuntoAcopio> puntos(List<MermaLinea> mermaPorLinea) {
        return jdbc.query("""
                SELECT u.id, u.nombre, u.distrito,
                       (SELECT coalesce(sum(l.cantidad_actual), 0) FROM lote l
                        WHERE l.ubicacion_id = u.id AND %s) AS stock,
                       (SELECT count(*) FROM pedido pe WHERE pe.ubicacion_origen_id = u.id AND %s) AS pedidos,
                       (SELECT coalesce(sum(pe.total), 0) FROM pedido pe WHERE pe.ubicacion_origen_id = u.id AND %s) AS ventas,
                       (SELECT coalesce(sum(mi.cantidad), 0) FROM merma m
                        JOIN movimiento_inventario mi ON mi.id = m.movimiento_id
                        WHERE mi.ubicacion_id = u.id AND m.registrado_en >= %s) AS merma
                FROM ubicacion u
                WHERE u.tipo = 'PUNTO_ACOPIO' AND u.activo
                ORDER BY u.nombre""".formatted(LOTE_VIGENTE, VENDIDO_EN_EL_MES, VENDIDO_EN_EL_MES, INICIO_MES),
                (rs, row) -> {
                    long id = rs.getLong("id");
                    BigDecimal stock = rs.getBigDecimal("stock");
                    BigDecimal merma = rs.getBigDecimal("merma");
                    return new PuntoAcopio(id, rs.getString("nombre"), rs.getString("distrito"), stock,
                            rs.getLong("pedidos"), rs.getBigDecimal("ventas"), mermaDelPunto(id, merma, stock, mermaPorLinea));
                });
    }

    /**
     * CCPIP-430: merma del mes de cada linea en cada punto de acopio activo, solo donde hubo merma, con los
     * umbrales de la linea. La linea de una oferta es su linea principal.
     */
    public List<MermaLinea> mermaPorLinea() {
        return jdbc.query("""
                WITH merma_mes AS (
                    SELECT mi.ubicacion_id, p.linea_id, sum(mi.cantidad) AS unidades
                    FROM merma m
                    JOIN movimiento_inventario mi ON mi.id = m.movimiento_id
                    JOIN producto p ON p.id = mi.producto_id
                    WHERE m.registrado_en >= %s AND p.linea_id IS NOT NULL
                    GROUP BY mi.ubicacion_id, p.linea_id
                ), stock_vigente AS (
                    SELECT l.ubicacion_id, p.linea_id, sum(l.cantidad_actual) AS stock
                    FROM lote l
                    JOIN producto p ON p.id = l.producto_id
                    WHERE %s
                    GROUP BY l.ubicacion_id, p.linea_id
                )
                SELECT u.id AS punto_id, u.nombre AS punto, li.codigo, li.nombre AS linea, mm.unidades,
                       coalesce(sv.stock, 0) AS stock, li.merma_umbral_alerta, li.merma_umbral_critico
                FROM merma_mes mm
                JOIN ubicacion u ON u.id = mm.ubicacion_id AND u.tipo = 'PUNTO_ACOPIO' AND u.activo
                JOIN linea_producto li ON li.id = mm.linea_id
                LEFT JOIN stock_vigente sv ON sv.ubicacion_id = mm.ubicacion_id AND sv.linea_id = mm.linea_id
                ORDER BY u.nombre, li.orden, li.nombre""".formatted(INICIO_MES, LOTE_VIGENTE),
                (rs, row) -> {
                    BigDecimal unidades = rs.getBigDecimal("unidades");
                    BigDecimal stock = rs.getBigDecimal("stock");
                    BigDecimal alerta = rs.getBigDecimal("merma_umbral_alerta");
                    BigDecimal critico = rs.getBigDecimal("merma_umbral_critico");
                    // Con merma siempre hay base: el porcentaje nunca es nulo aqui
                    BigDecimal porcentaje = Objects.requireNonNull(porcentajeMerma(unidades, stock));
                    return new MermaLinea(rs.getLong("punto_id"), rs.getString("punto"), rs.getString("codigo"),
                            rs.getString("linea"), unidades, stock, porcentaje, alerta, critico,
                            NivelMerma.de(porcentaje, alerta, critico));
                });
    }

    static MermaPunto mermaDelPunto(long puntoId, BigDecimal merma, BigDecimal stock, List<MermaLinea> mermaPorLinea) {
        BigDecimal porcentaje = porcentajeMerma(merma, stock);
        if (porcentaje == null) {
            return new MermaPunto(merma, null, null);
        }
        NivelMerma nivel = mermaPorLinea.stream()
                .filter(m -> m.puntoId() == puntoId)
                .map(MermaLinea::nivel)
                .max(Comparator.naturalOrder())
                .orElse(NivelMerma.NORMAL);
        return new MermaPunto(merma, porcentaje, nivel);
    }

    /** Merma / (merma + stock) en %, con un decimal; nulo si no hay base (sin stock ni merma). */
    static BigDecimal porcentajeMerma(BigDecimal merma, BigDecimal stock) {
        BigDecimal base = merma.add(stock);
        if (base.signum() == 0) {
            return null;
        }
        return merma.multiply(BigDecimal.valueOf(100)).divide(base, 1, RoundingMode.HALF_UP);
    }

    /** Variacion porcentual con un decimal; nula si no hay base para comparar. */
    static BigDecimal variacion(BigDecimal actual, BigDecimal anterior) {
        if (anterior == null || anterior.signum() == 0) {
            return null;
        }
        return actual.subtract(anterior).multiply(BigDecimal.valueOf(100)).divide(anterior, 1, RoundingMode.HALF_UP);
    }
}
