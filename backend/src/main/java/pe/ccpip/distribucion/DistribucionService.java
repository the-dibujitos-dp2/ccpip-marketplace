package pe.ccpip.distribucion;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.distribucion.DistribucionModelos.Disponibles;
import pe.ccpip.distribucion.DistribucionModelos.Origen;
import pe.ccpip.distribucion.DistribucionModelos.PedidoDisponible;
import pe.ccpip.distribucion.DistribucionModelos.PuntoConPedidos;
import pe.ccpip.entrega.EntregaModelos.Distrito;

/**
 * CCPIP-107 (RF-54): los pedidos preparados que un distribuidor puede asumir. Es elegible el pedido a domicilio
 * LISTO_PARA_DESPACHO cuya entrega sigue DISPONIBLE, sin distribuidor, y que sale de un punto de acopio activo;
 * lo que ya asumio alguien deja de aparecer. Asumirlo, retirarlo y entregarlo llegan con CCPIP-108 a 110.
 *
 * <p>La cola se recorre por paginas con un cursor (el ultimo pedido visto: desde cuando esta disponible y su id),
 * no por posicion: asi un pedido que otro distribuidor asume mientras se recorre no hace saltar ni repetir a los demas.
 */
@Service
public class DistribucionService {

    private static final String ELEGIBLES = """
            SELECT pe.id AS pedido_id, pe.numero, u.id AS punto_id, u.nombre AS punto, u.distrito AS punto_distrito,
                   u.direccion AS punto_direccion, g.codigo, g.departamento, g.provincia, g.distrito,
                   coalesce((SELECT max(h.registrado_en) FROM pedido_estado_historial h
                             WHERE h.pedido_id = pe.id AND h.estado = 'LISTO_PARA_DESPACHO'), pe.actualizado_en) AS disponible_desde
            FROM pedido pe
            JOIN entrega e ON e.pedido_id = pe.id
            JOIN ubigeo g ON g.codigo = e.ubigeo
            JOIN ubicacion u ON u.id = pe.ubicacion_origen_id
            WHERE pe.modalidad = 'ENVIO' AND pe.estado = 'LISTO_PARA_DESPACHO'
              AND e.estado = 'DISPONIBLE' AND e.distribuidor_id IS NULL AND u.activo""";

    private final NamedParameterJdbcTemplate jdbc;

    public DistribucionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Una pagina de los elegibles, el que lleva mas esperando primero. {@code punto} (opcional) deja solo los de ese punto. */
    @Transactional(readOnly = true)
    public Disponibles disponibles(Long punto, String cursor, int limite) {
        Instant ahora = Instant.now();
        MapSqlParameterSource p = new MapSqlParameterSource("punto", punto).addValue("limite", limite + 1);
        Cursor desde = Cursor.leer(cursor);
        p.addValue("desde", desde == null ? null : Timestamp.from(desde.desde()))
                .addValue("id", desde == null ? null : desde.pedidoId());

        List<PuntoConPedidos> puntos = jdbc.query("SELECT punto_id, punto, count(*) AS total FROM (" + ELEGIBLES
                + ") e GROUP BY punto_id, punto ORDER BY punto, punto_id", p,
                (rs, row) -> new PuntoConPedidos(rs.getLong("punto_id"), rs.getString("punto"), rs.getLong("total")));
        long total = puntos.stream().filter(x -> punto == null || x.id() == punto).mapToLong(PuntoConPedidos::total).sum();

        List<Long> ids = new ArrayList<>();
        List<Instant> desdes = new ArrayList<>();
        List<PedidoDisponible> items = new ArrayList<>(jdbc.query("SELECT * FROM (" + ELEGIBLES + """
                ) e
                WHERE (CAST(:punto AS bigint) IS NULL OR e.punto_id = :punto)
                  AND (CAST(:desde AS timestamptz) IS NULL
                       OR (e.disponible_desde, e.pedido_id) > (CAST(:desde AS timestamptz), CAST(:id AS bigint)))
                ORDER BY e.disponible_desde, e.pedido_id
                LIMIT :limite""", p, (rs, row) -> {
                    Instant disponible = rs.getObject("disponible_desde", Timestamp.class).toInstant();
                    ids.add(rs.getLong("pedido_id"));
                    desdes.add(disponible);
                    return new PedidoDisponible(rs.getString("numero"),
                            new Origen(rs.getLong("punto_id"), rs.getString("punto"), rs.getString("punto_distrito"),
                                    rs.getString("punto_direccion")),
                            new Distrito(rs.getString("codigo"), rs.getString("departamento"), rs.getString("provincia"),
                                    rs.getString("distrito")),
                            disponible, Math.max(0, Duration.between(disponible, ahora).toMinutes()));
                }));

        String siguiente = null;
        if (items.size() > limite) {
            // El pedido de mas solo avisa que hay otra pagina; el cursor apunta al ultimo que si se devuelve
            items.remove(limite);
            siguiente = new Cursor(desdes.get(limite - 1), ids.get(limite - 1)).escribir();
        }
        return new Disponibles(items, siguiente, total, puntos);
    }

    /** El ultimo pedido visto, en el orden de la cola. Viaja como texto opaco: el cliente solo lo devuelve. */
    record Cursor(Instant desde, long pedidoId) {

        private static final long MICROS = 1_000_000L;

        String escribir() {
            long micros = desde.getEpochSecond() * MICROS + desde.getNano() / 1000;
            return Base64.getUrlEncoder().withoutPadding().encodeToString((micros + ":" + pedidoId).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor leer(String texto) {
            if (texto == null || texto.isBlank()) {
                return null;
            }
            try {
                String[] partes = new String(Base64.getUrlDecoder().decode(texto), StandardCharsets.UTF_8).split(":");
                long micros = Long.parseLong(partes[0]);
                long id = Long.parseLong(partes[1]);
                if (partes.length != 2 || id < 0) {
                    throw new IllegalArgumentException();
                }
                return new Cursor(Instant.ofEpochSecond(Math.floorDiv(micros, MICROS), Math.floorMod(micros, MICROS) * 1000), id);
            } catch (RuntimeException e) {
                throw ErrorNegocio.invalido("CURSOR_INVALIDO", "El cursor no es válido: usa el que devolvió la página anterior.");
            }
        }
    }
}