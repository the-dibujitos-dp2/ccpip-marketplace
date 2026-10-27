package pe.ccpip.distribucion;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.distribucion.DistribucionModelos.Disponibles;
import pe.ccpip.distribucion.DistribucionModelos.EntregaDelDistribuidor;
import pe.ccpip.distribucion.DistribucionModelos.Origen;
import pe.ccpip.distribucion.DistribucionModelos.PedidoDisponible;
import pe.ccpip.distribucion.DistribucionModelos.PuntoConPedidos;
import pe.ccpip.entrega.EntregaModelos.Distrito;

/**
 * CCPIP-107 (RF-54): los pedidos preparados que un distribuidor puede asumir. Es elegible el pedido a domicilio
 * LISTO_PARA_DESPACHO cuya entrega sigue DISPONIBLE, sin distribuidor, y que sale de un punto de acopio activo;
 * lo que ya asumio alguien deja de aparecer. CCPIP-108: el distribuidor lo asume y la entrega pasa a ser suya;
 * CCPIP-317: consulta las que tiene asignadas; CCPIP-109: la retira del punto de acopio. Llevarla y entregarla
 * llegan con CCPIP-318 y CCPIP-319.
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

    /**
     * CCPIP-317 (RF-199): las entregas asignadas al distribuidor, y solo las suyas (RNF-05). Primero las que estan en
     * curso (asumidas, retiradas o en camino), la que asumio antes primero; despues las que entrego hoy, hora de Lima,
     * la ultima primero. Cada una conserva su origen, su distrito de destino y su estado.
     */
    @Transactional(readOnly = true)
    public List<EntregaDelDistribuidor> asignadas(long distribuidorId) {
        return jdbc.query("""
                SELECT pe.numero, u.id AS punto_id, u.nombre AS punto, u.distrito AS punto_distrito, u.direccion AS punto_direccion,
                       g.codigo, g.departamento, g.provincia, g.distrito, e.estado, e.asignada_en, e.retirada_en
                FROM entrega e
                JOIN pedido pe ON pe.id = e.pedido_id
                JOIN ubigeo g ON g.codigo = e.ubigeo
                JOIN ubicacion u ON u.id = pe.ubicacion_origen_id
                WHERE e.distribuidor_id = :distribuidor
                  AND (e.estado <> 'ENTREGADA'
                       OR (e.entregada_en AT TIME ZONE 'America/Lima')::date = (now() AT TIME ZONE 'America/Lima')::date)
                ORDER BY e.estado = 'ENTREGADA',
                         CASE WHEN e.estado = 'ENTREGADA' THEN -extract(epoch FROM e.entregada_en) END,
                         e.asignada_en, e.id""", new MapSqlParameterSource("distribuidor", distribuidorId),
                (rs, row) -> new EntregaDelDistribuidor(rs.getString("numero"),
                        new Origen(rs.getLong("punto_id"), rs.getString("punto"), rs.getString("punto_distrito"),
                                rs.getString("punto_direccion")),
                        new Distrito(rs.getString("codigo"), rs.getString("departamento"), rs.getString("provincia"),
                                rs.getString("distrito")),
                        rs.getString("estado"), rs.getObject("asignada_en", Timestamp.class).toInstant(),
                        instante(rs.getObject("retirada_en", Timestamp.class))));
    }

    /** La entrega de un pedido a domicilio, ya bloqueada, con lo que hace falta para decidir si se puede asumir o retirar. */
    private record Bloqueada(long pedidoId, String estadoPedido, String estadoEntrega, Long distribuidorId, boolean puntoActivo) {
    }

    /**
     * CCPIP-108 (RF-55): el distribuidor asume la entrega de un pedido elegible (el mismo criterio de la cola): queda
     * ASIGNADA a el, con la fecha en {@code asignada_en}. La asignacion es exclusiva: la entrega se bloquea con
     * {@code FOR UPDATE}, asi dos distribuidores que la piden a la vez se ordenan y el segundo la encuentra asumida
     * (409 {@code PEDIDO_YA_ASUMIDO}). Si quien ya la asumio la vuelve a pedir, no cambia nada y recibe la misma
     * asignacion. El pedido sigue LISTO_PARA_DESPACHO hasta que lo retire (CCPIP-109) y no se toca el inventario.
     *
     * <p>Un pedido para recojo, o un numero que no existe, es un 404: para el distribuidor no hay entrega (RNF-05).
     */
    @Transactional
    public EntregaDelDistribuidor asumir(long distribuidorId, String numero) {
        Bloqueada entrega = bloquear(numero);
        if (entrega.distribuidorId() != null) {
            if (entrega.distribuidorId() == distribuidorId && "ASIGNADA".equals(entrega.estadoEntrega())) {
                return entregaDe(entrega.pedidoId());
            }
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_YA_ASUMIDO", "Otro distribuidor ya asumió este pedido.");
        }
        if (!"LISTO_PARA_DESPACHO".equals(entrega.estadoPedido()) || !"DISPONIBLE".equals(entrega.estadoEntrega())
                || !entrega.puntoActivo()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_NO_DISPONIBLE", "Este pedido no está disponible para asumir.");
        }
        jdbc.update("""
                UPDATE entrega SET distribuidor_id = :distribuidor, estado = 'ASIGNADA', asignada_en = now()
                WHERE pedido_id = :pedido""",
                new MapSqlParameterSource("distribuidor", distribuidorId).addValue("pedido", entrega.pedidoId()));
        return entregaDe(entrega.pedidoId());
    }

    /**
     * CCPIP-109 (RF-56): el distribuidor registra que retiro del punto de acopio la entrega que asumio: queda RETIRADA,
     * con la fecha en {@code retirada_en}, y la ven el punto en su tablero y el comprador en su pedido. Solo la suya y
     * solo si esta ASIGNADA (409 {@code PEDIDO_NO_ASIGNADO} o {@code PEDIDO_NO_RETIRABLE}); si ya la retiro, repetirlo
     * no cambia nada y recibe el mismo retiro. La entrega se bloquea con {@code FOR UPDATE}, como al asumirla.
     *
     * <p>No se toca el inventario: el stock se desconto al confirmar la compra y no se vuelve a descontar. El pedido
     * sigue LISTO_PARA_DESPACHO hasta que el distribuidor lo lleve (CCPIP-318).
     */
    @Transactional
    public EntregaDelDistribuidor retirar(long distribuidorId, String numero) {
        Bloqueada entrega = bloquear(numero);
        if (entrega.distribuidorId() == null || entrega.distribuidorId() != distribuidorId) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_NO_ASIGNADO", "Este pedido no está asignado a ti.");
        }
        if ("RETIRADA".equals(entrega.estadoEntrega())) {
            return entregaDe(entrega.pedidoId());
        }
        if (!"ASIGNADA".equals(entrega.estadoEntrega()) || !"LISTO_PARA_DESPACHO".equals(entrega.estadoPedido())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_NO_RETIRABLE", "Este pedido ya no se puede retirar del punto.");
        }
        jdbc.update("UPDATE entrega SET estado = 'RETIRADA', retirada_en = now() WHERE pedido_id = :pedido",
                new MapSqlParameterSource("pedido", entrega.pedidoId()));
        return entregaDe(entrega.pedidoId());
    }

    /** CCPIP-318: registra el inicio del viaje y su actor en una sola transacción, bajo el bloqueo de la entrega. */
    @Transactional
    public EntregaDelDistribuidor enCamino(long distribuidorId, String numero) {
        Bloqueada entrega = bloquearPropia(numero, distribuidorId);
        if ("EN_CAMINO".equals(entrega.estadoEntrega()) && "EN_CAMINO".equals(entrega.estadoPedido())) {
            return entregaDe(entrega.pedidoId());
        }
        if (!"RETIRADA".equals(entrega.estadoEntrega()) || !"LISTO_PARA_DESPACHO".equals(entrega.estadoPedido())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_NO_INICIADO", "Este pedido no está retirado o ya no permite iniciar el viaje.");
        }
        jdbc.update("UPDATE pedido SET estado = 'EN_CAMINO', actualizado_en = now() WHERE id = :pedido", new MapSqlParameterSource("pedido", entrega.pedidoId()));
        jdbc.update("UPDATE entrega SET estado = 'EN_CAMINO' WHERE pedido_id = :pedido", new MapSqlParameterSource("pedido", entrega.pedidoId()));
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id) VALUES (:pedido, 'EN_CAMINO', :usuario)",
                new MapSqlParameterSource("pedido", entrega.pedidoId()).addValue("usuario", distribuidorId));
        return entregaDe(entrega.pedidoId());
    }

    /** CCPIP-319: confirma la entrega final propia con fecha existente e historial del distribuidor. */
    @Transactional
    public EntregaDelDistribuidor entregar(long distribuidorId, String numero) {
        Bloqueada entrega = bloquearPropia(numero, distribuidorId);
        if ("ENTREGADA".equals(entrega.estadoEntrega()) && "ENTREGADO".equals(entrega.estadoPedido())) {
            return entregaDe(entrega.pedidoId());
        }
        if (!"EN_CAMINO".equals(entrega.estadoEntrega()) || !"EN_CAMINO".equals(entrega.estadoPedido())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_NO_ENTREGABLE", "Este pedido aún no está en camino.");
        }
        jdbc.update("UPDATE pedido SET estado='ENTREGADO', actualizado_en=now() WHERE id=:pedido", new MapSqlParameterSource("pedido", entrega.pedidoId()));
        jdbc.update("UPDATE entrega SET estado='ENTREGADA', entregada_en=now() WHERE pedido_id=:pedido", new MapSqlParameterSource("pedido", entrega.pedidoId()));
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id) VALUES (:pedido, 'ENTREGADO', :usuario)",
                new MapSqlParameterSource("pedido", entrega.pedidoId()).addValue("usuario", distribuidorId));
        return entregaDe(entrega.pedidoId());
    }

    /** Bloquea la entrega y verifica su propietario antes de permitir cualquier reintento idempotente. */
    private Bloqueada bloquearPropia(String numero, long distribuidorId) {
        Bloqueada entrega = bloquear(numero);
        if (entrega.distribuidorId() == null || entrega.distribuidorId() != distribuidorId) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_NO_ASIGNADO", "Este pedido no está asignado a ti.");
        }
        return entrega;
    }

    /**
     * Bloquea la entrega de un pedido a domicilio hasta el final de la transaccion.
     * que no existe, es un 404: para el distribuidor no hay entrega (RNF-05).
     */
    private Bloqueada bloquear(String numero) {
        return jdbc.query("""
                SELECT pe.id, pe.estado AS estado_pedido, e.estado AS estado_entrega, e.distribuidor_id, u.activo
                FROM pedido pe
                JOIN entrega e ON e.pedido_id = pe.id
                JOIN ubicacion u ON u.id = pe.ubicacion_origen_id
                WHERE upper(pe.numero) = :numero AND pe.modalidad = 'ENVIO'
                FOR UPDATE OF pe, e""", new MapSqlParameterSource("numero", numero),
                (rs, row) -> new Bloqueada(rs.getLong("id"), rs.getString("estado_pedido"), rs.getString("estado_entrega"),
                        rs.getObject("distribuidor_id", Long.class), rs.getBoolean("activo")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe o no se entrega a domicilio."));
    }

    /** La entrega del distribuidor, con su origen, su distrito de destino, su estado y cuando la asumio y la retiro. */
    private EntregaDelDistribuidor entregaDe(long pedidoId) {
        return jdbc.queryForObject("""
                SELECT pe.numero, u.id AS punto_id, u.nombre AS punto, u.distrito AS punto_distrito, u.direccion AS punto_direccion,
                       g.codigo, g.departamento, g.provincia, g.distrito, e.estado, e.asignada_en, e.retirada_en
                FROM pedido pe
                JOIN entrega e ON e.pedido_id = pe.id
                JOIN ubigeo g ON g.codigo = e.ubigeo
                JOIN ubicacion u ON u.id = pe.ubicacion_origen_id
                WHERE pe.id = :pedido""", new MapSqlParameterSource("pedido", pedidoId),
                (rs, row) -> new EntregaDelDistribuidor(rs.getString("numero"),
                        new Origen(rs.getLong("punto_id"), rs.getString("punto"), rs.getString("punto_distrito"),
                                rs.getString("punto_direccion")),
                        new Distrito(rs.getString("codigo"), rs.getString("departamento"), rs.getString("provincia"),
                                rs.getString("distrito")),
                        rs.getString("estado"), rs.getObject("asignada_en", Timestamp.class).toInstant(),
                        instante(rs.getObject("retirada_en", Timestamp.class))));
    }

    private static Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
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
