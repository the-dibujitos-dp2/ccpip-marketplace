package pe.ccpip.fidelizacion;

import static java.time.temporal.ChronoUnit.DAYS;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.fidelizacion.FidelizacionModelos.Campana;
import pe.ccpip.fidelizacion.FidelizacionModelos.Dia;
import pe.ccpip.fidelizacion.FidelizacionModelos.EstadoCampana;
import pe.ccpip.fidelizacion.FidelizacionModelos.Metricas;
import pe.ccpip.fidelizacion.FidelizacionModelos.ProductoTop;
import pe.ccpip.fidelizacion.FidelizacionModelos.UsoCodigos;
import pe.ccpip.reportes.gestion.GestionModelos.Indicador;

/**
 * CCPIP-86: como le va a una campana de puntos. Compara sus pedidos con los del periodo anterior de igual duracion
 * (con el mismo pedido minimo), para ver si movio las ventas, y proyecta los puntos que aun faltan acreditar. Solo
 * cifras agregadas: ningun comprador por su nombre.
 */
@Service
public class MetricasCampanaService {

    private static final int PRODUCTOS_TOP = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final CampanasService campanas;

    public MetricasCampanaService(NamedParameterJdbcTemplate jdbc, CampanasService campanas) {
        this.jdbc = jdbc;
        this.campanas = campanas;
    }

    /**
     * Un pedido confirmado del periodo. {@code total}: sus productos; {@code base}: lo pagado por ellos despues del
     * descuento, sobre lo que se ganan los puntos. {@code nuevo}: su comprador no habia comprado antes del periodo.
     */
    private record Pedido(long usuarioId, BigDecimal total, BigDecimal base, boolean entregado, LocalDate dia, boolean nuevo) {
    }

    /** Lo que suman unos pedidos: cuantos, cuanto, ticket promedio y compradores distintos. */
    private record Suma(BigDecimal pedidos, BigDecimal ventas, BigDecimal ticket, BigDecimal clientes) {
        static Suma de(List<Pedido> pedidos) {
            BigDecimal ventas = pedidos.stream().map(Pedido::total).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cantidad = BigDecimal.valueOf(pedidos.size());
            BigDecimal ticket = pedidos.isEmpty() ? BigDecimal.ZERO : ventas.divide(cantidad, 2, RoundingMode.HALF_UP);
            long clientes = pedidos.stream().map(Pedido::usuarioId).distinct().count();
            return new Suma(cantidad, ventas, ticket, BigDecimal.valueOf(clientes));
        }
    }

    @Transactional(readOnly = true)
    public Metricas metricas(long id) {
        Campana campana = campanas.campana(id);
        if (campana.estado() == EstadoCampana.PROGRAMADA || campana.estado() == EstadoCampana.CANCELADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CAMPANA_SIN_METRICAS", "Esta campaña no tiene pedidos todavía.");
        }
        LocalDate desde = campana.desde();
        LocalDate hoy = campanas.hoy();
        LocalDate hasta = campana.finEfectivo().isAfter(hoy) ? hoy : campana.finEfectivo();
        long dias = DAYS.between(desde, hasta) + 1;
        LocalDate anteriorDesde = desde.minusDays(dias);
        LocalDate anteriorHasta = desde.minusDays(1);

        List<Pedido> periodo = pedidos(desde, hasta, campana.finalizadoEn());
        List<Pedido> enCampana = periodo.stream().filter(p -> llega(p, campana)).toList();
        List<Pedido> anteriores = pedidos(anteriorDesde, anteriorHasta, null).stream().filter(p -> llega(p, campana)).toList();
        Suma actual = Suma.de(enCampana);
        Suma previa = Suma.de(anteriores);

        ReglaPuntos regla = campana.regla();
        List<Pedido> porEntregar = enCampana.stream().filter(p -> !p.entregado()).toList();
        Map<Long, Long> pedidosPorCliente = enCampana.stream().collect(Collectors.groupingBy(Pedido::usuarioId, Collectors.counting()));
        int nuevos = (int) enCampana.stream().filter(Pedido::nuevo).map(Pedido::usuarioId).distinct().count();
        int recurrentes = (int) pedidosPorCliente.values().stream().filter(n -> n >= 2).count();

        return new Metricas(campana, desde, hasta, anteriorDesde, anteriorHasta,
                Indicador.comparar(actual.pedidos(), previa.pedidos()),
                Indicador.comparar(actual.ventas(), previa.ventas()),
                Indicador.comparar(actual.ticket(), previa.ticket()),
                Indicador.comparar(actual.clientes(), previa.clientes()),
                campana.resumen().puntosOtorgados(), puntos(porEntregar, regla),
                enCampana.size() - porEntregar.size(), porEntregar.size(), periodo.size() - enCampana.size(),
                nuevos, recurrentes, dias(enCampana, desde, hasta, regla), productos(id), codigos(id));
    }

