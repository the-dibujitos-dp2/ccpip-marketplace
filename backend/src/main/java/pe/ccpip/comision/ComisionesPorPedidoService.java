package pe.ccpip.comision;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comision.ComisionModelos.ComisionesPorPedido;
import pe.ccpip.comision.ComisionModelos.OpcionProductor;
import pe.ccpip.comision.ComisionModelos.ParteProductor;
import pe.ccpip.comision.ComisionModelos.PedidoComision;
import pe.ccpip.comision.ComisionModelos.Resumen;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.RangoFechas;

/**
 * CCPIP-91 (RF-69): la Camara consulta la comision y el neto de cada pedido confirmado. Lee solo lo que
 * se copio al confirmar (RNF-13); no recalcula con el porcentaje de hoy. No incluye datos del comprador.
 *
 * <p>CCPIP-431: la lista se pagina en el servidor y se puede filtrar por productor y por estado. Los
 * totales se calculan sobre todo lo filtrado, no solo sobre la pagina que se muestra.
 */
@Service
public class ComisionesPorPedidoService {

    /** Pedidos por pagina, igual que las demas listas paginadas (merma, movimientos). */
    static final int TAMANO = 20;

    /** Estados que se pueden elegir; un pedido cancelado nunca entra en la consulta. */
    static final Set<String> ESTADOS = Set.of("CONFIRMADO", "EN_PREPARACION", "LISTO_PARA_RECOJO", "LISTO_PARA_DESPACHO", "EN_CAMINO", "ENTREGADO");

    private static final String EN_RANGO = """
            pe.confirmado_en IS NOT NULL AND pe.estado <> 'CANCELADO'
            AND (pe.confirmado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta""";

    private final NamedParameterJdbcTemplate jdbc;
    private final ComisionService comision;

    public ComisionesPorPedidoService(NamedParameterJdbcTemplate jdbc, ComisionService comision) {
        this.jdbc = jdbc;
        this.comision = comision;
    }

    /**
     * Pedidos confirmados (y no cancelados) en el rango; sin fechas, el mes en curso hasta hoy.
     * {@code productor}: solo los pedidos que incluyen algo suyo. {@code estado}: solo los que estan en ese estado.
     */
    @Transactional(readOnly = true)
    public ComisionesPorPedido consultar(LocalDate desde, LocalDate hasta, Long productor, String estado, int pagina) {
        if (pagina < 0) {
            throw ErrorNegocio.invalido("PAGINA_INVALIDA", "La página debe ser 0 o mayor.");
        }
        String estadoFiltro = estado == null || estado.isBlank() ? null : estado.trim().toUpperCase(Locale.ROOT);
        if (estadoFiltro != null && !ESTADOS.contains(estadoFiltro)) {
            throw ErrorNegocio.invalido("ESTADO_INVALIDO", "Elige confirmado, en preparación, listo para recojo o entregado.");
        }
        RangoFechas rango = RangoFechas.resolver(desde, hasta, comision.hoy(), RangoFechas.MES_EN_CURSO);
        LocalDate inicio = rango.desde();
        LocalDate fin = rango.hasta();

        MapSqlParameterSource p = new MapSqlParameterSource("desde", inicio).addValue("hasta", fin);
        StringBuilder donde = new StringBuilder(EN_RANGO);
        if (productor != null) {
            donde.append(" AND EXISTS (SELECT 1 FROM item_pedido ip WHERE ip.pedido_id = pe.id AND ip.productor_id = :productor)");
            p.addValue("productor", productor);
        }
        if (estadoFiltro != null) {
            donde.append(" AND pe.estado = :estado");
            p.addValue("estado", estadoFiltro);
        }

        Resumen resumen = resumen(donde.toString(), p);
        int totalPaginas = (int) ((resumen.pedidos() + TAMANO - 1) / TAMANO);

        p.addValue("limite", TAMANO).addValue("salto", (long) pagina * TAMANO);
        List<Fila> filas = jdbc.query("""
                SELECT pe.id, pe.numero, pe.estado, pe.confirmado_en, pe.total, pe.comision_porcentaje, pe.comision_monto
                FROM pedido pe
                WHERE %s
                ORDER BY pe.confirmado_en DESC, pe.id DESC
                LIMIT :limite OFFSET :salto""".formatted(donde), p, (rs, row) -> new Fila(rs.getLong("id"),
                rs.getString("numero"), rs.getString("estado"), rs.getTimestamp("confirmado_en"), rs.getBigDecimal("total"),
                rs.getBigDecimal("comision_porcentaje"), rs.getBigDecimal("comision_monto")));

        Map<Long, List<ParteProductor>> partes = partes(filas);
        List<PedidoComision> pedidos = filas.stream().map(f -> new PedidoComision(f.id(), f.numero(), f.estado(),
                f.confirmadoEn().toInstant(), f.venta(), f.porcentaje(), f.monto(),
                f.monto() == null ? null : f.venta().subtract(f.monto()), partes.getOrDefault(f.id(), List.of()))).toList();

        return new ComisionesPorPedido(inicio, fin, productor, estadoFiltro, resumen, pedidos, pagina, TAMANO,
                resumen.pedidos(), totalPaginas, productores(inicio, fin));
    }

