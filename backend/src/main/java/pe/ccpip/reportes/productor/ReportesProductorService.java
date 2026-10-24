package pe.ccpip.reportes.productor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.RangoFechas;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.Modalidad;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoOpcion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.TotalesVentas;

/**
 * Reportes del productor por periodo. Cada consulta filtra por el productor de la sesion en el servidor
 * (RNF-05) y lee importes copiados al confirmar cada compra (RNF-13), sin recalcularlos. Sin fechas, el
 * año en curso hasta hoy (fecha de Lima); sin agrupacion, por mes; como maximo un año por consulta.
 */
@Service
public class ReportesProductorService {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportesProductorService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Los filtros comunes, ya resueltos: rango de fechas, agrupacion y producto opcional (siempre suyo). */
    record Consulta(long productorId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long producto) {

        MapSqlParameterSource parametros() {
            MapSqlParameterSource p = new MapSqlParameterSource("productor", productorId)
                    .addValue("desde", desde).addValue("hasta", hasta);
            return producto == null ? p : p.addValue("producto", producto);
        }

        /** Condicion del filtro de producto sobre la columna indicada. */
        String filtroProducto(String columna) {
            return producto == null ? "" : " AND " + columna + " = :producto";
        }
    }

    Consulta consulta(long productorId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long producto) {
        LocalDate hoy = jdbc.getJdbcTemplate()
                .queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
        RangoFechas rango = RangoFechas.resolver(desde, hasta, hoy, fin -> fin.withDayOfYear(1));
        if (producto != null && !esSuyo(productorId, producto)) {
            // Un producto ajeno no existe para el (RNF-05)
            throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe.");
        }
        return new Consulta(productorId, rango.desde(), rango.hasta(), agrupacion == null ? Agrupacion.MES : agrupacion,
                producto);
    }

    /**
     * CCPIP-114 (RF-25): lo vendido de sus productos, por la fecha en que se confirmo cada compra. Cuentan
     * los pedidos confirmados (pagados) y no cancelados, igual que las ventas de la plataforma (CCPIP-345).
     * La modalidad separa B2C (tarifa minorista) de B2B (mayorista); filtrar por una deja la otra en cero.
     */
    @Transactional(readOnly = true)
    public ReporteVentas ventas(long productorId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                                Long producto, Modalidad modalidad) {
        Consulta c = consulta(productorId, desde, hasta, agrupacion, producto);
        Tramos tramos = new Tramos(c.desde(), c.hasta(), c.agrupacion());
        int n = tramos.cantidad();
        BigDecimal[] unidades = ceros(n);
        BigDecimal[] b2c = ceros(n);
        BigDecimal[] b2b = ceros(n);
        Map<Long, ProductoVentas> porProducto = new LinkedHashMap<>();

        MapSqlParameterSource p = c.parametros();
        String filtroModalidad = "";
        if (modalidad != null) {
            p.addValue("tarifa", modalidad.name());
            filtroModalidad = " AND i.tarifa = :tarifa";
        }
        jdbc.query("""
                SELECT (pe.confirmado_en AT TIME ZONE 'America/Lima')::date AS fecha, i.producto_id, pr.nombre, i.tarifa,
                       sum(i.cantidad) AS unidades, sum(i.subtotal) AS monto
                FROM item_pedido i
                JOIN pedido pe ON pe.id = i.pedido_id
                JOIN producto pr ON pr.id = i.producto_id
                WHERE i.productor_id = :productor
                  AND pe.confirmado_en IS NOT NULL AND pe.estado <> 'CANCELADO'
                  AND (pe.confirmado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta
                """ + c.filtroProducto("i.producto_id") + filtroModalidad + """

                GROUP BY 1, i.producto_id, pr.nombre, i.tarifa""", p, rs -> {
            int i = tramos.posicion(rs.getObject("fecha", LocalDate.class));
            BigDecimal u = rs.getBigDecimal("unidades");
            BigDecimal monto = rs.getBigDecimal("monto");
            boolean minorista = Modalidad.MINORISTA.name().equals(rs.getString("tarifa"));
            unidades[i] = unidades[i].add(u);
            if (minorista) {
                b2c[i] = b2c[i].add(monto);
            } else {
                b2b[i] = b2b[i].add(monto);
            }
            long id = rs.getLong("producto_id");
            ProductoVentas previo = porProducto.getOrDefault(id,
                    new ProductoVentas(id, rs.getString("nombre"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal pB2c = minorista ? previo.b2c().add(monto) : previo.b2c();
            BigDecimal pB2b = minorista ? previo.b2b() : previo.b2b().add(monto);
            porProducto.put(id, new ProductoVentas(id, previo.producto(), previo.unidades().add(u), pB2c, pB2b, pB2c.add(pB2b)));
        });

        List<PeriodoVentas> periodos = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            Tramos.Tramo t = tramos.lista().get(i);
            BigDecimal total = b2c[i].add(b2b[i]);
            acumulado = acumulado.add(total);
            periodos.add(new PeriodoVentas(t.inicio(), t.fin(), unidades[i], b2c[i], b2b[i], total, acumulado));
        }
        TotalesVentas totales = new TotalesVentas(suma(unidades), suma(b2c), suma(b2b), acumulado);
        List<ProductoVentas> productos = porProducto.values().stream()
                .sorted(Comparator.comparing(ProductoVentas::total).reversed().thenComparing(ProductoVentas::producto))
                .toList();
        return new ReporteVentas(c.desde(), c.hasta(), c.agrupacion(), c.producto(), modalidad, totales, periodos,
                productos, productos(productorId));
    }

    /** Sus productos, para el filtro de la pantalla. */
    List<ProductoOpcion> productos(long productorId) {
        return jdbc.query("SELECT id, nombre FROM producto WHERE productor_id = :productor ORDER BY nombre, id",
                new MapSqlParameterSource("productor", productorId),
                (rs, row) -> new ProductoOpcion(rs.getLong("id"), rs.getString("nombre")));
    }

    private boolean esSuyo(long productorId, long productoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM producto WHERE id = :producto AND productor_id = :productor)",
                new MapSqlParameterSource("producto", productoId).addValue("productor", productorId), Boolean.class));
    }

    static BigDecimal[] ceros(int n) {
        BigDecimal[] arreglo = new BigDecimal[n];
        java.util.Arrays.fill(arreglo, BigDecimal.ZERO);
        return arreglo;
    }

    static BigDecimal suma(BigDecimal[] valores) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : valores) {
            total = total.add(v);
        }
        return total;
    }
}
