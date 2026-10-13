package pe.ccpip.entrega;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.entrega.EntregaModelos.Cambio;
import pe.ccpip.entrega.EntregaModelos.Linea;
import pe.ccpip.entrega.EntregaModelos.LoteAsignado;
import pe.ccpip.entrega.EntregaModelos.PedidoPunto;
import pe.ccpip.entrega.EntregaModelos.PedidosPunto;

/**
 * Pedidos para recoger en el punto de acopio (Figma A-05, ADR-17). Cada administrador opera solo
 * los pedidos de los puntos que tiene en usuario_ubicacion; un pedido de otro punto no existe para
 * el (404, RNF-05). Cambiar el estado no toca el inventario: el descuento ya ocurrio al confirmar
 * la compra (CCPIP-158).
 */
@Service
public class PedidosPuntoService {

    static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /** El filtro de RNF-05: solo los pedidos de los puntos que el administrador tiene asignados. */
    private static final String EN_SU_PUNTO =
            "pe.ubicacion_origen_id IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = :usuario)";

    /** Los pedidos de los puntos del administrador. */
    private static final String DEL_PUNTO = "FROM pedido pe WHERE " + EN_SU_PUNTO + "\n";

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;

    public PedidosPuntoService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
    }

    /** Columnas de A-05 en Sprint 0: confirmados, listos para recojo y entregados hoy (hora de Lima). */
    @Transactional(readOnly = true)
    public PedidosPunto listar(long usuarioId) {
        String ubicacion = puntos.nombres(usuarioId);
        List<Long> ids = jdbc.queryForList("SELECT pe.id " + DEL_PUNTO + """
                  AND (pe.estado IN ('CONFIRMADO', 'LISTO_PARA_RECOJO')
                       OR pe.estado = 'ENTREGADO' AND (pe.actualizado_en AT TIME ZONE 'America/Lima')::date = %s)
                ORDER BY CASE pe.estado WHEN 'CONFIRMADO' THEN 0 WHEN 'LISTO_PARA_RECOJO' THEN 1 ELSE 2 END,
                         pe.confirmado_en, pe.id""".formatted(HOY),
                new MapSqlParameterSource("usuario", usuarioId), Long.class);
        return new PedidosPunto(ubicacion, pedidos(ids));
    }

    /** Un pedido del punto, ya bloqueado, con lo que hace falta para decidir si cambia de estado. */
    private record Bloqueado(long id, String estado, String comprador, String personaAutorizada, String celularContacto) {
    }

    /**
     * Busca el pedido entre los de los puntos del administrador y lo bloquea hasta el final de la
     * transaccion: dos clics simultaneos sobre el mismo pedido se ordenan. Otro punto, o un numero
     * que no existe, es un 404 (RNF-05).
     */
    private Bloqueado bloquear(long usuarioId, String numero) {
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("numero", numero);
        return jdbc.query("""
                SELECT pe.id, pe.estado, u.nombre AS comprador, pe.persona_autorizada, pe.celular_contacto
                FROM pedido pe JOIN usuario u ON u.id = pe.usuario_id
                WHERE %s AND upper(pe.numero) = :numero
                FOR UPDATE OF pe""".formatted(EN_SU_PUNTO), p,
                (rs, row) -> new Bloqueado(rs.getLong("id"), rs.getString("estado"), rs.getString("comprador"),
                        rs.getString("persona_autorizada"), rs.getString("celular_contacto")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe en tu punto de acopio."));
    }

    /** El cambio de estado solo vale desde {@code requerido}; en cualquier otro, explica donde esta el pedido. */
    private static void exigirEstado(Bloqueado pedido, String requerido) {
        if (requerido.equals(pedido.estado())) {
            return;
        }
        throw new ErrorNegocio(HttpStatus.CONFLICT, "ESTADO_INVALIDO", switch (pedido.estado()) {
            case "CONFIRMADO" -> "Este pedido aún no está listo para recojo.";
            case "LISTO_PARA_RECOJO" -> "Este pedido ya está listo para recojo.";
            case "ENTREGADO" -> "Este pedido ya fue entregado.";
            case "CANCELADO" -> "Este pedido fue cancelado.";
            default -> "Este pedido no se puede cambiar ahora.";
        });
    }

    /** Pasa el pedido a su nuevo estado y deja en el historial quien lo hizo y cuando. No toca el inventario. */
    private void cambiarEstado(long usuarioId, Bloqueado pedido, String nuevo) {
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("id", pedido.id()).addValue("estado", nuevo);
        jdbc.update("UPDATE pedido SET estado = :estado, actualizado_en = now() WHERE id = :id", p);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id) VALUES (:id, :estado, :usuario)", p);
    }

    /**
     * CCPIP-159 (RF-128): el pedido confirmado queda listo para recojo, con responsable y fecha en su
     * historial. Solo desde CONFIRMADO: marcarlo dos veces, o un pedido ya entregado, es un 409.
     */
    @Transactional
    public PedidoPunto marcarListo(long usuarioId, String numero) {
        Bloqueado pedido = bloquear(usuarioId, numero);
        exigirEstado(pedido, "CONFIRMADO");
        cambiarEstado(usuarioId, pedido, "LISTO_PARA_RECOJO");
        return pedidos(List.of(pedido.id())).get(0);
    }

    /**
     * CCPIP-71 (RF-53): el pedido listo se entrega a quien lo recoge, que debe ser el comprador o la
     * persona autorizada y dar el celular registrado en el pedido. Queda quien recogio, el responsable
     * del punto y la fecha. Cierra la venta sin tocar el inventario: el descuento ya ocurrio al confirmar
     * la compra (CCPIP-158) y no se repite.
     *
     * <p>Los rechazos no dicen que dato debia ser: no se revela quien compro ni su celular (RNF-05).
     */
    @Transactional
    public PedidoPunto registrarRecojo(long usuarioId, String numero, String persona, String celular) {
        String celularDado = PersonaDeRecojo.celular(celular).orElseThrow(() -> ErrorNegocio.invalido("CELULAR_INVALIDO",
                "Ingresa un celular de 9 dígitos que empiece con 9."));
        Bloqueado pedido = bloquear(usuarioId, numero);
        exigirEstado(pedido, "LISTO_PARA_RECOJO");

        String recoge;
        if (PersonaDeRecojo.mismoNombre(persona, pedido.comprador())) {
            recoge = pedido.comprador();
        } else if (PersonaDeRecojo.mismoNombre(persona, pedido.personaAutorizada())) {
            recoge = pedido.personaAutorizada();
        } else {
            throw ErrorNegocio.invalido("PERSONA_NO_COINCIDE", "La persona no es quien compró ni la persona autorizada de este pedido.");
        }
        // Sin celular registrado no hay con que compararlo: se acepta uno valido y queda como constancia
        if (pedido.celularContacto() != null && PersonaDeRecojo.celular(pedido.celularContacto()).filter(celularDado::equals).isEmpty()) {
            throw ErrorNegocio.invalido("CELULAR_NO_COINCIDE", "El celular no coincide con el registrado en el pedido.");
        }

        cambiarEstado(usuarioId, pedido, "ENTREGADO");
        // Despues del cambio de estado: la base solo admite quien recogio en un pedido ENTREGADO
        jdbc.update("UPDATE pedido SET recogido_por = :por, recogido_celular = :celular WHERE id = :id",
                new MapSqlParameterSource("por", recoge).addValue("celular", celularDado).addValue("id", pedido.id()));
        return pedidos(List.of(pedido.id())).get(0);
    }

    /** Arma los pedidos con sus lineas e historial, en el orden de {@code ids}. */
    private List<PedidoPunto> pedidos(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids);
        // Los lotes que el descuento asigno a cada linea (CCPIP-74), el que vence antes primero
        Map<Long, List<LoteAsignado>> lotes = new HashMap<>();
        jdbc.query("""
                SELECT m.item_pedido_id, l.codigo, m.cantidad, l.fecha_vencimiento
                FROM movimiento_inventario m JOIN lote l ON l.id = m.lote_id
                WHERE m.pedido_id IN (:ids) AND m.tipo = 'SALIDA_VENTA'
                ORDER BY l.fecha_vencimiento NULLS LAST, m.id""", p,
                rs -> {
                    lotes.computeIfAbsent(rs.getLong("item_pedido_id"), k -> new ArrayList<>())
                            .add(new LoteAsignado(rs.getString("codigo"), rs.getBigDecimal("cantidad"),
                                    rs.getObject("fecha_vencimiento", LocalDate.class)));
                });
        Map<Long, List<Linea>> lineas = new HashMap<>();
        jdbc.query("""
                SELECT i.id, i.pedido_id, pr.nombre, coalesce(po.nombre_tienda, po.comunidad) AS comunidad, i.cantidad
                FROM item_pedido i JOIN producto pr ON pr.id = i.producto_id JOIN productor po ON po.id = i.productor_id
                WHERE i.pedido_id IN (:ids) ORDER BY i.id""", p,
                rs -> {
                    lineas.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                            .add(new Linea(rs.getString("nombre"), rs.getString("comunidad"), rs.getBigDecimal("cantidad"),
                                    lotes.getOrDefault(rs.getLong("id"), List.of())));
                });
        // El responsable es el personal del punto; el comprador que confirmo no se nombra (RNF-05)
        Map<Long, List<Cambio>> historial = new HashMap<>();
        jdbc.query("""
                SELECT h.pedido_id, h.estado, h.registrado_en,
                       CASE WHEN r.codigo = 'ADMIN_PUNTO' THEN u.nombre END AS responsable
                FROM pedido_estado_historial h
                LEFT JOIN usuario u ON u.id = h.usuario_id LEFT JOIN rol r ON r.id = u.rol_id
                WHERE h.pedido_id IN (:ids) ORDER BY h.registrado_en, h.id""", p,
                rs -> {
                    historial.computeIfAbsent(rs.getLong("pedido_id"), k -> new ArrayList<>())
                            .add(new Cambio(rs.getString("estado"), rs.getObject("registrado_en", Timestamp.class).toInstant(),
                                    rs.getString("responsable")));
                });
        Map<Long, PedidoPunto> porId = new HashMap<>();
        jdbc.query("SELECT id, numero, estado, confirmado_en, actualizado_en, total, recogido_por FROM pedido WHERE id IN (:ids)", p,
                rs -> {
                    long id = rs.getLong("id");
                    porId.put(id, new PedidoPunto(rs.getString("numero"), rs.getString("estado"),
                            rs.getObject("confirmado_en", Timestamp.class).toInstant(),
                            rs.getObject("actualizado_en", Timestamp.class).toInstant(), rs.getBigDecimal("total"),
                            lineas.getOrDefault(id, List.of()), historial.getOrDefault(id, List.of()), rs.getString("recogido_por")));
                });
        return ids.stream().map(porId::get).toList();
    }
}
