package pe.ccpip.reportes.gestion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.RangoFechas;
import pe.ccpip.reportes.ReportesModelos.Desglose;
import pe.ccpip.reportes.gestion.GestionModelos.Denuncias;
import pe.ccpip.reportes.gestion.GestionModelos.Estrellas;
import pe.ccpip.reportes.gestion.GestionModelos.Linea;
import pe.ccpip.reportes.gestion.GestionModelos.ProductoCalificado;
import pe.ccpip.reportes.gestion.GestionModelos.ProductorVentas;
import pe.ccpip.reportes.gestion.GestionModelos.PuntoReporte;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteCalificaciones;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteProductores;
import pe.ccpip.reportes.gestion.GestionModelos.ReportePuntos;
import pe.ccpip.reportes.gestion.GestionModelos.ResumenCalificaciones;
import pe.ccpip.reportes.gestion.GestionModelos.Tendencia;
import pe.ccpip.valoracion.MinimoCalificacionesService;

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

    /** Una denuncia {@code d} recibida en el rango (fecha de Lima). */
    static final String DENUNCIA_EN_RANGO = "(d.creada_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta";

    /** Una calificacion {@code c} del rango (fecha de Lima). */
    static final String CALIFICADA_EN_RANGO = "(c.calificado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta";

    /** Una calificacion {@code c} del rango o del periodo anterior de igual duracion. */
    static final String CALIFICADA_DESDE_ANTERIOR =
            "(c.calificado_en AT TIME ZONE 'America/Lima')::date BETWEEN :anteriorDesde AND :hasta";

    /** Calificaciones y suma de estrellas del rango y del periodo anterior ({@code :anteriorDesde} a {@code :desde}). */
    static final String DOS_PERIODOS = """
            count(*) FILTER (WHERE (c.calificado_en AT TIME ZONE 'America/Lima')::date >= :desde) AS resenas,
            coalesce(sum(c.estrellas) FILTER (WHERE (c.calificado_en AT TIME ZONE 'America/Lima')::date >= :desde), 0) AS suma,
            count(*) FILTER (WHERE (c.calificado_en AT TIME ZONE 'America/Lima')::date < :desde) AS resenas_anterior,
            coalesce(sum(c.estrellas) FILTER (WHERE (c.calificado_en AT TIME ZONE 'America/Lima')::date < :desde), 0)
                AS suma_anterior""";

    /** Diferencia de promedio (en estrellas) desde la que la tendencia sube o baja. */
    static final BigDecimal CAMBIO_TENDENCIA = new BigDecimal("0.3");

    /** Lo vendido de las lineas {@code i} separado en B2C (tarifa minorista) y B2B (mayorista). */
    static final String POR_TARIFA = """
            coalesce(sum(i.subtotal) FILTER (WHERE i.tarifa = 'MINORISTA'), 0) AS b2c,
            coalesce(sum(i.subtotal) FILTER (WHERE i.tarifa = 'MAYORISTA'), 0) AS b2b""";

    private final NamedParameterJdbcTemplate jdbc;
    private final MinimoCalificacionesService minimoCalificaciones;

    public ReportesGestionService(NamedParameterJdbcTemplate jdbc, MinimoCalificacionesService minimoCalificaciones) {
        this.jdbc = jdbc;
        this.minimoCalificaciones = minimoCalificaciones;
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
     * CCPIP-356 (RF-246): las denuncias recibidas en el rango sobre las ofertas que estan en cada punto.
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
        // CCPIP-356: una denuncia suma en cada punto donde esta la oferta denunciada
        Map<Long, long[]> denunciasPorPunto = new HashMap<>();
        jdbc.query("""
                SELECT l.ubicacion_id, count(DISTINCT d.id) AS total,
                       count(DISTINCT d.id) FILTER (WHERE d.estado IN ('RECIBIDA', 'EN_REVISION')) AS abiertas
                FROM denuncia d
                JOIN producto pr ON pr.id = d.producto_id OR (d.producto_id IS NULL AND pr.productor_id = d.productor_id)
                JOIN lote l ON l.producto_id = pr.id
                WHERE %s
                GROUP BY l.ubicacion_id""".formatted(DENUNCIA_EN_RANGO), parametros(rango), rs -> {
            denunciasPorPunto.put(rs.getLong("ubicacion_id"), new long[] {rs.getLong("total"), rs.getLong("abiertas")});
        });
        Denuncias denuncias = jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE d.estado IN ('RECIBIDA', 'EN_REVISION')) AS abiertas,
                       count(*) FILTER (WHERE d.estado = 'RESUELTA') AS resueltas,
                       count(*) FILTER (WHERE d.estado = 'DESESTIMADA') AS desestimadas
                FROM denuncia d WHERE %s""".formatted(DENUNCIA_EN_RANGO), parametros(rango),
                (rs, row) -> new Denuncias(rs.getLong("total"), rs.getLong("abiertas"), rs.getLong("resueltas"),
                        rs.getLong("desestimadas")));
        List<PuntoReporte> puntos = ventas.stream()
                .map(v -> {
                    long[] d = denunciasPorPunto.getOrDefault(v.id(), new long[2]);
                    return new PuntoReporte(v.id(), v.nombre(), v.departamento(), v.pedidos(), v.ventas().b2c(),
                            v.ventas().b2b(), v.ventas().total(), porcentaje(v.ventas().total(), totales.total()), d[0], d[1]);
                })
                .toList();
        return new ReportePuntos(rango.desde(), rango.hasta(), totales, ventas.stream().mapToLong(PuntoVentas::pedidos).sum(),
                denuncias, puntos);
    }

    private record PuntoVentas(long id, String nombre, String departamento, long pedidos, Desglose ventas) {
    }

    /**
     * CCPIP-123 (RF-87): las calificaciones del rango, en total y por producto. Un promedio solo se muestra con
     * el minimo de calificaciones que configura el superadministrador (CCPIP-82); la tendencia compara con el
     * periodo anterior de igual duracion. {@code linea} filtra por linea de producto (codigo).
     */
    @Transactional(readOnly = true)
    public ReporteCalificaciones calificaciones(LocalDate desde, LocalDate hasta, String linea) {
        RangoFechas rango = rango(desde, hasta);
        RangoFechas anterior = anterior(rango);
        int minimo = minimoCalificaciones.vigente().calificaciones();
        List<Linea> lineas = jdbc.query("SELECT codigo, nombre FROM linea_producto WHERE activo ORDER BY nombre",
                (rs, row) -> new Linea(rs.getString("codigo"), rs.getString("nombre")));
        String filtro = linea == null || linea.isBlank() ? null : linea.trim();
        if (filtro != null && lineas.stream().noneMatch(l -> l.codigo().equals(filtro))) {
            throw ErrorNegocio.noEncontrado("LINEA_NO_ENCONTRADA", "La línea de producto no existe.");
        }
        MapSqlParameterSource p = parametros(rango).addValue("anteriorDesde", anterior.desde()).addValue("linea", filtro);
        String deLaLinea = filtro == null ? "" : """
                 AND EXISTS (SELECT 1 FROM producto_linea pl JOIN linea_producto lp ON lp.id = pl.linea_id
                             WHERE pl.producto_id = c.producto_id AND lp.codigo = :linea)""";

        long[] porEstrellas = new long[6];
        jdbc.query("""
                SELECT c.estrellas, count(*) AS cantidad FROM calificacion c
                WHERE %s %s GROUP BY c.estrellas""".formatted(CALIFICADA_EN_RANGO, deLaLinea), p,
                rs -> {
                    porEstrellas[rs.getInt("estrellas")] = rs.getLong("cantidad");
                });
        long resenas = 0;
        long suma = 0;
        List<Estrellas> distribucion = new ArrayList<>();
        for (int e = 5; e >= 1; e--) {
            resenas += porEstrellas[e];
            suma += e * porEstrellas[e];
            distribucion.add(new Estrellas(e, porEstrellas[e]));
        }
        ResumenCalificaciones resumen = new ResumenCalificaciones(
                promedio(BigDecimal.valueOf(suma), resenas, minimo), resenas, distribucion);

        Map<Long, Long> denuncias = new HashMap<>();
        jdbc.query("""
                SELECT d.producto_id, count(*) AS denuncias FROM denuncia d
                WHERE d.producto_id IS NOT NULL AND %s GROUP BY d.producto_id""".formatted(DENUNCIA_EN_RANGO), p,
                rs -> {
                    denuncias.put(rs.getLong("producto_id"), rs.getLong("denuncias"));
                });
        List<ProductoCalificado> productos = jdbc.query("""
                SELECT c.producto_id, pr.nombre, coalesce(po.nombre_tienda, po.comunidad) AS productor, %s
                FROM calificacion c
                JOIN producto pr ON pr.id = c.producto_id
                JOIN productor po ON po.id = c.productor_id
                WHERE %s %s
                GROUP BY c.producto_id, pr.nombre, po.nombre_tienda, po.comunidad
                HAVING count(*) FILTER (WHERE %s) > 0""".formatted(DOS_PERIODOS, CALIFICADA_DESDE_ANTERIOR, deLaLinea,
                CALIFICADA_EN_RANGO), p,
                (rs, row) -> {
                    Calificado c = calificado(rs, minimo);
                    return new ProductoCalificado(rs.getLong("producto_id"), rs.getString("nombre"), rs.getString("productor"),
                            c.promedio(), c.resenas(), denuncias.getOrDefault(rs.getLong("producto_id"), 0L), c.tendencia());
                });
        List<ProductoCalificado> ordenados = productos.stream()
                .sorted(Comparator.comparing(ProductoCalificado::promedio, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ProductoCalificado::resenas, Comparator.reverseOrder())
                        .thenComparing(ProductoCalificado::producto))
                .toList();
        return new ReporteCalificaciones(rango.desde(), rango.hasta(), anterior.desde(), anterior.hasta(), minimo, filtro,
                resumen, ordenados, lineas);
    }

    /** Lo que se calcula igual para un producto o un productor: promedio, calificaciones y tendencia. */
    private record Calificado(BigDecimal promedio, long resenas, Tendencia tendencia) {
    }

    /** Lee las columnas de {@link #DOS_PERIODOS}. */
    private static Calificado calificado(java.sql.ResultSet rs, int minimo) throws java.sql.SQLException {
        long resenas = rs.getLong("resenas");
        long anteriores = rs.getLong("resenas_anterior");
        BigDecimal suma = rs.getBigDecimal("suma");
        BigDecimal sumaAnterior = rs.getBigDecimal("suma_anterior");
        Tendencia tendencia = null;
        if (resenas >= minimo && anteriores >= minimo) {
            BigDecimal diferencia = suma.divide(BigDecimal.valueOf(resenas), 4, RoundingMode.HALF_UP)
                    .subtract(sumaAnterior.divide(BigDecimal.valueOf(anteriores), 4, RoundingMode.HALF_UP));
            tendencia = diferencia.compareTo(CAMBIO_TENDENCIA) >= 0 ? Tendencia.SUBE
                    : diferencia.compareTo(CAMBIO_TENDENCIA.negate()) <= 0 ? Tendencia.BAJA : Tendencia.ESTABLE;
        }
        return new Calificado(promedio(suma, resenas, minimo), resenas, tendencia);
    }

    /** El promedio con un decimal, o nulo si no se alcanza el minimo de calificaciones (CCPIP-82). */
    static BigDecimal promedio(BigDecimal suma, long resenas, int minimo) {
        if (resenas == 0 || resenas < minimo) {
            return null;
        }
        return suma.divide(BigDecimal.valueOf(resenas), 1, RoundingMode.HALF_UP);
    }

    /** El periodo de igual duracion que termina el dia antes de {@code rango}. */
    static RangoFechas anterior(RangoFechas rango) {
        long dias = ChronoUnit.DAYS.between(rango.desde(), rango.hasta());
        LocalDate hasta = rango.desde().minusDays(1);
        return new RangoFechas(hasta.minusDays(dias), hasta);
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
