package pe.ccpip.entrega;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.entrega.EntregaModelos.PedidoPunto;
import pe.ccpip.pago.PasarelaPago;

/**
 * CCPIP-485 y CCPIP-486: un producto de un pedido confirmado se malogra en el punto antes de entregarse.
 * Decision del cliente del 05/11/2026 (origen CCPIP-418): primero se reemplaza con otro lote vigente del
 * mismo producto en el mismo punto (485); si no hay, se entrega lo disponible y lo faltante se reembolsa
 * automaticamente, con la comision anulada en la misma proporcion (486).
 *
 * <ul>
 *   <li>Quien: el administrador del punto de origen del pedido; otro punto no lo ve (404, RNF-05).</li>
 *   <li>Cuando: confirmado y aun no entregado ni en camino. Lo entregado queda fuera (devoluciones,
 *       CCPIP-102).</li>
 *   <li>Inventario: las unidades malogradas ya salieron de su lote con la venta. Vuelven al lote con un
 *       REINTEGRO y se dan de baja con una MERMA del mismo lote (CCPIP-76), asi que su saldo no cambia
 *       y no se edita. En el reemplazo, el lote nuevo se descuenta una sola vez (SALIDA_VENTA).</li>
 *   <li>Dinero: item_pedido y pedido no cambian (RNF-13). Lo faltante, el reembolso y la comision anulada
 *       quedan en {@code incidencia_pedido} y {@code reembolso}. Si no se entrega nada, el pedido se anula
 *       y se devuelve tambien el envio.</li>
 *   <li>Un reintento del mismo formulario (misma clave) no registra dos veces.</li>
 * </ul>
 */
@Service
public class ProductoMalogradoService {

    /** Estados en los que el pedido aun esta en el punto. */
    private static final List<String> EN_EL_PUNTO = List.of("CONFIRMADO", "EN_PREPARACION", "LISTO_PARA_RECOJO",
            "LISTO_PARA_DESPACHO");

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /** Lo que de cada lote sigue asignado a la linea: lo vendido menos lo reintegrado. */
    static final String NETO_POR_LOTE = """
            SELECT m.lote_id, sum(CASE WHEN m.tipo = 'SALIDA_VENTA' THEN m.cantidad ELSE -m.cantidad END) AS cantidad
            FROM movimiento_inventario m
            WHERE m.item_pedido_id = :item
              AND (m.tipo = 'SALIDA_VENTA' OR m.motivo = 'REINTEGRO_PEDIDO')
            GROUP BY m.lote_id
            """;

    public enum Accion { REEMPLAZAR, REEMBOLSAR }

    /** Lo que el administrador indica: que lote y cuanto se malogro, por que y como se resuelve. */
    public record Malogrado(long loteId, BigDecimal cantidad, long motivoId, String detalle, Accion accion,
                            Long loteReemplazoId, UUID clave) {
    }

    /** Un lote asignado a la linea, con lo que aun cubre de ella. */
    public record LoteDeLinea(long id, String codigo, BigDecimal cantidad, LocalDate fechaVencimiento) {
    }

    /** Un lote vigente del mismo producto en el punto que puede cubrir el reemplazo. */
    public record LoteReemplazo(long id, String codigo, BigDecimal disponible, LocalDate fechaVencimiento) {
    }

    /**
     * Para el formulario: la linea, lo que aun se entrega de ella, sus lotes y los lotes con que puede
     * reemplazarse. Si {@code reemplazos} esta vacia, solo cabe el reembolso.
     */
    public record OpcionesLinea(long itemId, String producto, BigDecimal cantidad, BigDecimal porEntregar,
                                BigDecimal precioUnitario, List<LoteDeLinea> lotes, List<LoteReemplazo> reemplazos) {
    }

    /** Lo registrado, con el pedido ya actualizado. {@code repetida}: el mismo envio llego otra vez. */
    public record Resultado(long incidenciaId, String tipo, BigDecimal cantidad, BigDecimal reembolsado,
                            boolean pedidoAnulado, boolean repetida, PedidoPunto pedido) {
    }

    private record Pedido(long id, String numero, String estado, long ubicacionId, BigDecimal costoEnvio) {
    }

    private record Item(long id, long productoId, String producto, BigDecimal cantidad, BigDecimal precioUnitario,
                        BigDecimal subtotal, BigDecimal comision) {
    }

