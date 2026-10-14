package pe.ccpip.pago;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * Reglas del modelo de pago (CCPIP-19, CCPIP-148) que CCPIP-62, CCPIP-157 y CCPIP-163 dan por
 * garantizadas: sin doble cobro, cada intento registrado, estados finales que no se reescriben y
 * ninguna tarjeta guardada. Cada prueba se deshace al terminar.
 */
@Transactional
class PagoModeloTest extends IntegrationTest {

    private static final BigDecimal TOTAL = new BigDecimal("96.00");

    /** Un pedido de consumidor@ccpip.test que espera su pago, sin stock descontado. */
    private long pedidoPendienteDePago(String numero) {
        return jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, estado, total, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 'PENDIENTE_PAGO', ?, NULL
                FROM usuario u, ubicacion l
                WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, numero, TOTAL);
    }

    private long intento(long pedidoId, UUID clave, BigDecimal monto) {
        return jdbc.queryForObject("""
                INSERT INTO pago (pedido_id, clave_idempotencia, monto, pasarela, marca_tarjeta)
                VALUES (?, ?, ?, 'SIMULADA', 'VISA') RETURNING id""", Long.class, pedidoId, clave, monto);
    }

    private long intento(long pedidoId) {
        return intento(pedidoId, UUID.randomUUID(), TOTAL);
    }

    private int resolver(long pagoId, String estado) {
        return jdbc.update("""
                UPDATE pago SET estado = ?, resuelto_en = now(), referencia_pasarela = 'REF-' || id
                WHERE id = ?""", estado, pagoId);
    }

    @Test
    void unRechazoDejaReintentarYCadaIntentoQuedaRegistrado() {
        long pedido = pedidoPendienteDePago("T-PAGO-1");
        resolver(intento(pedido), "RECHAZADO");
        resolver(intento(pedido), "APROBADO");

        List<String> estados = jdbc.queryForList(
                "SELECT estado FROM pago WHERE pedido_id = ? ORDER BY id", String.class, pedido);

        assertThat(estados).containsExactly("RECHAZADO", "APROBADO");
    }

    @Test
    void elMismoIntentoEnviadoDosVecesNoSeRegistraDosVeces() {
        long pedido = pedidoPendienteDePago("T-PAGO-2");
        UUID clave = UUID.randomUUID();
        resolver(intento(pedido, clave, TOTAL), "ERROR");

        assertThatThrownBy(() -> intento(pedido, clave, TOTAL))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pago_clave_idempotencia_unica");
    }

    @Test
    void unPedidoNoSeCobraDosVeces() {
        long pedido = pedidoPendienteDePago("T-PAGO-3");
        resolver(intento(pedido), "APROBADO");
        long segundo = intento(pedido);

        assertThatThrownBy(() -> resolver(segundo, "APROBADO"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pago_un_aprobado_por_pedido_idx");
    }

    @Test
    void soloHayUnIntentoEnCursoALaVez() {
        long pedido = pedidoPendienteDePago("T-PAGO-4");
        intento(pedido);

        assertThatThrownBy(() -> intento(pedido))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pago_un_pendiente_por_pedido_idx");
    }

    @Test
    void unEstadoFinalNoSeReescribe() {
        long pedido = pedidoPendienteDePago("T-PAGO-5");
        long pago = intento(pedido);
        resolver(pago, "RECHAZADO");

        assertThatThrownBy(() -> resolver(pago, "APROBADO"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Transicion de pago no permitida");
    }

    @Test
    void unPagoNoSeBorra() {
        long pago = intento(pedidoPendienteDePago("T-PAGO-6"));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM pago WHERE id = ?", pago))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("no se borra");
    }

    @Test
    void elMontoDebeSerElTotalDelPedido() {
        long pedido = pedidoPendienteDePago("T-PAGO-7");

        assertThatThrownBy(() -> intento(pedido, UUID.randomUUID(), new BigDecimal("1.00")))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("igual al total del pedido");
    }

    @Test
    void unPedidoYaConfirmadoNoAceptaPagos() {
        long pedido = pedidoConfirmado("T-PAGO-8", "Centro Nacional Lima",
                jdbc.queryForObject("SELECT min(id) FROM producto", Long.class), 1);

        assertThatThrownBy(() -> intento(pedido))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PENDIENTE_PAGO");
    }

    @Test
    void unPedidoPendienteDePagoNoFiguraComoConfirmado() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, estado, total)
                SELECT 'T-PAGO-9', u.id, 'RECOJO', l.id, 'PENDIENTE_PAGO', 10
                FROM usuario u, ubicacion l
                WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'"""))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("pedido_confirmacion_coherente");
    }

    @Test
    void laTablaDePagoNoTieneDondeGuardarLaTarjeta() {
        List<String> columnas = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'pago'
                  AND (column_name ~ '(numero|pan|cvv|cvc|vencimiento|expira)' OR column_name LIKE '%tarjeta%')""",
                String.class);

        assertThat(columnas).containsExactly("marca_tarjeta");
    }
}
