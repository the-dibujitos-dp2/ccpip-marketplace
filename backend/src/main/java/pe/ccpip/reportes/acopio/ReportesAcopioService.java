package pe.ccpip.reportes.acopio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.comun.RangoFechas;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.exportar.DocumentoReporte;
import pe.ccpip.reportes.Tramos;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.Opcion;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoInventario;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoSalidas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoVentas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoInventario;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoSalidas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoVentas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteInventario;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteSalidas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteVentas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.TotalesEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.TotalesInventario;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.TotalesSalidas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.TotalesVentas;

/**
 * Reportes del punto de acopio por periodo. Todo se limita en el servidor a los puntos que el usuario
 * administra (RNF-05); si administra varios, se suman. Sin fechas, el año en curso hasta hoy (fecha de Lima);
 * sin agrupacion, por mes; como maximo un año por consulta, igual que los demas reportes.
 */
@Service
public class ReportesAcopioService {

    /** Los puntos de acopio del usuario :usuario. */
    static final String SUS_PUNTOS = """
            (SELECT uu.ubicacion_id FROM usuario_ubicacion uu JOIN ubicacion u ON u.id = uu.ubicacion_id
             WHERE uu.usuario_id = :usuario AND u.tipo = 'PUNTO_ACOPIO')""";

    /**
     * Estados de un pedido pagado que aun no se entrega: su salida por venta ya se registro (al confirmarse
     * la compra, CCPIP-62), pero las unidades siguen fisicamente en el punto.
     */
    static final String POR_ENTREGAR = "('CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'LISTO_PARA_DESPACHO', 'EN_CAMINO')";

    /** Nombre del productor {@code po}: su tienda o, si aun no tiene, su comunidad. */
    static final String NOMBRE_PRODUCTOR = "coalesce(po.nombre_tienda, po.comunidad)";

    /** Fecha de Lima de un instante. */
    private static final String FECHA = "(%s AT TIME ZONE 'America/Lima')::date";

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;