    private record Causa(long id, String nombre, String nombreEn, boolean requiereDetalle) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PedidosPuntoService pedidos;
    private final PasarelaPago pasarela;

    public ProductoMalogradoService(NamedParameterJdbcTemplate jdbc, PedidosPuntoService pedidos, PasarelaPago pasarela) {
        this.jdbc = jdbc;
        this.pedidos = pedidos;
        this.pasarela = pasarela;
    }

    // ---------- Consultar ----------

    @Transactional(readOnly = true)
    public OpcionesLinea opciones(long usuarioId, String numero, long itemId) {
        Pedido pedido = pedido(usuarioId, numero, false);
        Item item = item(pedido, itemId, false);
        return new OpcionesLinea(item.id(), item.producto(), item.cantidad(), item.cantidad().subtract(faltante(item.id())),
                item.precioUnitario(), lotesDeLinea(item.id()), reemplazos(pedido, item));
    }

    // ---------- Registrar ----------

    @Transactional
    public Resultado registrar(long usuarioId, String numero, long itemId, Malogrado d) {
        Pedido pedido = pedido(usuarioId, numero, true);

        // Despues del bloqueo del pedido: un reintento simultaneo ya ve lo que registro el primero
        Optional<Resultado> previa = previa(usuarioId, d.clave(), pedido);
        if (previa.isPresent()) {
            return previa.get();
        }
        if (!EN_EL_PUNTO.contains(pedido.estado())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "ESTADO_INVALIDO", switch (pedido.estado()) {
                case "ENTREGADO" -> "Este pedido ya fue entregado: lo malogrado después de la entrega es una devolución.";
                case "EN_CAMINO" -> "Este pedido ya salió del punto con el distribuidor.";
                case "CANCELADO" -> "Este pedido fue cancelado.";
                default -> "Este pedido aún no se ha pagado.";
            });
        }
        Item item = item(pedido, itemId, true);
        BigDecimal cantidad = d.cantidad().setScale(3, RoundingMode.UNNECESSARY);
        Causa causa = causa(d);

