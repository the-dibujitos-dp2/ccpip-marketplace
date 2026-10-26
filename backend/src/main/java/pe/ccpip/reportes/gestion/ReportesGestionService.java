package pe.ccpip.reportes.gestion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.RangoFechas;
import pe.ccpip.reportes.ReportesModelos.Desglose;
import pe.ccpip.reportes.gestion.GestionModelos.ProductorVentas;
import pe.ccpip.reportes.gestion.GestionModelos.PuntoReporte;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteProductores;
import pe.ccpip.reportes.gestion.GestionModelos.ReportePuntos;

/**
 * Reportes de gestion del superadministrador (SA-05, SA-10). Leen los importes copiados al confirmar cada
 * pedido (RNF-13); no recalculan precios ni exponen compradores.
 */
@Service
public class ReportesGestionService {

    /** Cuantos productores muestra el ranking de mayores ventas. */
    static final int LIMITE_PRODUCTORES = 20;

    /** Un pedido vendido en el rango: pagado, no cancelado y confirmado entre {@code :desde} y {@code :hasta}. */
    static final String VENDIDO = """
            pe.confirmado_en IS NOT NULL AND pe.estado <> 'CANCELADO'
            AND (pe.confirmado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta""";

    /** Lo vendido de las lineas {@code i} separado en B2C (tarifa minorista) y B2B (mayorista). */
    static final String POR_TARIFA = """
            coalesce(sum(i.subtotal) FILTER (WHERE i.tarifa = 'MINORISTA'), 0) AS b2c,
            coalesce(sum(i.subtotal) FILTER (WHERE i.tarifa = 'MAYORISTA'), 0) AS b2b""";

    private final NamedParameterJdbcTemplate jdbc;

    public ReportesGestionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * CCPIP-122 (RF-86): los productores que mas vendieron en el rango, con su desglose B2C (tarifa minorista) y
     * B2B (mayorista), sus pedidos y unidades, y su parte de las ventas de la plataforma.
     */
    @Transactional(readOnly = true)
    public ReporteProductores productores(LocalDate desde, LocalDate hasta) {
        RangoFechas rango = rango(desde, hasta);
        MapSqlParameterSource p = parametros(rango).addValue("limite", LIMITE_PRODUCTORES);
        Ventas ventas = jdbc.queryForObject("""
                SELECT %s, count(DISTINCT i.productor_id) AS productores
                FROM item_pedido i JOIN pedido pe ON pe.id = i.pedido_id
                WHERE %s""".formatted(POR_TARIFA, VENDIDO), p,
                (rs, row) -> new Ventas(desglose(rs.getBigDecimal("b2c"), rs.getBigDecimal("b2b")), rs.getLong("productores")));
        Desglose totales = ventas.desglose();
        List<ProductorVentas> productores = jdbc.query("""
                SELECT po.id, coalesce(po.nombre_tienda, po.comunidad) AS nombre, po.pueblo,
                       count(DISTINCT i.pedido_id) AS pedidos, sum(i.cantidad) AS unidades, %s
                FROM item_pedido i
                JOIN pedido pe ON pe.id = i.pedido_id
                JOIN productor po ON po.id = i.productor_id
                WHERE %s
                GROUP BY po.id
                ORDER BY sum(i.subtotal) DESC, nombre, po.id
                LIMIT :limite""".formatted(POR_TARIFA, VENDIDO), p, (rs, row) -> {
            BigDecimal total = rs.getBigDecimal("b2c").add(rs.getBigDecimal("b2b"));
            return new ProductorVentas(rs.getLong("id"), rs.getString("nombre"), rs.getString("pueblo"), rs.getLong("pedidos"),
                    rs.getBigDecimal("unidades"), rs.getBigDecimal("b2c"), rs.getBigDecimal("b2b"), total,
                    porcentaje(total, totales.total()));
        });
        return new ReporteProductores(rango.desde(), rango.hasta(), totales, ventas.productores(), LIMITE_PRODUCTORES,
                productores);
    }

    /**
     * CCPIP-355 (RF-245): lo vendido desde cada punto de acopio en el rango, con su desglose B2C y B2B y sus
     * pedidos. Los puntos activos sin ventas aparecen en cero; uno inactivo, solo si vendio en el rango.
     */
    @Transactional(readOnly = true)
    public ReportePuntos puntos(LocalDate desde, LocalDate hasta) {
        RangoFechas rango = rango(desde, hasta);
        List<PuntoVentas> ventas = jdbc.query("""
                SELECT u.id, u.nombre, u.departamento, count(DISTINCT pe.id) AS pedidos, %s
                FROM ubicacion u
                LEFT JOIN pedido pe ON pe.ubicacion_origen_id = u.id AND %s
                LEFT JOIN item_pedido i ON i.pedido_id = pe.id
                WHERE u.tipo = 'PUNTO_ACOPIO'
                GROUP BY u.id
                HAVING u.activo OR count(pe.id) > 0
                ORDER BY coalesce(sum(i.subtotal), 0) DESC, u.nombre, u.id""".formatted(POR_TARIFA, VENDIDO), parametros(rango),
                (rs, row) -> new PuntoVentas(rs.getLong("id"), rs.getString("nombre"), rs.getString("departamento"),
                        rs.getLong("pedidos"), desglose(rs.getBigDecimal("b2c"), rs.getBigDecimal("b2b"))));
        Desglose totales = desglose(ventas.stream().map(v -> v.ventas().b2c()).reduce(BigDecimal.ZERO, BigDecimal::add),
                ventas.stream().map(v -> v.ventas().b2b()).reduce(BigDecimal.ZERO, BigDecimal::add));
        List<PuntoReporte> puntos = ventas.stream()
                .map(v -> new PuntoReporte(v.id(), v.nombre(), v.departamento(), v.pedidos(), v.ventas().b2c(),
                        v.ventas().b2b(), v.ventas().total(), porcentaje(v.ventas().total(), totales.total())))
                .toList();
        return new ReportePuntos(rango.desde(), rango.hasta(), totales, ventas.stream().mapToLong(PuntoVentas::pedidos).sum(),
                puntos);
    }

    private record PuntoVentas(long id, String nombre, String departamento, long pedidos, Desglose ventas) {
    }

    /** Las ventas de la plataforma en el rango y cuantos productores vendieron. */
    private record Ventas(Desglose desglose, long productores) {
    }

    static Desglose desglose(BigDecimal b2c, BigDecimal b2b) {
        return new Desglose(b2c, b2b, b2c.add(b2b));
    }

    /** Sin fechas, el año en curso hasta hoy (fecha de Lima), como los demas reportes de la plataforma. */
    RangoFechas rango(LocalDate desde, LocalDate hasta) {
        LocalDate hoy = jdbc.getJdbcTemplate()
                .queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
        return RangoFechas.resolver(desde, hasta, hoy, fin -> fin.withDayOfYear(1));
    }

    static MapSqlParameterSource parametros(RangoFechas rango) {
        return new MapSqlParameterSource("desde", rango.desde()).addValue("hasta", rango.hasta());
    }

    /** {@code parte} como porcentaje de {@code todo}, con un decimal; cero si no hay todo. */
    static BigDecimal porcentaje(BigDecimal parte, BigDecimal todo) {
        if (todo.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return parte.multiply(BigDecimal.valueOf(100)).divide(todo, 1, RoundingMode.HALF_UP);
    }
}
