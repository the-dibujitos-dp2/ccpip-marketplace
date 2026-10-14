package pe.ccpip.compra;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.compra.CompraModelos.ResultadoPago;
import pe.ccpip.inventario.DescuentoExistenciasService;
import pe.ccpip.pago.PasarelaPago;
import pe.ccpip.pago.PasarelaPago.Estado;
import pe.ccpip.pago.PasarelaPago.ResultadoCobro;
import pe.ccpip.pago.Tarjeta;

/**
 * CCPIP-62: pagar un pedido con tarjeta. Tres pasos para que una pasarela lenta no deje la base
 * bloqueada y cada intento quede registrado aunque algo falle a mitad (CCPIP-163):
 *
 * <ol>
 *   <li>Se registra el intento en PENDIENTE (transaccion corta).</li>
 *   <li>Se cobra en la pasarela, fuera de toda transaccion.</li>
 *   <li>Se aplica el resultado. Si se aprobo, en una sola transaccion: descuento de stock (CCPIP-158),
 *       pedido CONFIRMADO y carrito vaciado. Si el stock ya no alcanzo, el pago se ANULA, se devuelve
 *       y el pedido se cancela. Un rechazo no confirma ni descuenta.</li>
 * </ol>
 *
 * <p>La base impide el doble cobro aunque este codigo falle: un solo pago aprobado y un solo intento
 * pendiente por pedido, y la clave de idempotencia es unica (V202610132100__pago.sql).
 */
@Service
public class PagoService {

    private static final Logger log = LoggerFactory.getLogger(PagoService.class);

    private record Intento(long id, long pedidoId, String numero, BigDecimal monto) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DescuentoExistenciasService descuento;
    private final PasarelaPago pasarela;
    private final CompraService compra;

