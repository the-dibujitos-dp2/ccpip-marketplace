package pe.ccpip.distribucion;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.distribucion.DistribucionModelos.Origen;
import pe.ccpip.distribucion.DistribucionModelos.PedidoDisponible;
import pe.ccpip.entrega.EntregaModelos.Distrito;

/**
 * CCPIP-107 (RF-54): los pedidos preparados que un distribuidor puede asumir. Es elegible el pedido a domicilio
 * LISTO_PARA_DESPACHO cuya entrega sigue DISPONIBLE, sin distribuidor, y que sale de un punto de acopio activo;
 * lo que ya asumio alguien deja de aparecer. Asumirlo, retirarlo y entregarlo llegan con CCPIP-108 a 110.
 */
@Service
public class DistribucionService {

    private final NamedParameterJdbcTemplate jdbc;

    public DistribucionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Los elegibles, el que lleva mas esperando primero. {@code punto} (opcional) deja solo los de ese punto. */
    @Transactional(readOnly = true)
    public List<PedidoDisponible> disponibles(Long punto) {
        Instant ahora = Instant.now();
        return jdbc.query("""
                SELECT pe.numero, u.id AS punto_id, u.nombre AS punto, u.distrito AS punto_distrito, u.direccion AS punto_direccion,
                       g.codigo, g.departamento, g.provincia, g.distrito,
                       coalesce((SELECT max(h.registrado_en) FROM pedido_estado_historial h
                                 WHERE h.pedido_id = pe.id AND h.estado = 'LISTO_PARA_DESPACHO'), pe.actualizado_en) AS disponible_desde
                FROM pedido pe
                JOIN entrega e ON e.pedido_id = pe.id
                JOIN ubigeo g ON g.codigo = e.ubigeo
                JOIN ubicacion u ON u.id = pe.ubicacion_origen_id
                WHERE pe.modalidad = 'ENVIO' AND pe.estado = 'LISTO_PARA_DESPACHO'
                  AND e.estado = 'DISPONIBLE' AND e.distribuidor_id IS NULL
                  AND u.activo AND (CAST(:punto AS bigint) IS NULL OR u.id = :punto)
                ORDER BY disponible_desde, pe.id""", new MapSqlParameterSource("punto", punto),
                (rs, row) -> {
                    Instant desde = rs.getObject("disponible_desde", Timestamp.class).toInstant();
                    return new PedidoDisponible(rs.getString("numero"),
                            new Origen(rs.getLong("punto_id"), rs.getString("punto"), rs.getString("punto_distrito"),
                                    rs.getString("punto_direccion")),
                            new Distrito(rs.getString("codigo"), rs.getString("departamento"), rs.getString("provincia"),
                                    rs.getString("distrito")),
                            desde, Math.max(0, Duration.between(desde, ahora).toMinutes()));
                });
    }
}
