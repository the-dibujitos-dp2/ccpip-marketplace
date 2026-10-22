package pe.ccpip.liquidacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.RangoFechas;
import pe.ccpip.liquidacion.LiquidacionModelos.Linea;
import pe.ccpip.liquidacion.LiquidacionModelos.Liquidacion;
import pe.ccpip.liquidacion.LiquidacionModelos.PedidoLiquidado;
import pe.ccpip.liquidacion.LiquidacionModelos.Resumen;

/**
 * CCPIP-92 (RF-70): el productor consulta su liquidacion por periodo. Lee solo lo que se copio al
 * confirmar cada pedido (RNF-13) y solo sus lineas, filtradas por {@code item_pedido.productor_id} en el
 * servidor (RNF-05). Un pedido cancelado despues del pago se anulo y no se liquida.
 */
@Service
public class LiquidacionService {

    /** Lineas propias de pedidos confirmados en el periodo; {@code i} es la linea y {@code pe} su pedido. */
    private static final String SUS_LINEAS = """
            FROM item_pedido i
            JOIN pedido pe ON pe.id = i.pedido_id
            JOIN producto pr ON pr.id = i.producto_id
            WHERE i.productor_id = :productor
              AND pe.confirmado_en IS NOT NULL AND pe.estado <> 'CANCELADO'
              AND (pe.confirmado_en AT TIME ZONE 'America/Lima')::date BETWEEN :desde AND :hasta
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LiquidacionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Sin fechas, el mes en curso hasta hoy (fecha de Lima). */
    @Transactional(readOnly = true)
    public Liquidacion consultar(long productorId, LocalDate desde, LocalDate hasta) {
        LocalDate hoy = jdbc.getJdbcTemplate()
                .queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
        RangoFechas rango = RangoFechas.resolver(desde, hasta, hoy, RangoFechas.MES_EN_CURSO);
        LocalDate inicio = rango.desde();
        LocalDate fin = rango.hasta();

        MapSqlParameterSource p = new MapSqlParameterSource("productor", productorId)
                .addValue("desde", inicio).addValue("hasta", fin);
        Map<Long, List<Linea>> lineas = new HashMap<>();
        jdbc.query("""
                SELECT i.pedido_id, i.producto_id, pr.nombre, i.cantidad, i.precio_unitario, i.subtotal,
                       coalesce(i.comision_monto, 0) AS comision, coalesce(i.neto, i.subtotal) AS neto
                """ + SUS_LINEAS + "ORDER BY i.id", p, rs -> {
                    lineas.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                            .add(new Linea(rs.getLong("producto_id"), rs.getString("nombre"), rs.getBigDecimal("cantidad"),
                                    rs.getBigDecimal("precio_unitario"), rs.getBigDecimal("subtotal"),
                                    rs.getBigDecimal("comision"), rs.getBigDecimal("neto")));
                });

        List<PedidoLiquidado> pedidos = jdbc.query("""
                SELECT DISTINCT pe.id, pe.numero, pe.estado, pe.confirmado_en, pe.comision_porcentaje
                """ + SUS_LINEAS + "ORDER BY pe.confirmado_en DESC, pe.id DESC", p, (rs, row) -> {
            List<Linea> propias = lineas.getOrDefault(rs.getLong("id"), List.of());
            return new PedidoLiquidado(rs.getLong("id"), rs.getString("numero"), rs.getString("estado"),
                    rs.getTimestamp("confirmado_en").toInstant(), rs.getBigDecimal("comision_porcentaje"),
                    suma(propias, Linea::bruto), suma(propias, Linea::comision), suma(propias, Linea::neto), propias);
        });

        String productor = jdbc.queryForObject("SELECT coalesce(nombre_tienda, comunidad) FROM productor WHERE id = :productor",
                p, String.class);
        return new Liquidacion(inicio, fin, productor, resumen(pedidos), pedidos);
    }

    private static Resumen resumen(List<PedidoLiquidado> pedidos) {
        int sinComision = (int) pedidos.stream().filter(pe -> pe.porcentaje() == null).count();
        return new Resumen(pedidos.size(), suma(pedidos, PedidoLiquidado::bruto), suma(pedidos, PedidoLiquidado::comision),
                suma(pedidos, PedidoLiquidado::neto), sinComision);
    }

    private static <T> BigDecimal suma(List<T> filas, Function<T, BigDecimal> importe) {
        return filas.stream().map(importe).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
