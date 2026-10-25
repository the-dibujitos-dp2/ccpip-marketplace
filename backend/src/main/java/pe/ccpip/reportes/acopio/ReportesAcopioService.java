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
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.comun.RangoFechas;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.Tramos;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.Opcion;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.PeriodoEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ProductoEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.TotalesEntradas;

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