    public ReportesAcopioService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
    }

    /** Los filtros comunes, ya resueltos y validados. */
    record Consulta(long usuarioId, String puntos, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                    Long productor, Long producto) {

        MapSqlParameterSource parametros() {
            MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId)
                    .addValue("desde", desde).addValue("hasta", hasta);
            if (productor != null) {
                p.addValue("productor", productor);
            }
            if (producto != null) {
                p.addValue("producto", producto);
            }
            return p;
        }

        /** Condiciones de los filtros sobre la tabla de productos {@code pr}. */
        String filtros() {
            return (productor == null ? "" : " AND pr.productor_id = :productor")
                    + (producto == null ? "" : " AND pr.id = :producto");
        }
    }

    Consulta consulta(long usuarioId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Long productor,
                      Long producto) {
        String nombres = puntos.nombres(usuarioId);
        LocalDate hoy = jdbc.getJdbcTemplate()
                .queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
        RangoFechas rango = RangoFechas.resolver(desde, hasta, hoy, fin -> fin.withDayOfYear(1));
        // Un productor o un producto que nunca paso por sus puntos no existe para el (RNF-05)
        if (productor != null && productores(usuarioId).stream().noneMatch(o -> o.id() == productor)) {
            throw ErrorNegocio.noEncontrado("PRODUCTOR_NO_ENCONTRADO", "El productor no existe.");
        }
        if (producto != null && productos(usuarioId).stream().noneMatch(o -> o.id() == producto)) {
            throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe.");
        }
        return new Consulta(usuarioId, nombres, rango.desde(), rango.hasta(),
                agrupacion == null ? Agrupacion.MES : agrupacion, productor, producto);
    }

    /**
     * CCPIP-117 (RF-65): lo que entro a sus puntos, por la fecha en que se registro: recepcion directa (el
     * registro de un lote en el punto o una entrada de recepcion) y transferencias recibidas. Lo no atendido
     * son las transferencias hacia sus puntos pedidas en el rango que aun no se reciben.
     */
    @Transactional(readOnly = true)
    public ReporteEntradas entradas(long usuarioId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                                    Long productor, Long producto) {
        Consulta c = consulta(usuarioId, desde, hasta, agrupacion, productor, producto);
        Tramos tramos = new Tramos(c.desde(), c.hasta(), c.agrupacion());
        int n = tramos.cantidad();
        BigDecimal[] recepcion = ceros(n);
        BigDecimal[] transferencias = ceros(n);
        Map<Long, ProductoEntradas> porProducto = new LinkedHashMap<>();

        jdbc.query("""
                SELECT %s AS fecha, pr.id AS producto_id, pr.nombre AS producto, %s AS productor, m.tipo,
                       sum(m.cantidad) AS cantidad
                FROM movimiento_inventario m
                JOIN producto pr ON pr.id = m.producto_id
                JOIN productor po ON po.id = pr.productor_id
                WHERE m.ubicacion_id IN %s AND m.tipo IN ('ENTRADA', 'TRANSFERENCIA_ENTRADA')
                  AND %s BETWEEN :desde AND :hasta
                """.formatted(FECHA.formatted("m.registrado_en"), NOMBRE_PRODUCTOR, SUS_PUNTOS,
                FECHA.formatted("m.registrado_en")) + c.filtros() + """

                GROUP BY 1, pr.id, pr.nombre, 4, m.tipo""", c.parametros(), rs -> {
            int i = tramos.posicion(rs.getObject("fecha", LocalDate.class));
            BigDecimal cantidad = rs.getBigDecimal("cantidad");
            boolean recibida = "ENTRADA".equals(rs.getString("tipo"));
            if (recibida) {
                recepcion[i] = recepcion[i].add(cantidad);
            } else {
                transferencias[i] = transferencias[i].add(cantidad);
            }
            long id = rs.getLong("producto_id");
            ProductoEntradas previo = porProducto.getOrDefault(id, new ProductoEntradas(id, rs.getString("producto"),
                    rs.getString("productor"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal rec = recibida ? previo.recepcion().add(cantidad) : previo.recepcion();
            BigDecimal tra = recibida ? previo.transferencias() : previo.transferencias().add(cantidad);
            porProducto.put(id, new ProductoEntradas(id, previo.producto(), previo.productor(), rec, tra, rec.add(tra)));
        });

        Map<String, Object> pendientes = jdbc.queryForMap("""
                SELECT count(DISTINCT t.id) AS transferencias, coalesce(sum(ti.cantidad), 0) AS unidades
                FROM transferencia t
                JOIN transferencia_item ti ON ti.transferencia_id = t.id
                JOIN producto pr ON pr.id = ti.producto_id
                WHERE t.destino_id IN %s AND t.estado IN ('SOLICITADA', 'PREPARADA', 'DESPACHADA')
                  AND %s BETWEEN :desde AND :hasta""".formatted(SUS_PUNTOS, FECHA.formatted("t.creada_en"))
                + c.filtros(), c.parametros());

        List<PeriodoEntradas> periodos = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            Tramos.Tramo t = tramos.lista().get(i);
            BigDecimal total = recepcion[i].add(transferencias[i]);
            acumulado = acumulado.add(total);
            periodos.add(new PeriodoEntradas(t.inicio(), t.fin(), recepcion[i], transferencias[i], total, acumulado));
        }
        TotalesEntradas totales = new TotalesEntradas(suma(recepcion), suma(transferencias), acumulado,
                ((Number) pendientes.get("transferencias")).longValue(), (BigDecimal) pendientes.get("unidades"));
        List<ProductoEntradas> productos = porProducto.values().stream()
                .sorted(Comparator.comparing(ProductoEntradas::total).reversed().thenComparing(ProductoEntradas::producto))
                .toList();
        return new ReporteEntradas(c.puntos(), c.desde(), c.hasta(), c.agrupacion(), c.productor(), c.producto(),
                totales, periodos, productos, productores(usuarioId), productos(usuarioId));
    }

    /**
     * CCPIP-341 (RF-231): lo que salio de sus puntos, por la fecha en que se registro cada salida. Las ventas
     * se separan en atendidas (pedido entregado o cerrado) y no atendidas (pagado y aun por entregar, segun el
     * estado actual del pedido); ademas, transferencias enviadas, devoluciones al productor y merma.
     */
    @Transactional(readOnly = true)
    public ReporteSalidas salidas(long usuarioId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                                  Long productor, Long producto) {
        Consulta c = consulta(usuarioId, desde, hasta, agrupacion, productor, producto);
        Tramos tramos = new Tramos(c.desde(), c.hasta(), c.agrupacion());
        int n = tramos.cantidad();
        // entregadas, por entregar, transferencias, devoluciones, merma
        BigDecimal[][] columnas = new BigDecimal[5][];
        for (int k = 0; k < columnas.length; k++) {
            columnas[k] = ceros(n);
        }
        Map<Long, BigDecimal[]> porProducto = new LinkedHashMap<>();
        Map<Long, String[]> nombres = new LinkedHashMap<>();

        jdbc.query("""
                SELECT %s AS fecha, pr.id AS producto_id, pr.nombre AS producto, %s AS productor,
                       CASE m.tipo
                           WHEN 'SALIDA_VENTA' THEN CASE WHEN pe.estado IN %s THEN 1 ELSE 0 END
                           WHEN 'TRANSFERENCIA_SALIDA' THEN 2
                           WHEN 'SALIDA' THEN 3
                           ELSE 4
                       END AS columna,
                       sum(m.cantidad) AS cantidad
                FROM movimiento_inventario m
                JOIN producto pr ON pr.id = m.producto_id
                JOIN productor po ON po.id = pr.productor_id
                LEFT JOIN pedido pe ON pe.id = m.pedido_id
                WHERE m.ubicacion_id IN %s AND m.tipo IN ('SALIDA_VENTA', 'TRANSFERENCIA_SALIDA', 'SALIDA', 'MERMA')
                  AND %s BETWEEN :desde AND :hasta
                """.formatted(FECHA.formatted("m.registrado_en"), NOMBRE_PRODUCTOR, POR_ENTREGAR, SUS_PUNTOS,
                FECHA.formatted("m.registrado_en")) + c.filtros() + """

                GROUP BY 1, pr.id, pr.nombre, 4, 5""", c.parametros(), rs -> {
            int i = tramos.posicion(rs.getObject("fecha", LocalDate.class));
            int k = rs.getInt("columna");
            BigDecimal cantidad = rs.getBigDecimal("cantidad");
            columnas[k][i] = columnas[k][i].add(cantidad);
            long id = rs.getLong("producto_id");
            nombres.putIfAbsent(id, new String[] {rs.getString("producto"), rs.getString("productor")});
            BigDecimal[] f = porProducto.computeIfAbsent(id, x -> ceros(4));
            // Por producto, las ventas van juntas: atendidas y no atendidas
            int col = Math.max(0, k - 1);
            f[col] = f[col].add(cantidad);
        });

        List<PeriodoSalidas> periodos = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            Tramos.Tramo t = tramos.lista().get(i);
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal[] columna : columnas) {
                total = total.add(columna[i]);
            }
            acumulado = acumulado.add(total);
            periodos.add(new PeriodoSalidas(t.inicio(), t.fin(), columnas[0][i], columnas[1][i], columnas[2][i],
                    columnas[3][i], columnas[4][i], total, acumulado));
        }
        TotalesSalidas totales = new TotalesSalidas(suma(columnas[0]), suma(columnas[1]), suma(columnas[2]),
                suma(columnas[3]), suma(columnas[4]), acumulado);
        List<ProductoSalidas> productos = porProducto.entrySet().stream()
                .map(e -> {
                    BigDecimal[] f = e.getValue();
                    String[] nombre = nombres.get(e.getKey());
                    return new ProductoSalidas(e.getKey(), nombre[0], nombre[1], f[0], f[1], f[2], f[3], suma(f));
                })
                .sorted(Comparator.comparing(ProductoSalidas::total).reversed().thenComparing(ProductoSalidas::producto))
                .toList();
        return new ReporteSalidas(c.puntos(), c.desde(), c.hasta(), c.agrupacion(), c.productor(), c.producto(),
                totales, periodos, productos, productores(usuarioId), productos(usuarioId));
    }

    /**
     * CCPIP-342 (RF-232): lo vendido desde sus puntos (el punto de origen del pedido), por la fecha en que se
     * confirmo cada compra, en pedidos pagados y no cancelados, como los demas reportes de ventas. Cuenta los
     * pedidos atendidos (entregados) y los no atendidos (aun por entregar) segun su estado actual; con un
     * filtro de productor o producto, solo los pedidos que lo incluyen.
     */
    @Transactional(readOnly = true)
    public ReporteVentas ventas(long usuarioId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                                Long productor, Long producto) {
        Consulta c = consulta(usuarioId, desde, hasta, agrupacion, productor, producto);
        Tramos tramos = new Tramos(c.desde(), c.hasta(), c.agrupacion());
        int n = tramos.cantidad();
        BigDecimal[] unidades = ceros(n);
        BigDecimal[] b2c = ceros(n);
        BigDecimal[] b2b = ceros(n);
        long[] atendidos = new long[n];
        long[] porEntregar = new long[n];
        Map<Long, ProductoVentas> porProducto = new LinkedHashMap<>();
        String desdeSusPuntos = """
                FROM item_pedido i
                JOIN pedido pe ON pe.id = i.pedido_id
                JOIN producto pr ON pr.id = i.producto_id
                JOIN productor po ON po.id = pr.productor_id
                WHERE pe.ubicacion_origen_id IN %s
                  AND pe.confirmado_en IS NOT NULL AND pe.estado <> 'CANCELADO'
                  AND %s BETWEEN :desde AND :hasta
                """.formatted(SUS_PUNTOS, FECHA.formatted("pe.confirmado_en")) + c.filtros();

        jdbc.query("SELECT " + FECHA.formatted("pe.confirmado_en") + " AS fecha, pr.id AS producto_id, pr.nombre AS producto, "
                + NOMBRE_PRODUCTOR + " AS productor, i.tarifa, sum(i.cantidad) AS unidades, sum(i.subtotal) AS monto "
                + desdeSusPuntos + " GROUP BY 1, pr.id, pr.nombre, 4, i.tarifa", c.parametros(), rs -> {
            int i = tramos.posicion(rs.getObject("fecha", LocalDate.class));
            BigDecimal u = rs.getBigDecimal("unidades");
            BigDecimal monto = rs.getBigDecimal("monto");
            unidades[i] = unidades[i].add(u);
            if ("MAYORISTA".equals(rs.getString("tarifa"))) {
                b2b[i] = b2b[i].add(monto);
            } else {
                b2c[i] = b2c[i].add(monto);
            }
            long id = rs.getLong("producto_id");
            ProductoVentas previo = porProducto.getOrDefault(id, new ProductoVentas(id, rs.getString("producto"),
                    rs.getString("productor"), BigDecimal.ZERO, BigDecimal.ZERO));
            porProducto.put(id, new ProductoVentas(id, previo.producto(), previo.productor(), previo.unidades().add(u),
                    previo.total().add(monto)));
        });
        jdbc.query("SELECT " + FECHA.formatted("pe.confirmado_en") + " AS fecha, pe.estado IN " + POR_ENTREGAR
                + " AS pendiente, count(DISTINCT pe.id) AS pedidos " + desdeSusPuntos + " GROUP BY 1, 2", c.parametros(), rs -> {
            int i = tramos.posicion(rs.getObject("fecha", LocalDate.class));
            if (rs.getBoolean("pendiente")) {
                porEntregar[i] += rs.getLong("pedidos");
            } else {
                atendidos[i] += rs.getLong("pedidos");
            }
        });

        List<PeriodoVentas> periodos = new ArrayList<>();
        BigDecimal acumulado = BigDecimal.ZERO;
        long totalAtendidos = 0;
        long totalPorEntregar = 0;
        for (int i = 0; i < n; i++) {
            Tramos.Tramo t = tramos.lista().get(i);
            BigDecimal total = b2c[i].add(b2b[i]);
            acumulado = acumulado.add(total);
            totalAtendidos += atendidos[i];
            totalPorEntregar += porEntregar[i];
            periodos.add(new PeriodoVentas(t.inicio(), t.fin(), atendidos[i] + porEntregar[i], atendidos[i], porEntregar[i],
                    unidades[i], b2c[i], b2b[i], total, acumulado));
        }
        TotalesVentas totales = new TotalesVentas(totalAtendidos + totalPorEntregar, totalAtendidos, totalPorEntregar,
                suma(unidades), suma(b2c), suma(b2b), acumulado);
        List<ProductoVentas> productos = porProducto.values().stream()
                .sorted(Comparator.comparing(ProductoVentas::total).reversed().thenComparing(ProductoVentas::producto))
                .toList();
        return new ReporteVentas(c.puntos(), c.desde(), c.hasta(), c.agrupacion(), c.productor(), c.producto(),
                totales, periodos, productos, productores(usuarioId), productos(usuarioId));
    }

    /** Movimiento con signo: lo que entra al punto suma y lo que sale resta (sin ajustes, que ya no se usan). */
    private static final String CON_SIGNO = """
            CASE WHEN m.tipo IN ('ENTRADA', 'TRANSFERENCIA_ENTRADA') THEN m.cantidad ELSE -m.cantidad END""";

    private static final String MOVIMIENTOS_DEL_PUNTO =
            "m.tipo IN ('ENTRADA', 'TRANSFERENCIA_ENTRADA', 'SALIDA', 'SALIDA_VENTA', 'TRANSFERENCIA_SALIDA', 'MERMA')";

    /**
     * CCPIP-343 (RF-233): cuanto hubo en sus puntos y por que cambio, periodo a periodo, desde los movimientos
     * de sus lotes (RNF-15, ADR-10): cada saldo queda explicado por sus entradas y salidas. Como no atendido,
     * las unidades vendidas que siguen en el punto esperando entrega.
     */
    @Transactional(readOnly = true)
    public ReporteInventario inventario(long usuarioId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                                        Long productor, Long producto) {
        Consulta c = consulta(usuarioId, desde, hasta, agrupacion, productor, producto);
        Tramos tramos = new Tramos(c.desde(), c.hasta(), c.agrupacion());
        int n = tramos.cantidad();
        BigDecimal[] entradas = ceros(n);
        BigDecimal[] salidas = ceros(n);
        // Por producto: saldo inicial, entradas, salidas, por entregar
        Map<Long, BigDecimal[]> porProducto = new LinkedHashMap<>();
        Map<Long, String[]> nombres = new LinkedHashMap<>();
        String base = """
                FROM movimiento_inventario m
                JOIN producto pr ON pr.id = m.producto_id
                JOIN productor po ON po.id = pr.productor_id
                WHERE m.ubicacion_id IN %s AND %s
                """.formatted(SUS_PUNTOS, MOVIMIENTOS_DEL_PUNTO) + c.filtros();
        String fecha = FECHA.formatted("m.registrado_en");

        jdbc.query("SELECT pr.id AS producto_id, pr.nombre AS producto, " + NOMBRE_PRODUCTOR + " AS productor, sum("
                + CON_SIGNO + ") AS saldo " + base + " AND " + fecha + " < :desde GROUP BY pr.id, pr.nombre, 3",
                c.parametros(), rs -> {
                    fila(porProducto, nombres, rs.getLong("producto_id"), rs.getString("producto"),
                            rs.getString("productor"))[0] = rs.getBigDecimal("saldo");
                });

        jdbc.query("SELECT " + fecha + " AS fecha, pr.id AS producto_id, pr.nombre AS producto, " + NOMBRE_PRODUCTOR
                + " AS productor, m.tipo IN ('ENTRADA', 'TRANSFERENCIA_ENTRADA') AS entra, sum(m.cantidad) AS cantidad "
                + base + " AND " + fecha + " BETWEEN :desde AND :hasta GROUP BY 1, pr.id, pr.nombre, 4, 5",
                c.parametros(), rs -> {
            int i = tramos.posicion(rs.getObject("fecha", LocalDate.class));
            BigDecimal cantidad = rs.getBigDecimal("cantidad");
            BigDecimal[] f = fila(porProducto, nombres, rs.getLong("producto_id"), rs.getString("producto"),
                    rs.getString("productor"));
            if (rs.getBoolean("entra")) {
                entradas[i] = entradas[i].add(cantidad);
                f[1] = f[1].add(cantidad);
            } else {
                salidas[i] = salidas[i].add(cantidad);
                f[2] = f[2].add(cantidad);
            }
        });

        jdbc.query("SELECT pr.id AS producto_id, pr.nombre AS producto, " + NOMBRE_PRODUCTOR
                + " AS productor, sum(m.cantidad) AS cantidad " + base.replace(MOVIMIENTOS_DEL_PUNTO, "m.tipo = 'SALIDA_VENTA'")
                + " AND " + fecha + " <= :hasta AND EXISTS (SELECT 1 FROM pedido pe WHERE pe.id = m.pedido_id AND pe.estado IN "
                + POR_ENTREGAR + ") GROUP BY pr.id, pr.nombre, 3", c.parametros(),
                rs -> {
                    fila(porProducto, nombres, rs.getLong("producto_id"), rs.getString("producto"),
                            rs.getString("productor"))[3] = rs.getBigDecimal("cantidad");
                });

        BigDecimal saldoInicial = porProducto.values().stream().map(f -> f[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PeriodoInventario> periodos = new ArrayList<>();
        BigDecimal saldo = saldoInicial;
        for (int i = 0; i < n; i++) {
            Tramos.Tramo t = tramos.lista().get(i);
            BigDecimal inicio = saldo;
            saldo = saldo.add(entradas[i]).subtract(salidas[i]);
            periodos.add(new PeriodoInventario(t.inicio(), t.fin(), inicio, entradas[i], salidas[i], saldo));
        }
        BigDecimal porEntregar = porProducto.values().stream().map(f -> f[3]).reduce(BigDecimal.ZERO, BigDecimal::add);
        TotalesInventario totales = new TotalesInventario(saldoInicial, suma(entradas), suma(salidas), saldo, porEntregar,
                saldo.add(porEntregar));
        List<ProductoInventario> productos = porProducto.entrySet().stream()
                .map(e -> {
                    BigDecimal[] f = e.getValue();
                    String[] nombre = nombres.get(e.getKey());
                    return new ProductoInventario(e.getKey(), nombre[0], nombre[1], f[0], f[1], f[2],
                            f[0].add(f[1]).subtract(f[2]), f[3]);
                })
                // Un producto sin existencias ni movimientos en el rango no aporta nada al reporte
                .filter(p -> p.saldoInicial().signum() != 0 || p.entradas().signum() != 0 || p.salidas().signum() != 0
                        || p.porEntregar().signum() != 0)
                .sorted(Comparator.comparing(ProductoInventario::producto).thenComparing(ProductoInventario::productoId))
                .toList();
        return new ReporteInventario(c.puntos(), c.desde(), c.hasta(), c.agrupacion(), c.productor(), c.producto(),
                totales, periodos, productos, productores(usuarioId), productos(usuarioId));
    }

    /**
     * CCPIP-325, CCPIP-378, CCPIP-380, CCPIP-382 (y su Excel): los cuatro reportes juntos con los mismos
     * filtros, listos para exportar. El rango se resuelve una vez, aunque se haya pedido sin fechas.
     */
    @Transactional(readOnly = true)
    public DocumentoReporte documento(long usuarioId, LocalDate desde, LocalDate hasta, Agrupacion agrupacion,
                                      Long productor, Long producto, Idioma idioma) {
        ReporteEntradas entradas = entradas(usuarioId, desde, hasta, agrupacion, productor, producto);
        LocalDate d = entradas.desde();
        LocalDate h = entradas.hasta();
        Agrupacion a = entradas.agrupacion();
        ReporteSalidas salidas = salidas(usuarioId, d, h, a, productor, producto);
        ReporteVentas ventas = ventas(usuarioId, d, h, a, productor, producto);
        ReporteInventario inventario = inventario(usuarioId, d, h, a, productor, producto);
        return ContenidoReporteAcopio.documento(entradas, salidas, ventas, inventario, nombre(entradas.productores(), productor),
                nombre(entradas.productos(), producto), idioma == Idioma.EN);
    }

    private static String nombre(List<Opcion> opciones, Long id) {
        return id == null ? null : opciones.stream().filter(o -> o.id() == id).map(Opcion::nombre).findFirst().orElse(null);
    }

    private static BigDecimal[] fila(Map<Long, BigDecimal[]> porProducto, Map<Long, String[]> nombres, long id,
                                     String producto, String productor) {
        nombres.putIfAbsent(id, new String[] {producto, productor});
        return porProducto.computeIfAbsent(id, x -> ceros(4));
    }

    /** Productores con algun lote en sus puntos, para el filtro. */
    List<Opcion> productores(long usuarioId) {
        return jdbc.query("""
                SELECT DISTINCT po.id, %s AS nombre
                FROM lote l JOIN producto pr ON pr.id = l.producto_id JOIN productor po ON po.id = pr.productor_id
                WHERE l.ubicacion_id IN %s ORDER BY nombre""".formatted(NOMBRE_PRODUCTOR, SUS_PUNTOS),
                new MapSqlParameterSource("usuario", usuarioId), (rs, row) -> new Opcion(rs.getLong("id"), rs.getString("nombre")));
    }

    /** Productos con algun lote en sus puntos, para el filtro. */
    List<Opcion> productos(long usuarioId) {
        return jdbc.query("""
                SELECT DISTINCT pr.id, pr.nombre
                FROM lote l JOIN producto pr ON pr.id = l.producto_id
                WHERE l.ubicacion_id IN %s ORDER BY pr.nombre, pr.id""".formatted(SUS_PUNTOS),
                new MapSqlParameterSource("usuario", usuarioId), (rs, row) -> new Opcion(rs.getLong("id"), rs.getString("nombre")));
    }

    static BigDecimal[] ceros(int n) {
        BigDecimal[] arreglo = new BigDecimal[n];
        Arrays.fill(arreglo, BigDecimal.ZERO);
        return arreglo;
    }

    static BigDecimal suma(BigDecimal[] valores) {
        return Arrays.stream(valores).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