        // El lote malogrado debe ser uno de los de la linea, y no mas de lo que aun cubre de ella
        MapSqlParameterSource p = new MapSqlParameterSource("item", item.id()).addValue("lote", d.loteId());
        BigDecimal asignado = jdbc.query("SELECT cantidad FROM (" + NETO_POR_LOTE + ") n WHERE n.lote_id = :lote", p,
                (rs, row) -> rs.getBigDecimal("cantidad")).stream().findFirst().orElse(BigDecimal.ZERO);
        if (asignado.signum() <= 0) {
            throw ErrorNegocio.noEncontrado("LOTE_NO_ASIGNADO", "Ese lote no está asignado a esta línea del pedido.");
        }
        if (cantidad.compareTo(asignado) > 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CANTIDAD_EXCEDE",
                    "De ese lote la línea tiene " + texto(asignado) + ": lo malogrado no puede ser mayor.");
        }

        // Todo se valida antes de escribir: un rechazo no deja reintegro ni merma a medias
        Candidato reemplazo = d.accion() == Accion.REEMPLAZAR ? validarReemplazo(pedido, item, d, cantidad) : null;
        Plan plan = d.accion() == Accion.REEMBOLSAR ? planearReembolso(pedido, item, cantidad) : null;
        long merma = darDeBaja(usuarioId, pedido, item, d.loteId(), cantidad, causa, d.detalle());
        return reemplazo != null
                ? reemplazar(usuarioId, pedido, item, d, cantidad, merma, reemplazo)
                : reembolsar(usuarioId, pedido, item, d, cantidad, merma, plan);
    }

    private record Candidato(long id, long productoId, long ubicacionId, BigDecimal disponible, boolean vencido) {
    }

    private record Cobro(long id, String referencia) {
    }

    /** Lo que se devolvera: el monto de la linea, la comision anulada, si se anula el pedido y a que pago. */
    private record Plan(BigDecimal monto, BigDecimal comision, boolean anula, BigDecimal devolver, Cobro pago) {
    }

    /** CCPIP-485: el lote de reemplazo es de este producto en el punto, vigente, otro y alcanza. */
    private Candidato validarReemplazo(Pedido pedido, Item item, Malogrado d, BigDecimal cantidad) {
        if (d.loteReemplazoId() == null) {
            throw ErrorNegocio.invalido("LOTE_REEMPLAZO_REQUERIDO", "Elige el lote con que se reemplaza.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("lote", d.loteReemplazoId()).addValue("item", item.id())
                .addValue("producto", item.productoId()).addValue("ubicacion", pedido.ubicacionId())
                .addValue("cantidad", cantidad);
        Candidato lote = jdbc.query("""
                SELECT id, producto_id, ubicacion_id, cantidad_actual,
                       (fecha_vencimiento IS NOT NULL AND fecha_vencimiento < %s) AS vencido
                FROM lote WHERE id = :lote FOR UPDATE""".formatted(HOY), p,
                (rs, row) -> new Candidato(rs.getLong("id"), rs.getLong("producto_id"), rs.getLong("ubicacion_id"),
                        rs.getBigDecimal("cantidad_actual"), rs.getBoolean("vencido")))
                .stream().findFirst()
                .filter(l -> l.productoId() == item.productoId() && l.ubicacionId() == pedido.ubicacionId())
                .orElseThrow(() -> ErrorNegocio.noEncontrado("LOTE_NO_DISPONIBLE",
                        "Ese lote no es de este producto en el punto del pedido."));
        if (lote.id() == d.loteId()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "MISMO_LOTE", "Elige un lote distinto del que se malogró.");
        }
        if (lote.vencido()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "LOTE_VENCIDO", "Ese lote está vencido: no se puede entregar.");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM movimiento_inventario
                               WHERE item_pedido_id = :item AND lote_id = :lote AND tipo = 'SALIDA_VENTA')""", p, Boolean.class))) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "LOTE_YA_EN_LINEA", "Ese lote ya cubre esta línea: elige otro.");
        }
        if (lote.disponible().compareTo(cantidad) < 0) {
            throw saldoInsuficiente(lote);
        }
        return lote;
    }

    /** CCPIP-485: el lote nuevo cubre lo malogrado; el precio del pedido no cambia. */
    private Resultado reemplazar(long usuarioId, Pedido pedido, Item item, Malogrado d, BigDecimal cantidad, long merma,
                                 Candidato lote) {
        MapSqlParameterSource p = new MapSqlParameterSource("lote", lote.id()).addValue("item", item.id())
                .addValue("producto", item.productoId()).addValue("ubicacion", pedido.ubicacionId())
                .addValue("cantidad", cantidad);
        // RNF-09: nunca por debajo de cero, ni en el lote ni en el stock del punto (aun con otra venta a la vez)
        if (jdbc.update("UPDATE lote SET cantidad_actual = cantidad_actual - :cantidad WHERE id = :lote AND cantidad_actual >= :cantidad", p) == 0
                || jdbc.update("""
                        UPDATE stock SET cantidad = cantidad - :cantidad, actualizado_en = now()
                        WHERE producto_id = :producto AND ubicacion_id = :ubicacion AND cantidad >= :cantidad""", p) == 0) {
            throw saldoInsuficiente(lote);
        }
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, pedido_id,
                                                   item_pedido_id, usuario_id, observacion)
                VALUES (:producto, :ubicacion, :lote, 'SALIDA_VENTA', :cantidad, :pedido, :item, :usuario,
                        'Reemplazo de producto malogrado del pedido ' || :numero)""",
                p.addValue("pedido", pedido.id()).addValue("usuario", usuarioId).addValue("numero", pedido.numero()));
        long id = incidencia(usuarioId, pedido, item, "REEMPLAZO", cantidad, d.loteId(), lote.id(), merma,
                BigDecimal.ZERO, BigDecimal.ZERO, null, d.clave());
        return new Resultado(id, "REEMPLAZO", cantidad, BigDecimal.ZERO, false, false, pedidos.detalle(pedido.id()));
    }

    /**
     * CCPIP-486: lo malogrado no se entrega y se devuelve al comprador: precio unitario por cantidad. La
     * comision baja en la misma proporcion. Si ya no se entrega nada, el pedido se anula y se devuelve el
     * resto del pago, envio incluido.
     */
    private Plan planearReembolso(Pedido pedido, Item item, BigDecimal cantidad) {
        MapSqlParameterSource p = new MapSqlParameterSource("item", item.id()).addValue("pedido", pedido.id());
        record Previo(BigDecimal cantidad, BigDecimal monto, BigDecimal comision) {
        }
        Previo previo = jdbc.queryForObject("""
                SELECT cantidad_faltante, monto_reembolsado, comision_anulada FROM ajuste_item_pedido
                WHERE item_pedido_id = :item""", p,
                (rs, row) -> new Previo(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
        boolean lineaCompleta = previo.cantidad().add(cantidad).compareTo(item.cantidad()) == 0;
        // Lo que falta de la linea entera se devuelve exacto, sin restos de redondeo
        BigDecimal monto = lineaCompleta ? item.subtotal().subtract(previo.monto())
                : item.precioUnitario().multiply(cantidad).setScale(2, RoundingMode.HALF_UP);
        BigDecimal comision = item.comision() == null ? BigDecimal.ZERO : lineaCompleta
                ? item.comision().subtract(previo.comision())
                : item.comision().multiply(cantidad).divide(item.cantidad(), 2, RoundingMode.HALF_UP);
        comision = comision.min(monto);
        if (monto.signum() <= 0) {
            throw ErrorNegocio.invalido("CANTIDAD_MUY_PEQUENA", "La cantidad es demasiado pequeña para reembolsarla.");
        }

        // Si con esto ya no se entrega nada, se anula el pedido y se devuelve tambien el envio
        BigDecimal porEntregar = jdbc.queryForObject("""
                SELECT coalesce(sum(i.cantidad - a.cantidad_faltante), 0) FROM item_pedido i
                JOIN ajuste_item_pedido a ON a.item_pedido_id = i.id WHERE i.pedido_id = :pedido""", p, BigDecimal.class);
        boolean anula = porEntregar.subtract(cantidad).signum() == 0;
        BigDecimal devolver = anula ? monto.add(pedido.costoEnvio()) : monto;
        Cobro pago = jdbc.query("""
                SELECT id, referencia_pasarela FROM pago WHERE pedido_id = :pedido AND estado = 'APROBADO' FOR UPDATE""", p,
                (rs, row) -> new Cobro(rs.getLong("id"), rs.getString("referencia_pasarela"))).stream().findFirst()
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT, "SIN_PAGO_APROBADO",
                        "Este pedido no tiene un pago aprobado que reembolsar."));
        return new Plan(monto, comision, anula, devolver, pago);
    }

    private Resultado reembolsar(long usuarioId, Pedido pedido, Item item, Malogrado d, BigDecimal cantidad, long merma,
                                 Plan plan) {
        MapSqlParameterSource p = new MapSqlParameterSource("item", item.id()).addValue("pedido", pedido.id());
        Cobro pago = plan.pago();
        BigDecimal devolver = plan.devolver();
        boolean anula = plan.anula();
        // La pasarela simulada no mueve dinero; una real devolveria aqui y se reintentaria si falla
        pasarela.devolver(pago.referencia(), devolver);
        Long reembolso = jdbc.queryForObject("""
                INSERT INTO reembolso (pago_id, pedido_id, monto, pasarela, referencia_pasarela, registrado_por)
                VALUES (:pago, :pedido, :monto, :pasarela, :referencia, :usuario) RETURNING id""",
                p.addValue("pago", pago.id()).addValue("monto", devolver).addValue("pasarela", pasarela.nombre())
                        .addValue("referencia", "R-" + pago.referencia() + "-" + UUID.randomUUID().toString().substring(0, 8))
                        .addValue("usuario", usuarioId), Long.class);
        long id = incidencia(usuarioId, pedido, item, "FALTANTE", cantidad, d.loteId(), null, merma, plan.monto(),
                plan.comision(), reembolso, d.clave());
        if (anula) {
            MapSqlParameterSource q = new MapSqlParameterSource("id", pedido.id()).addValue("usuario", usuarioId);
            jdbc.update("UPDATE pedido SET estado = 'CANCELADO', actualizado_en = now() WHERE id = :id", q);
            jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id) VALUES (:id, 'CANCELADO', :usuario)", q);
        }
        return new Resultado(id, "FALTANTE", cantidad, devolver, anula, false, pedidos.detalle(pedido.id()));
    }

    // ---------- Apoyo ----------

    private ErrorNegocio saldoInsuficiente(Candidato lote) {
        return new ErrorNegocio(HttpStatus.CONFLICT, "SALDO_INSUFICIENTE",
                "Ese lote tiene " + texto(lote.disponible()) + ": no alcanza para el reemplazo.");
    }

    /** Reintegro y merma del lote malogrado, juntos: el saldo del lote no cambia. Devuelve la merma. */
    private long darDeBaja(long usuarioId, Pedido pedido, Item item, long loteId, BigDecimal cantidad, Causa causa,
                           String detalle) {
        MapSqlParameterSource p = new MapSqlParameterSource("producto", item.productoId())
                .addValue("ubicacion", pedido.ubicacionId()).addValue("lote", loteId).addValue("cantidad", cantidad)
                .addValue("pedido", pedido.id()).addValue("item", item.id()).addValue("usuario", usuarioId)
                .addValue("numero", pedido.numero());
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, motivo, cantidad, pedido_id,
                                                   item_pedido_id, usuario_id, observacion)
                VALUES (:producto, :ubicacion, :lote, 'ENTRADA', 'REINTEGRO_PEDIDO', :cantidad, :pedido, :item, :usuario,
                        'Reintegro del pedido ' || :numero || ' para darlo de baja')""", p);
        Long movimiento = jdbc.queryForObject("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, pedido_id, usuario_id,
                                                   observacion)
                VALUES (:producto, :ubicacion, :lote, 'MERMA', :cantidad, :pedido, :usuario, :observacion) RETURNING id""",
                p.addValue("observacion", "Merma: " + causa.nombre() + " · pedido " + pedido.numero()), Long.class);
        String texto = detalle == null || detalle.isBlank() ? null : detalle.trim();
        jdbc.update("""
                INSERT INTO merma (movimiento_id, lote_id, motivo_id, motivo_nombre, motivo_nombre_en, detalle, automatica,
                                   registrado_por)
                VALUES (:movimiento, :lote, :motivo, :nombre, :nombreEn, :detalle, false, :usuario)""",
                p.addValue("movimiento", movimiento).addValue("motivo", causa.id()).addValue("nombre", causa.nombre())
                        .addValue("nombreEn", causa.nombreEn()).addValue("detalle", texto));
        return movimiento;
    }

    private long incidencia(long usuarioId, Pedido pedido, Item item, String tipo, BigDecimal cantidad, long loteOriginal,
                            Long loteNuevo, long merma, BigDecimal monto, BigDecimal comision, Long reembolso, UUID clave) {
        return jdbc.queryForObject("""
                INSERT INTO incidencia_pedido (pedido_id, item_pedido_id, tipo, cantidad, lote_original_id, lote_nuevo_id,
                                               merma_movimiento_id, monto, comision_anulada, reembolso_id, registrado_por,
                                               clave_idempotencia)
                VALUES (:pedido, :item, :tipo, :cantidad, :original, :nuevo, :merma, :monto, :comision, :reembolso, :usuario,
                        :clave) RETURNING id""",
                new MapSqlParameterSource("pedido", pedido.id()).addValue("item", item.id()).addValue("tipo", tipo)
                        .addValue("cantidad", cantidad).addValue("original", loteOriginal).addValue("nuevo", loteNuevo)
                        .addValue("merma", merma).addValue("monto", monto).addValue("comision", comision)
                        .addValue("reembolso", reembolso).addValue("usuario", usuarioId).addValue("clave", clave), Long.class);
    }

    private Optional<Resultado> previa(long usuarioId, UUID clave, Pedido pedido) {
        return jdbc.query("""
                SELECT f.id, f.tipo, f.cantidad, f.pedido_id, coalesce(r.monto, 0) AS reembolsado
                FROM incidencia_pedido f LEFT JOIN reembolso r ON r.id = f.reembolso_id
                WHERE f.registrado_por = :usuario AND f.clave_idempotencia = :clave""",
                new MapSqlParameterSource("usuario", usuarioId).addValue("clave", clave), (rs, row) -> {
                    if (rs.getLong("pedido_id") != pedido.id()) {
                        throw ErrorNegocio.invalido("ENVIO_REUTILIZADO", "Ese envío ya se usó para otro pedido.");
                    }
                    return new Resultado(rs.getLong("id"), rs.getString("tipo"), rs.getBigDecimal("cantidad"),
                            rs.getBigDecimal("reembolsado"), "CANCELADO".equals(pedido.estado()), true,
                            pedidos.detalle(pedido.id()));
                }).stream().findFirst();
    }

    /** El pedido entre los de los puntos del administrador, bloqueado si se va a cambiar. Otro punto: 404. */
    private Pedido pedido(long usuarioId, String numero, boolean bloquear) {
        return jdbc.query("""
                SELECT pe.id, pe.numero, pe.estado, pe.ubicacion_origen_id, pe.costo_envio FROM pedido pe
                WHERE %s AND upper(pe.numero) = upper(:numero)%s""".formatted(PedidosPuntoService.EN_SU_PUNTO,
                        bloquear ? " FOR UPDATE OF pe" : ""),
                new MapSqlParameterSource("usuario", usuarioId).addValue("numero", numero),
                (rs, row) -> new Pedido(rs.getLong("id"), rs.getString("numero"), rs.getString("estado"),
                        rs.getLong("ubicacion_origen_id"), rs.getBigDecimal("costo_envio")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe en tu punto de acopio."));
    }

    private Item item(Pedido pedido, long itemId, boolean bloquear) {
        return jdbc.query("""
                SELECT i.id, i.producto_id, pr.nombre, i.cantidad, i.precio_unitario, i.subtotal, i.comision_monto
                FROM item_pedido i JOIN producto pr ON pr.id = i.producto_id
                WHERE i.id = :item AND i.pedido_id = :pedido%s""".formatted(bloquear ? " FOR UPDATE OF i" : ""),
                new MapSqlParameterSource("item", itemId).addValue("pedido", pedido.id()),
                (rs, row) -> new Item(rs.getLong("id"), rs.getLong("producto_id"), rs.getString("nombre"),
                        rs.getBigDecimal("cantidad"), rs.getBigDecimal("precio_unitario"), rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("comision_monto")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("LINEA_NO_ENCONTRADA", "Esa línea no es de este pedido."));
    }

    private BigDecimal faltante(long itemId) {
        return jdbc.queryForObject("SELECT cantidad_faltante FROM ajuste_item_pedido WHERE item_pedido_id = :item",
                new MapSqlParameterSource("item", itemId), BigDecimal.class);
    }

    private List<LoteDeLinea> lotesDeLinea(long itemId) {
        return jdbc.query("""
                SELECT l.id, l.codigo, n.cantidad, l.fecha_vencimiento FROM (%s) n JOIN lote l ON l.id = n.lote_id
                WHERE n.cantidad > 0 ORDER BY l.fecha_vencimiento NULLS LAST, l.id""".formatted(NETO_POR_LOTE),
                new MapSqlParameterSource("item", itemId),
                (rs, row) -> new LoteDeLinea(rs.getLong("id"), rs.getString("codigo"), rs.getBigDecimal("cantidad"),
                        rs.getObject("fecha_vencimiento", LocalDate.class)));
    }

    /** Vigentes, con saldo, del mismo producto y punto, que aun no cubren la linea; el que vence antes primero. */
    private List<LoteReemplazo> reemplazos(Pedido pedido, Item item) {
        return jdbc.query("""
                SELECT l.id, l.codigo, l.cantidad_actual, l.fecha_vencimiento FROM lote l
                WHERE l.producto_id = :producto AND l.ubicacion_id = :ubicacion AND l.cantidad_actual > 0
                  AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= %s)
                  AND NOT EXISTS (SELECT 1 FROM movimiento_inventario m
                                  WHERE m.item_pedido_id = :item AND m.lote_id = l.id AND m.tipo = 'SALIDA_VENTA')
                ORDER BY l.fecha_vencimiento NULLS LAST, l.id""".formatted(HOY),
                new MapSqlParameterSource("producto", item.productoId()).addValue("ubicacion", pedido.ubicacionId())
                        .addValue("item", item.id()),
                (rs, row) -> new LoteReemplazo(rs.getLong("id"), rs.getString("codigo"), rs.getBigDecimal("cantidad_actual"),
                        rs.getObject("fecha_vencimiento", LocalDate.class)));
    }

    private Causa causa(Malogrado d) {
        Causa causa = jdbc.query("SELECT id, nombre, nombre_en, requiere_detalle FROM motivo_merma WHERE id = :id AND activo",
                new MapSqlParameterSource("id", d.motivoId()),
                (rs, row) -> new Causa(rs.getLong("id"), rs.getString("nombre"), rs.getString("nombre_en"),
                        rs.getBoolean("requiere_detalle"))).stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.invalido("CAUSA_INVALIDA", "Elige una causa de merma vigente."));
        if (causa.requiereDetalle() && (d.detalle() == null || d.detalle().isBlank())) {
            throw ErrorNegocio.invalido("DETALLE_REQUERIDO", "Describe la causa de la merma.");
        }
        return causa;
    }

    private static String texto(BigDecimal cantidad) {
        return cantidad.stripTrailingZeros().toPlainString();
    }
}
