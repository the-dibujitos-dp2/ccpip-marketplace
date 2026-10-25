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
import pe.ccpip.comun.Idioma;
import pe.ccpip.comun.RangoFechas;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.Tramos;
import pe.ccpip.reportes.exportar.DocumentoReporte;
import pe.ccpip.reportes.productor.ReportesProductorModelos.Modalidad;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoInventario;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.PeriodoVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoExistencias;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoOpcion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ProductoVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteInventario;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteVentas;
import pe.ccpip.reportes.productor.ReportesProductorModelos.TotalesInventario;
import pe.ccpip.reportes.productor.ReportesProductorModelos.TotalesProduccion;
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

    /**
     * CCPIP-333 (RF-223): lo producido de sus productos, por la fecha en que ingreso al inventario. Es cada
     * ENTRADA en sus lotes: el registro de un lote nuevo (que cuenta tambien como lote) y las entradas de
     * produccion o recepcion en un lote existente (CCPIP-283, CCPIP-121). Las transferencias no son
     * produccion: solo mueven lo ya producido entre ubicaciones.
     */
    @Transactional(readOnly = true)
    public ReporteProduccion produccion(long productorId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                                        Long producto) {
        Consulta c = consulta(productorId, desde, hasta, agrupacion, producto);
        Tramos tramos = new Tramos(c.desde(), c.hasta(), c.agrupacion());
        int n = tramos.cantidad();
        BigDecimal[] unidades = ceros(n);
        long[] lotes = new long[n];
        Map<Long, ProductoProduccion> porProducto = new LinkedHashMap<>();

        jdbc.query("""
                SELECT (m.registrado_en AT TIME ZONE 'America/Lima')::date AS fecha, m.producto_id, pr.nombre,
                       sum(m.cantidad) AS unidades,
                       count(DISTINCT m.lote_id) FILTER (WHERE m.motivo IS NULL) AS lotes
                FROM movimiento_inventario m
                JOIN producto pr ON pr.id = m.producto_id
                WHERE pr.productor_id = :productor AND m.tipo = 'ENTRADA'
                  AND (m.registrado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta
                """ + c.filtroProducto("m.producto_id") + """

                GROUP BY 1, m.producto_id, pr.nombre""", c.parametros(), rs -> {
            int i = tramos.posicion(rs.getObject("fecha", LocalDate.class));
            BigDecimal u = rs.getBigDecimal("unidades");
            long l = rs.getLong("lotes");
            unidades[i] = unidades[i].add(u);
            lotes[i] += l;
            long id = rs.getLong("producto_id");
            ProductoProduccion previo = porProducto.getOrDefault(id,
                    new ProductoProduccion(id, rs.getString("nombre"), BigDecimal.ZERO, 0));
            porProducto.put(id, new ProductoProduccion(id, previo.producto(), previo.unidades().add(u), previo.lotes() + l));
        });

        List<PeriodoProduccion> periodos = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        long totalLotes = 0;
        for (int i = 0; i < n; i++) {
            Tramos.Tramo t = tramos.lista().get(i);
            acumulado = acumulado.add(unidades[i]);
            totalLotes += lotes[i];
            periodos.add(new PeriodoProduccion(t.inicio(), t.fin(), unidades[i], lotes[i], acumulado));
        }
        List<ProductoProduccion> productos = porProducto.values().stream()
                .sorted(Comparator.comparing(ProductoProduccion::unidades).reversed().thenComparing(ProductoProduccion::producto))
                .toList();
        return new ReporteProduccion(c.desde(), c.hasta(), c.agrupacion(), c.producto(),
                new TotalesProduccion(acumulado, totalLotes), periodos, productos, productos(productorId));
    }

    /** Movimientos que cambian sus existencias totales; las transferencias solo las cambian de lugar. */
    private static final String MOVIMIENTOS_DE_EXISTENCIAS = "m.tipo IN ('ENTRADA', 'SALIDA', 'SALIDA_VENTA', 'MERMA')";

    /**
     * CCPIP-334 (RF-224): cuanto tuvo de cada producto y por que cambio, periodo a periodo. Todo sale de los
     * movimientos de sus lotes (RNF-15, ADR-10), asi que cada saldo queda explicado: entradas (registro de
     * lotes y entradas manuales), vendidas (separadas en B2C y B2B por la tarifa de la linea vendida),
     * merma y otras salidas (retiro o devolucion al productor). Las transferencias no cambian el total de su
     * inventario virtual unico y no se muestran; lo que esta en transito sigue contando como suyo, igual que
     * lo vencido hasta que se da de baja como merma.
     */
    @Transactional(readOnly = true)
    public ReporteInventario inventario(long productorId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                                        Long producto) {
        Consulta c = consulta(productorId, desde, hasta, agrupacion, producto);
        Tramos tramos = new Tramos(c.desde(), c.hasta(), c.agrupacion());
        int n = tramos.cantidad();
        BigDecimal[] entradas = ceros(n);
        BigDecimal[] b2c = ceros(n);
        BigDecimal[] b2b = ceros(n);
        BigDecimal[] merma = ceros(n);
        BigDecimal[] otras = ceros(n);
        // Por producto: saldo inicial, entradas, vendidas, merma, otras salidas
        Map<Long, BigDecimal[]> porProducto = new LinkedHashMap<>();
        Map<Long, String> nombres = new LinkedHashMap<>();

        jdbc.query("""
                SELECT m.producto_id, pr.nombre,
                       sum(CASE WHEN m.tipo = 'ENTRADA' THEN m.cantidad ELSE -m.cantidad END) AS saldo
                FROM movimiento_inventario m
                JOIN producto pr ON pr.id = m.producto_id
                WHERE pr.productor_id = :productor AND %s
                  AND (m.registrado_en AT TIME ZONE 'America/Lima')::date < :desde
                """.formatted(MOVIMIENTOS_DE_EXISTENCIAS) + c.filtroProducto("m.producto_id") + """

                GROUP BY m.producto_id, pr.nombre""", c.parametros(), rs -> {
            long id = rs.getLong("producto_id");
            nombres.put(id, rs.getString("nombre"));
            fila(porProducto, id)[0] = rs.getBigDecimal("saldo");
        });

        jdbc.query("""
                SELECT (m.registrado_en AT TIME ZONE 'America/Lima')::date AS fecha, m.producto_id, pr.nombre,
                       CASE WHEN m.tipo = 'SALIDA_VENTA' THEN 'VENTA_' || coalesce(i.tarifa, 'MINORISTA') ELSE m.tipo END AS tipo,
                       sum(m.cantidad) AS cantidad
                FROM movimiento_inventario m
                JOIN producto pr ON pr.id = m.producto_id
                LEFT JOIN item_pedido i ON i.id = m.item_pedido_id
                WHERE pr.productor_id = :productor AND %s
                  AND (m.registrado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta
                """.formatted(MOVIMIENTOS_DE_EXISTENCIAS) + c.filtroProducto("m.producto_id") + """

                GROUP BY 1, m.producto_id, pr.nombre, 4""", c.parametros(), rs -> {
            int i = tramos.posicion(rs.getObject("fecha", LocalDate.class));
            BigDecimal cantidad = rs.getBigDecimal("cantidad");
            long id = rs.getLong("producto_id");
            nombres.put(id, rs.getString("nombre"));
            BigDecimal[] f = fila(porProducto, id);
            switch (rs.getString("tipo")) {
                case "ENTRADA" -> {
                    entradas[i] = entradas[i].add(cantidad);
                    f[1] = f[1].add(cantidad);
                }
                case "VENTA_MAYORISTA" -> {
                    b2b[i] = b2b[i].add(cantidad);
                    f[2] = f[2].add(cantidad);
                }
                case "VENTA_MINORISTA" -> {
                    b2c[i] = b2c[i].add(cantidad);
                    f[2] = f[2].add(cantidad);
                }
                case "MERMA" -> {
                    merma[i] = merma[i].add(cantidad);
                    f[3] = f[3].add(cantidad);
                }
                default -> {
                    otras[i] = otras[i].add(cantidad);
                    f[4] = f[4].add(cantidad);
                }
            }
        });

        BigDecimal saldoInicial = porProducto.values().stream().map(f -> f[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PeriodoInventario> periodos = new ArrayList<>();
        BigDecimal saldo = saldoInicial;
        for (int i = 0; i < n; i++) {
            Tramos.Tramo t = tramos.lista().get(i);
            BigDecimal inicio = saldo;
            saldo = saldo.add(entradas[i]).subtract(b2c[i]).subtract(b2b[i]).subtract(merma[i]).subtract(otras[i]);
            periodos.add(new PeriodoInventario(t.inicio(), t.fin(), inicio, entradas[i], b2c[i], b2b[i], merma[i], otras[i], saldo));
        }
        TotalesInventario totales = new TotalesInventario(saldoInicial, suma(entradas), suma(b2c), suma(b2b), suma(merma),
                suma(otras), saldo);
        List<ProductoExistencias> productos = porProducto.entrySet().stream()
                .map(e -> {
                    BigDecimal[] f = e.getValue();
                    BigDecimal fin = f[0].add(f[1]).subtract(f[2]).subtract(f[3]).subtract(f[4]);
                    return new ProductoExistencias(e.getKey(), nombres.get(e.getKey()), f[0], f[1], f[2], f[3], f[4], fin);
                })
                // Un producto sin existencias ni movimientos en el rango no aporta nada al reporte
                .filter(p -> p.saldoInicial().signum() != 0 || p.saldoFinal().signum() != 0 || p.entradas().signum() != 0
                        || p.vendidas().signum() != 0 || p.merma().signum() != 0 || p.otrasSalidas().signum() != 0)
                .sorted(Comparator.comparing(ProductoExistencias::producto).thenComparing(ProductoExistencias::productoId))
                .toList();
        return new ReporteInventario(c.desde(), c.hasta(), c.agrupacion(), c.producto(), totales, periodos, productos,
                productos(productorId));
    }

    private static BigDecimal[] fila(Map<Long, BigDecimal[]> porProducto, long productoId) {
        return porProducto.computeIfAbsent(productoId, id -> ceros(5));
    }

    /**
     * CCPIP-115 y CCPIP-335: el reporte de sus productos para exportar, con las mismas cifras que la pantalla
     * para los mismos filtros (la modalidad solo filtra las ventas), en el idioma pedido.
     */
    @Transactional(readOnly = true)
    DocumentoReporte documento(long productorId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long producto,
                               Modalidad modalidad, Idioma idioma) {
        ReporteVentas ventas = ventas(productorId, desde, hasta, agrupacion, producto, modalidad);
        // Mismo rango resuelto para las tres secciones, aunque se haya pedido sin fechas
        ReporteProduccion produccion = produccion(productorId, ventas.desde(), ventas.hasta(), ventas.agrupacion(), producto);
        ReporteInventario inventario = inventario(productorId, ventas.desde(), ventas.hasta(), ventas.agrupacion(), producto);
        String nombre = jdbc.queryForObject("SELECT coalesce(nombre_tienda, comunidad) FROM productor WHERE id = :productor",
                new MapSqlParameterSource("productor", productorId), String.class);
        String nombreProducto = producto == null ? null : ventas.productos().stream()
                .filter(o -> o.id() == producto).map(ProductoOpcion::nombre).findFirst().orElse(null);
        return ContenidoReporte.documento(nombre, ventas, produccion, inventario, nombreProducto, idioma);
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