    private static boolean llega(Pedido p, Campana c) {
        return p.base().compareTo(c.montoMinimo()) >= 0;
    }

    /** Sin el multiplicador de cada nivel: es una estimacion de lo que se acreditara. */
    private static long puntos(List<Pedido> pedidos, ReglaPuntos regla) {
        return pedidos.stream().mapToLong(p -> regla.puntosDe(p.base())).sum();
    }

    /** Cuantas compras pagadas usaron los codigos de la campana y cuanto descontaron (lo asume la Camara). */
    private UsoCodigos codigos(long campanaId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS usos, coalesce(sum(pe.descuento_monto), 0) AS descuento
                FROM pedido pe JOIN codigo_descuento d ON d.id = pe.codigo_descuento_id
                WHERE d.campana_id = :campana AND pe.estado NOT IN ('CANCELADO', 'PENDIENTE_PAGO')""",
                new MapSqlParameterSource("campana", campanaId),
                (rs, row) -> new UsoCodigos(rs.getInt("usos"), rs.getBigDecimal("descuento")));
    }

    /** Cada dia del periodo, tambien los que no tuvieron pedidos. */
    private static List<Dia> dias(List<Pedido> pedidos, LocalDate desde, LocalDate hasta, ReglaPuntos regla) {
        Map<LocalDate, List<Pedido>> porDia = pedidos.stream().collect(Collectors.groupingBy(Pedido::dia));
        return desde.datesUntil(hasta.plusDays(1)).map(dia -> {
            List<Pedido> delDia = porDia.getOrDefault(dia, List.of());
            Suma suma = Suma.de(delDia);
            return new Dia(dia, delDia.size(), suma.ventas(), puntos(delDia, regla));
        }).toList();
    }

    /** Los pedidos confirmados (no cancelados) entre dos dias de Lima y antes de {@code corte}, si lo hay. */
    private List<Pedido> pedidos(LocalDate desde, LocalDate hasta, Instant corte) {
        String enPeriodo = CampanasService.enPeriodo(":desde", ":hasta", "CAST(:corte AS timestamptz)");
        return jdbc.query("""
                        SELECT p.usuario_id, p.total, p.total - p.descuento_monto AS base, p.estado = 'ENTREGADO' AS entregado,
                               (p.confirmado_en AT TIME ZONE 'America/Lima')::date AS dia,
                               NOT EXISTS (SELECT 1 FROM pedido q
                                           WHERE q.usuario_id = p.usuario_id AND q.estado <> 'CANCELADO'
                                             AND q.confirmado_en IS NOT NULL
                                             AND (q.confirmado_en AT TIME ZONE 'America/Lima')::date < :desde) AS nuevo
                        FROM pedido p
                        WHERE p.estado <> 'CANCELADO' AND %s""".formatted(enPeriodo),
                new MapSqlParameterSource("desde", desde).addValue("hasta", hasta)
                        .addValue("corte", corte == null ? null : Timestamp.from(corte)),
                (rs, row) -> new Pedido(rs.getLong("usuario_id"), rs.getBigDecimal("total"), rs.getBigDecimal("base"),
                        rs.getBoolean("entregado"),
                        rs.getObject("dia", LocalDate.class), rs.getBoolean("nuevo")));
    }

    /** Los productos mas vendidos en los pedidos de la campana. */
    private List<ProductoTop> productos(long campanaId) {
        return jdbc.query("""
                        SELECT pr.id, pr.nombre, coalesce(pd.nombre_tienda, pd.comunidad) AS comunidad,
                               sum(i.cantidad) AS unidades, sum(i.subtotal) AS ventas
                        FROM campana_puntos c
                        JOIN pedido p ON p.estado <> 'CANCELADO' AND p.total - p.descuento_monto >= c.monto_minimo AND %s
                        JOIN item_pedido i ON i.pedido_id = p.id
                        JOIN producto pr ON pr.id = i.producto_id
                        JOIN productor pd ON pd.id = i.productor_id
                        WHERE c.id = :campana
                        GROUP BY pr.id, pr.nombre, pd.nombre_tienda, pd.comunidad
                        ORDER BY ventas DESC, pr.id LIMIT %d""".formatted(CampanasService.EN_CAMPANA, PRODUCTOS_TOP),
                new MapSqlParameterSource("campana", campanaId),
                (rs, row) -> new ProductoTop(rs.getLong("id"), rs.getString("nombre"), rs.getString("comunidad"),
                        rs.getBigDecimal("unidades"), rs.getBigDecimal("ventas")));
    }
}
