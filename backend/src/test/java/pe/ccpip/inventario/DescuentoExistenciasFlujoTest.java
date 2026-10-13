package pe.ccpip.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.inventario.DescuentoExistenciasService.Descuento;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-158 (RF-127): al confirmar una compra se descuentan las existencias del punto de recojo,
 * de forma atomica e idempotente con el pedido, sin sobreventa ni negativos. Como la confirmacion
 * (CCPIP-157) aun no existe, cada prueba arma el pedido directamente en la base.
 */
@Transactional
class DescuentoExistenciasFlujoTest extends IntegrationTest {

    static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));

    @Autowired
    DescuentoExistenciasService descuento;

    @Test
    void descuentaPrimeroElLoteQueVenceAntesYDejaCadaSalidaExplicada() {
        long producto = productoConLote("Sacha inchi", "PUBLICADO", null, null);
        long tardio = lote(producto, "L-TARDE", 10, HOY.plusDays(90));
        long proximo = lote(producto, "L-PRONTO", 4, HOY.plusDays(10));
        long pedido = pedido("MC-T0001", producto, 6);

        List<Descuento> salidas = descuento.descontarPorPedido(pedido, comprador());

        // 4 del lote que vence antes y 2 del siguiente: un movimiento por lote
        assertThat(salidas).extracting(Descuento::loteId, d -> d.cantidad().intValue())
                .containsExactly(tuple(proximo, 4), tuple(tardio, 2));
        assertThat(cantidadLote(proximo)).isZero();
        assertThat(cantidadLote(tardio)).isEqualTo(8);
        assertThat(stock(producto)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM movimiento_inventario
                WHERE pedido_id = ? AND tipo = 'SALIDA_VENTA' AND item_pedido_id IS NOT NULL AND observacion = 'Venta del pedido MC-T0001'""",
                Integer.class, pedido)).isEqualTo(2);
    }

    // --- CCPIP-74 (RF-30, RNF-14): lotes vendibles por vencimiento mas proximo, nunca uno vencido ---

    @Test
    void unLoteVencidoNuncaSeAsignaAunqueSeaElQueVenceAntes() {
        long producto = productoConLote("Camu camu", "PUBLICADO", null, null);
        long vencido = lote(producto, "L-VENCIDO", 10, HOY.minusDays(1));
        long vigente = lote(producto, "L-VIGENTE", 10, HOY.plusDays(20));
        long pedido = pedido("MC-T0010", producto, 4);

        List<Descuento> salidas = descuento.descontarPorPedido(pedido, comprador());

        // Es el lote que vence "antes", pero ya vencio: todo sale del vigente y el vencido no se toca
        assertThat(salidas).extracting(Descuento::loteId, d -> d.cantidad().intValue()).containsExactly(tuple(vigente, 4));
        assertThat(cantidadLote(vencido)).isEqualTo(10);
        assertThat(cantidadLote(vigente)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ? AND tipo = 'SALIDA_VENTA'",
                Integer.class, vencido)).isZero();
    }

    @Test
    void elLoteQueVenceHoyTodaviaSeAsignaYSeCompletaConElSiguiente() {
        long producto = productoConLote("Miel", "PUBLICADO", null, null);
        long vencePasado = lote(producto, "L-SIGUIENTE", 5, HOY.plusDays(30));
        long venceHoy = lote(producto, "L-HOY", 3, HOY);
        long pedido = pedido("MC-T0011", producto, 4);

        List<Descuento> salidas = descuento.descontarPorPedido(pedido, comprador());

        // Hoy todavia es vendible (vence al terminar el dia): sale primero y el resto del siguiente lote
        assertThat(salidas).extracting(Descuento::loteId, d -> d.cantidad().intValue())
                .containsExactly(tuple(venceHoy, 3), tuple(vencePasado, 1));
    }

    @Test
    void loQueNoVenceSaleDespuesDeLoQueVence() {
        long producto = productoConLote("Canasta tejida", "PUBLICADO", null, null);
        long sinVencimiento = lote(producto, "L-ARTESANIA", 5, null);
        long conVencimiento = lote(producto, "L-FECHA", 2, HOY.plusDays(60));
        long pedido = pedido("MC-T0012", producto, 4);

        List<Descuento> salidas = descuento.descontarPorPedido(pedido, comprador());

        assertThat(salidas).extracting(Descuento::loteId, d -> d.cantidad().intValue())
                .containsExactly(tuple(conVencimiento, 2), tuple(sinVencimiento, 2));
    }

    @Test
    void cadaLineaConservaLosLotesDeLosQueSalio() {
        long producto = productoConLote("Cafe", "PUBLICADO", null, null);
        long otro = productoConLote("Cacao", "PUBLICADO", null, null);
        long l1 = lote(producto, "L-A", 2, HOY.plusDays(5));
        long l2 = lote(producto, "L-B", 5, HOY.plusDays(50));
        long l3 = lote(otro, "L-C", 9, HOY.plusDays(15));
        long pedido = pedido("MC-T0013", producto, 4);
        agregarItem(pedido, otro, 1);

        descuento.descontarPorPedido(pedido, comprador());

        // Trazabilidad: cada movimiento dice de que pedido, de que linea y de que lote salio
        assertThat(jdbc.queryForList("""
                SELECT m.lote_id, i.producto_id, m.cantidad::int AS cantidad
                FROM movimiento_inventario m JOIN item_pedido i ON i.id = m.item_pedido_id
                WHERE m.pedido_id = ? AND m.tipo = 'SALIDA_VENTA' ORDER BY m.id""", pedido))
                .extracting(f -> f.get("lote_id"), f -> f.get("producto_id"), f -> f.get("cantidad"))
                .containsExactly(tuple(l1, producto, 2), tuple(l2, producto, 2), tuple(l3, otro, 1));
    }

    @Test
    void confirmarDosVecesNoDescuentaDosVeces() {
        long producto = productoConLote("Cacao", "PUBLICADO", null, null);
        lote(producto, "L-1", 5, HOY.plusDays(30));
        long pedido = pedido("MC-T0002", producto, 3);

        List<Descuento> primera = descuento.descontarPorPedido(pedido, comprador());
        List<Descuento> segunda = descuento.descontarPorPedido(pedido, comprador());

        assertThat(segunda).isEqualTo(primera);
        assertThat(stock(producto)).isEqualTo(2);
    }

    @Test
    void laBaseRechazaUnSegundoDescuentoDeLaMismaLineaYLote() {
        long producto = productoConLote("Cacao", "PUBLICADO", null, null);
        long lote = lote(producto, "L-1", 5, HOY.plusDays(30));
        long pedido = pedido("MC-T0003", producto, 1);
        descuento.descontarPorPedido(pedido, comprador());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, pedido_id, item_pedido_id)
                SELECT ?, ubicacion_origen_id, ?, 'SALIDA_VENTA', 1, id, (SELECT id FROM item_pedido WHERE pedido_id = ?)
                FROM pedido WHERE id = ?""", producto, lote, pedido, pedido))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sinExistenciasSuficientesNoDescuentaNada() {
        long alcanza = productoConLote("Cafe", "PUBLICADO", null, null);
        long falta = productoConLote("Miel", "PUBLICADO", null, null);
        lote(alcanza, "L-1", 10, HOY.plusDays(30));
        lote(falta, "L-2", 2, HOY.plusDays(30));
        // Un lote vencido no cuenta, aunque tenga saldo
        lote(falta, "L-3", 5, HOY.minusDays(1));
        long pedido = pedido("MC-T0004", alcanza, 4);
        agregarItem(pedido, falta, 3);

        assertThatThrownBy(() -> descuento.descontarPorPedido(pedido, comprador()))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> {
                    assertThat(e.code()).isEqualTo("STOCK_INSUFICIENTE");
                    assertThat(e.getMessage()).contains("Miel");
                });
        // Ni siquiera la linea que alcanzaba se desconto: el pedido entero se rechaza (RNF-10)
        assertThat(stock(alcanza)).isEqualTo(10);
        assertThat(stock(falta)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE pedido_id = ?", Integer.class, pedido))
                .isZero();
    }

    @Test
    void soloDescuentaDelPuntoDeRecojoDelPedido() {
        long producto = productoConLote("Cafe", "PUBLICADO", null, null);
        // Hay saldo, pero en otro punto de acopio
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_vencimiento)
                SELECT ?, id, 'L-OTRO', 9, 9, ? FROM ubicacion WHERE tipo = 'PUNTO_ACOPIO' AND nombre <> 'Centro Nacional Lima' LIMIT 1""",
                producto, HOY.plusDays(30));
        long pedido = pedido("MC-T0005", producto, 1);

        assertThatThrownBy(() -> descuento.descontarPorPedido(pedido, comprador()))
                .isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void unPedidoQueNoExisteDevuelve404() {
        assertThatThrownBy(() -> descuento.descontarPorPedido(999_999_999L, comprador()))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> assertThat(e.code()).isEqualTo("PEDIDO_NO_ENCONTRADO"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void soloCorreDentroDeLaTransaccionDeLaConfirmacion() {
        assertThatThrownBy(() -> descuento.descontarPorPedido(999_999_999L, 1L))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------- Datos de prueba ----------

    /** Un lote en el Centro Nacional Lima, con su ENTRADA y su stock, como lo registra el productor. */
    long lote(long producto, String codigo, int cantidad, LocalDate vence) {
        Long id = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_vencimiento)
                SELECT ?, id, ?, ?, ?, ? FROM ubicacion WHERE nombre = 'Centro Nacional Lima' RETURNING id""",
                Long.class, producto, codigo, cantidad, cantidad, vence);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad)
                SELECT ?, ubicacion_id, id, 'ENTRADA', ? FROM lote WHERE id = ?""", producto, cantidad, id);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad)
                SELECT ?, id, ? FROM ubicacion WHERE nombre = 'Centro Nacional Lima'
                ON CONFLICT (producto_id, ubicacion_id) DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad""",
                producto, cantidad);
        return id;
    }

    /** Un pedido confirmado del consumidor de demostracion para recoger en el Centro Nacional Lima. */
    long pedido(String numero, long producto, int cantidad) {
        Long id = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total)
                SELECT ?, ?, 'RECOJO', id, 0 FROM ubicacion WHERE nombre = 'Centro Nacional Lima' RETURNING id""",
                Long.class, numero, comprador());
        agregarItem(id, producto, cantidad);
        return id;
    }

    void agregarItem(long pedido, long producto, int cantidad) {
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
                SELECT ?, id, productor_id, ?, precio, precio * ? FROM producto WHERE id = ?""",
                pedido, cantidad, cantidad, producto);
    }

    long comprador() {
        return jdbc.queryForObject("SELECT id FROM usuario WHERE correo = 'consumidor@ccpip.test'", Long.class);
    }

    int cantidadLote(long lote) {
        return jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE id = ?", BigDecimal.class, lote).intValue();
    }

    int stock(long producto) {
        return jdbc.queryForObject("""
                SELECT s.cantidad FROM stock s JOIN ubicacion u ON u.id = s.ubicacion_id
                WHERE s.producto_id = ? AND u.nombre = 'Centro Nacional Lima'""", BigDecimal.class, producto).intValue();
    }
}
