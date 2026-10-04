package pe.ccpip.productor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PW-01: el inicio del productor, solo con datos que la plataforma ya maneja. */
@Service
public class PanelService {

    public record LotePorVencer(long productoId, String producto, String codigo, String ubicacion, LocalDate vence) {
    }

    public record Panel(String tienda, boolean tiendaPublicada, LocalDate verificadoEn, int publicados, int borradores,
                        int retirados, BigDecimal stockEnVenta, List<LotePorVencer> lotesPorVencer,
                        List<TiendaService.ProductoOpcion> publicadosSinStock) {
    }

    private final JdbcTemplate jdbc;

    public PanelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Panel obtener(long productorId) {
        List<LotePorVencer> porVencer = jdbc.query("""
                SELECT p.id, p.nombre, l.codigo, u.nombre AS ubicacion, l.fecha_vencimiento
                FROM lote l JOIN producto p ON p.id = l.producto_id JOIN ubicacion u ON u.id = l.ubicacion_id
                WHERE p.productor_id = ? AND l.cantidad_actual > 0
                  AND l.fecha_vencimiento BETWEEN (now() AT TIME ZONE 'America/Lima')::date
                      AND (now() AT TIME ZONE 'America/Lima')::date + ?
                ORDER BY l.fecha_vencimiento""",
                (rs, row) -> new LotePorVencer(rs.getLong("id"), rs.getString("nombre"), rs.getString("codigo"),
                        rs.getString("ubicacion"), rs.getObject("fecha_vencimiento", LocalDate.class)),
                productorId, ProductosService.DIAS_ALERTA_VENCIMIENTO);
        List<TiendaService.ProductoOpcion> sinStock = jdbc.query("""
                SELECT p.id, p.nombre FROM producto p
                WHERE p.productor_id = ? AND p.estado = 'PUBLICADO' AND %s = 0
                ORDER BY p.nombre""".formatted(ProductosService.EN_VENTA),
                (rs, row) -> new TiendaService.ProductoOpcion(rs.getLong("id"), rs.getString("nombre")), productorId);
        return jdbc.queryForObject("""
                SELECT coalesce(pr.nombre_tienda, pr.comunidad) AS tienda, pr.tienda_publicada, pr.verificado_en,
                       count(p.id) FILTER (WHERE p.estado = 'PUBLICADO') AS publicados,
                       count(p.id) FILTER (WHERE p.estado = 'BORRADOR') AS borradores,
                       count(p.id) FILTER (WHERE p.estado = 'RETIRADO') AS retirados,
                       coalesce((SELECT sum(l.cantidad_actual) FROM lote l
                                 JOIN producto x ON x.id = l.producto_id JOIN ubicacion u ON u.id = l.ubicacion_id
                                 WHERE x.productor_id = pr.id AND x.estado = 'PUBLICADO' AND u.tipo = 'PUNTO_ACOPIO'
                                   AND (l.fecha_vencimiento IS NULL
                                        OR l.fecha_vencimiento >= (now() AT TIME ZONE 'America/Lima')::date)), 0) AS en_venta
                FROM productor pr LEFT JOIN producto p ON p.productor_id = pr.id
                WHERE pr.id = ?
                GROUP BY pr.id""",
                (rs, row) -> new Panel(rs.getString("tienda"), rs.getBoolean("tienda_publicada"),
                        rs.getObject("verificado_en", LocalDate.class), rs.getInt("publicados"), rs.getInt("borradores"),
                        rs.getInt("retirados"), rs.getBigDecimal("en_venta"), porVencer, sinStock),
                productorId);
    }
}