    public PagoService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, DescuentoExistenciasService descuento,
                       PasarelaPago pasarela, CompraService compra) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.descuento = descuento;
        this.pasarela = pasarela;
        this.compra = compra;
    }

    /**
     * Paga el pedido. Si la misma {@code clave} ya se uso (doble clic, reintento por corte de red),
     * devuelve ese intento sin volver a cobrar.
     */
    public ResultadoPago pagar(long usuarioId, long pedidoId, UUID clave, Tarjeta tarjeta) {
        Intento previo = intentoPorClave(clave);
        if (previo != null) {
            if (previo.pedidoId() != pedidoId) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "CLAVE_REUTILIZADA", "Este intento de pago pertenece a otro pedido.");
            }
            return resultado(usuarioId, pedidoId, previo.id());
        }

        Tarjeta.Marca marca = tarjeta.validar();
        Intento intento = tx.execute(s -> registrar(usuarioId, pedidoId, clave, marca));

        ResultadoCobro cobro;
        try {
            cobro = pasarela.cobrar(intento.numero(), intento.monto(), tarjeta);
        } catch (RuntimeException e) {
            log.warn("La pasarela {} fallo al cobrar el pedido {}: {}", pasarela.nombre(), intento.numero(), e.getMessage());
            cobro = new ResultadoCobro(Estado.ERROR, null, "PASARELA_NO_DISPONIBLE",
                    "No pudimos comunicarnos con la pasarela de pago. No se hizo ningún cobro; inténtalo otra vez.");
        }

        if (cobro.estado() == Estado.APROBADO) {
            ResultadoCobro aprobado = cobro;
            try {
                tx.executeWithoutResult(s -> confirmar(usuarioId, intento, aprobado));
            } catch (ErrorNegocio e) {
                if (!"STOCK_INSUFICIENTE".equals(e.code())) {
                    throw e;
                }
                // Otro comprador se llevo las ultimas unidades mientras este pagaba
                tx.executeWithoutResult(s -> anular(usuarioId, intento, aprobado, e.getMessage()));
                pasarela.devolver(aprobado.referencia(), intento.monto());
            }
        } else {
            ResultadoCobro resuelto = cobro;
            tx.executeWithoutResult(s -> resolver(intento.id(), resuelto));
        }
        return resultado(usuarioId, pedidoId, intento.id());
    }

    private Intento registrar(long usuarioId, long pedidoId, UUID clave, Tarjeta.Marca marca) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", pedidoId).addValue("usuario", usuarioId);
        record Pagable(String numero, String estado, BigDecimal total) {
        }
        Pagable pedido = jdbc.query("SELECT numero, estado, total FROM pedido WHERE id = :id AND usuario_id = :usuario FOR UPDATE", p,
                        (rs, row) -> new Pagable(rs.getString("numero"), rs.getString("estado"), rs.getBigDecimal("total")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "No encontramos ese pedido."));
        if (!"PENDIENTE_PAGO".equals(pedido.estado())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_NO_PAGABLE", switch (pedido.estado()) {
                case "CANCELADO" -> "Este pedido fue cancelado. Vuelve a tu carrito para comprar otra vez.";
                default -> "Este pedido ya fue pagado.";
            });
        }
        p.addValue("clave", clave).addValue("monto", pedido.total()).addValue("pasarela", pasarela.nombre())
                .addValue("marca", marca.name());
        try {
            long pagoId = jdbc.queryForObject("""
                    INSERT INTO pago (pedido_id, clave_idempotencia, monto, pasarela, marca_tarjeta)
                    VALUES (:id, :clave, :monto, :pasarela, :marca) RETURNING id""", p, Long.class);
            return new Intento(pagoId, pedidoId, pedido.numero(), pedido.total());
        } catch (DuplicateKeyException e) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PAGO_EN_CURSO",
                    "Ya hay un pago en curso para este pedido. Espera su resultado antes de reintentar.");
        }
    }

    private void confirmar(long usuarioId, Intento intento, ResultadoCobro cobro) {
        resolver(intento.id(), cobro);
        descuento.descontarPorPedido(intento.pedidoId(), usuarioId);
        MapSqlParameterSource p = new MapSqlParameterSource("id", intento.pedidoId()).addValue("usuario", usuarioId);
        jdbc.update("UPDATE pedido SET estado = 'CONFIRMADO', confirmado_en = now(), actualizado_en = now() WHERE id = :id", p);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id) VALUES (:id, 'CONFIRMADO', :usuario)", p);
        // Lo comprado sale del carrito; lo demas (si hubiera) se queda
        jdbc.update("""
                DELETE FROM item_carrito ic USING carrito c
                WHERE c.id = ic.carrito_id AND c.usuario_id = :usuario
                  AND ic.producto_id IN (SELECT producto_id FROM item_pedido WHERE pedido_id = :id)""", p);
    }

    private void anular(long usuarioId, Intento intento, ResultadoCobro cobro, String motivo) {
        resolver(intento.id(), cobro);
        jdbc.update("""
                UPDATE pago SET estado = 'ANULADO', anulado_en = now(), codigo_respuesta = 'STOCK_INSUFICIENTE',
                                mensaje_respuesta = :mensaje
                WHERE id = :id""", new MapSqlParameterSource("id", intento.id())
                .addValue("mensaje", motivo + " Devolvimos tu pago y cancelamos el pedido."));
        MapSqlParameterSource p = new MapSqlParameterSource("id", intento.pedidoId()).addValue("usuario", usuarioId);
        jdbc.update("UPDATE pedido SET estado = 'CANCELADO', actualizado_en = now() WHERE id = :id", p);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id) VALUES (:id, 'CANCELADO', :usuario)", p);
    }

    private void resolver(long pagoId, ResultadoCobro cobro) {
        jdbc.update("""
                UPDATE pago SET estado = :estado, resuelto_en = now(), referencia_pasarela = :referencia,
                                codigo_respuesta = :codigo, mensaje_respuesta = :mensaje
                WHERE id = :id""", new MapSqlParameterSource("id", pagoId).addValue("estado", cobro.estado().name())
                .addValue("referencia", cobro.referencia()).addValue("codigo", cobro.codigo())
                .addValue("mensaje", cobro.mensaje()));
    }

    private Intento intentoPorClave(UUID clave) {
        return jdbc.query("""
                SELECT pa.id, pa.pedido_id, pe.numero, pa.monto FROM pago pa JOIN pedido pe ON pe.id = pa.pedido_id
                WHERE pa.clave_idempotencia = :clave""", new MapSqlParameterSource("clave", clave),
                (rs, row) -> new Intento(rs.getLong("id"), rs.getLong("pedido_id"), rs.getString("numero"),
                        rs.getBigDecimal("monto"))).stream().findFirst().orElse(null);
    }

    /** El intento y el pedido como quedaron. Solo del comprador: un pedido ajeno no existe (RNF-05). */
    private ResultadoPago resultado(long usuarioId, long pedidoId, long pagoId) {
        CompraModelos.Pedido pedido = compra.pedido(usuarioId, pedidoId);
        CompraModelos.IntentoPago intento = pedido.pagos().stream().filter(i -> i.id() == pagoId).findFirst().orElseThrow();
        return new ResultadoPago(intento.estado(), intento.mensaje(), pedido);
    }
}