    /** Totales de todo lo filtrado. El neto de un pedido sin comision copiada no se cuenta. */
    private Resumen resumen(String donde, MapSqlParameterSource p) {
        return jdbc.queryForObject("""
                SELECT count(*) AS pedidos,
                       coalesce(sum(pe.total), 0) AS ventas,
                       coalesce(sum(pe.comision_monto), 0) AS comision,
                       coalesce(sum(pe.total - pe.comision_monto) FILTER (WHERE pe.comision_monto IS NOT NULL), 0) AS neto,
                       count(*) FILTER (WHERE pe.comision_monto IS NULL) AS sin_comision
                FROM pedido pe
                WHERE %s""".formatted(donde), p, (rs, row) -> new Resumen(rs.getInt("pedidos"),
                rs.getBigDecimal("ventas"), rs.getBigDecimal("comision"), rs.getBigDecimal("neto"), rs.getInt("sin_comision")));
    }

    /** Lo de cada productor, solo para los pedidos de la pagina. */
    private Map<Long, List<ParteProductor>> partes(List<Fila> filas) {
        Map<Long, List<ParteProductor>> partes = new HashMap<>();
        if (filas.isEmpty()) {
            return partes;
        }
        jdbc.query("""
                SELECT i.pedido_id, pr.id AS productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS productor,
                       sum(i.subtotal) AS venta, sum(i.comision_monto) AS comision, sum(i.neto) AS neto
                FROM item_pedido i
                JOIN productor pr ON pr.id = i.productor_id
                WHERE i.pedido_id IN (:ids)
                GROUP BY i.pedido_id, pr.id, pr.nombre_tienda, pr.comunidad
                ORDER BY productor""", new MapSqlParameterSource("ids", filas.stream().map(Fila::id).toList()), rs -> {
            partes.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                    .add(new ParteProductor(rs.getLong("productor_id"), rs.getString("productor"),
                            rs.getBigDecimal("venta"), rs.getBigDecimal("comision"), rs.getBigDecimal("neto")));
        });
        return partes;
    }

    /** Productores con pedidos confirmados en el rango, sin aplicar los demas filtros, para elegir uno. */
    private List<OpcionProductor> productores(LocalDate desde, LocalDate hasta) {
        return jdbc.query("""
                SELECT DISTINCT pr.id, coalesce(pr.nombre_tienda, pr.comunidad) AS nombre
                FROM item_pedido i
                JOIN pedido pe ON pe.id = i.pedido_id
                JOIN productor pr ON pr.id = i.productor_id
                WHERE %s
                ORDER BY nombre, pr.id""".formatted(EN_RANGO),
                new MapSqlParameterSource("desde", desde).addValue("hasta", hasta),
                (rs, row) -> new OpcionProductor(rs.getLong("id"), rs.getString("nombre")));
    }

    private record Fila(long id, String numero, String estado, Timestamp confirmadoEn, BigDecimal venta,
                        BigDecimal porcentaje, BigDecimal monto) {
    }
}
