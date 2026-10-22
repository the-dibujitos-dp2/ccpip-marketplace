package pe.ccpip.comision;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comision.ComisionModelos.ComisionesPorPedido;
import pe.ccpip.comision.ComisionModelos.ParteProductor;
import pe.ccpip.comision.ComisionModelos.PedidoComision;
import pe.ccpip.comision.ComisionModelos.Resumen;
import pe.ccpip.comun.RangoFechas;

/**
 * CCPIP-91 (RF-69): la Camara consulta la comision y el neto de cada pedido confirmado. Lee solo lo que
 * se copio al confirmar (RNF-13); no recalcula con el porcentaje de hoy. No incluye datos del comprador.
 */
@Service
public class ComisionesPorPedidoService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ComisionService comision;

    public ComisionesPorPedidoService(NamedParameterJdbcTemplate jdbc, ComisionService comision) {
        this.jdbc = jdbc;
        this.comision = comision;
    }

    /** Pedidos confirmados (y no cancelados) en el rango; sin fechas, el mes en curso hasta hoy. */
    @Transactional(readOnly = true)
    public ComisionesPorPedido consultar(LocalDate desde, LocalDate hasta) {
        RangoFechas rango = RangoFechas.resolver(desde, hasta, comision.hoy(), RangoFechas.MES_EN_CURSO);
        LocalDate inicio = rango.desde();
        LocalDate fin = rango.hasta();

        MapSqlParameterSource p = new MapSqlParameterSource("desde", inicio).addValue("hasta", fin);
        String confirmados = """
                pe.confirmado_en IS NOT NULL AND pe.estado <> 'CANCELADO'
                AND (pe.confirmado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta""";

        Map<Long, List<ParteProductor>> partes = new HashMap<>();
        jdbc.query("""
                SELECT i.pedido_id, pr.id AS productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS productor,
                       sum(i.subtotal) AS venta, sum(i.comision_monto) AS comision, sum(i.neto) AS neto
                FROM item_pedido i
                JOIN pedido pe ON pe.id = i.pedido_id
                JOIN productor pr ON pr.id = i.productor_id
                WHERE %s
                GROUP BY i.pedido_id, pr.id, pr.nombre_tienda, pr.comunidad
                ORDER BY productor""".formatted(confirmados), p, rs -> {
            partes.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                    .add(new ParteProductor(rs.getLong("productor_id"), rs.getString("productor"),
                            rs.getBigDecimal("venta"), rs.getBigDecimal("comision"), rs.getBigDecimal("neto")));
        });

        List<PedidoComision> pedidos = jdbc.query("""
                SELECT pe.id, pe.numero, pe.estado, pe.confirmado_en, pe.total, pe.comision_porcentaje, pe.comision_monto
                FROM pedido pe
                WHERE %s
                ORDER BY pe.confirmado_en DESC, pe.id DESC""".formatted(confirmados), p, (rs, row) -> {
            long id = rs.getLong("id");
            BigDecimal venta = rs.getBigDecimal("total");
            BigDecimal monto = rs.getBigDecimal("comision_monto");
            Timestamp confirmado = rs.getTimestamp("confirmado_en");
            return new PedidoComision(id, rs.getString("numero"), rs.getString("estado"), confirmado.toInstant(), venta,
                    rs.getBigDecimal("comision_porcentaje"), monto, monto == null ? null : venta.subtract(monto),
                    partes.getOrDefault(id, List.of()));
        });
        return new ComisionesPorPedido(inicio, fin, resumen(pedidos), pedidos);
    }

    private static Resumen resumen(List<PedidoComision> pedidos) {
        BigDecimal ventas = pedidos.stream().map(PedidoComision::venta).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal comision = pedidos.stream().map(PedidoComision::comision).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal neto = pedidos.stream().map(PedidoComision::neto).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int sinComision = (int) pedidos.stream().filter(pe -> pe.comision() == null).count();
        return new Resumen(pedidos.size(), ventas, comision, neto, sinComision);
    }
}
