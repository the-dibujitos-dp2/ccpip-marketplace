package pe.ccpip.productor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-66 (RF-48, Figma PW-07): el productor ve los pedidos que incluyen sus publicaciones y su
 * estado. Solo sus lineas: en un pedido mixto no ve lo que vendio otro productor, ni el total, ni
 * quien compro (RNF-05).
 */
@Transactional
class PedidosProductorTest extends IntegrationTest {

    static final String LIMA = "Centro Nacional Lima";
    static final LocalDate EN_UN_MES = LocalDate.now(ZoneId.of("America/Lima")).plusDays(30);

    private Cookie productor;
    private long propio;
    private long ajeno;

    @BeforeEach
    void preparar() throws Exception {
        productor = loginAs("productor@ccpip.test");
        propio = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        ajeno = productoConLote("Cacao de Yarina", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES, "productor2@ccpip.test");
    }

    @Test
    void veSoloSusLineasEnUnPedidoMixtoSinDatosDelComprador() throws Exception {
        long pedido = pedidoConfirmado("MC-T0066", LIMA, propio, 2);
        agregarLinea(pedido, ajeno, 3);
        jdbc.update("UPDATE item_pedido SET comision_monto = subtotal * 0.10, neto = subtotal * 0.90 WHERE pedido_id = ?", pedido);

        pedidos(productor, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].modalidad").value("RECOJO"))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].puntoRecojo").value(LIMA))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].lineas.length()").value(1))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].lineas[0].producto").value("Sacha inchi tostado"))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].lineas[0].cantidad").value(2.0))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].subtotal").value(40.0))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].comision").value(4.0))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].neto").value(36.0))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].historial[0].estado").value("CONFIRMADO"))
                .andExpect(content().string(not(containsString("Cacao de Yarina"))))
                .andExpect(content().string(not(containsString("consumidor@ccpip.test"))))
                .andExpect(content().string(not(containsString("\"total\""))))
                .andExpect(content().string(not(containsString("usuario"))));

        // El otro productor ve su linea del mismo pedido y no la ajena
        pedidos(loginAs("productor2@ccpip.test"), null)
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0066')].lineas[0].producto").value("Cacao de Yarina"))
                .andExpect(content().string(not(containsString("Sacha inchi tostado"))));
    }

    @Test
    void filtraPorEstadoYCuentaCadaPestana() throws Exception {
        pedidoConfirmado("MC-T0067", LIMA, propio, 1);
        long listo = pedidoConfirmado("MC-T0068", LIMA, propio, 1);
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO' WHERE id = ?", listo);
        int confirmados = contar("CONFIRMADO");
        int listos = contar("LISTO_PARA_RECOJO");

        pedidos(productor, "LISTO_PARA_RECOJO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].numero", hasItem("MC-T0068")))
                .andExpect(jsonPath("$.items[*].numero", not(hasItem("MC-T0067"))))
                .andExpect(jsonPath("$.items[*].estado", not(hasItem("CONFIRMADO"))))
                .andExpect(jsonPath("$.conteos.CONFIRMADO").value(confirmados))
                .andExpect(jsonPath("$.conteos.LISTO_PARA_RECOJO").value(listos))
                .andExpect(jsonPath("$.conteos.ENTREGADO").isNumber())
                .andExpect(jsonPath("$.conteos.CANCELADO").isNumber());
    }

    @Test
    void unPedidoADomicilioListoParaDespacharSeVeComoListoYNoRompeElConteo() throws Exception {
        long envio = pedidoConfirmado("MC-T0070", LIMA, propio, 1);
        jdbc.update("UPDATE pedido SET modalidad = 'ENVIO', estado = 'LISTO_PARA_DESPACHO' WHERE id = ?", envio);

        pedidos(productor, "LISTO_PARA_RECOJO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0070')].estado").value("LISTO_PARA_RECOJO"))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0070')].modalidad").value("ENVIO"))
                .andExpect(jsonPath("$.conteos.LISTO_PARA_RECOJO").value(contar("LISTO_PARA_RECOJO") + contar("LISTO_PARA_DESPACHO")))
                .andExpect(content().string(not(containsString("LISTO_PARA_DESPACHO"))));
        pedidos(productor, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].numero", hasItem("MC-T0070")));
    }
    @Test
    void unPedidoSinPagarNoEsUnaVenta() throws Exception {
        long pendiente = pedidoConfirmado("MC-T0069", LIMA, propio, 1);
        jdbc.update("UPDATE pedido SET estado = 'PENDIENTE_PAGO', confirmado_en = NULL WHERE id = ?", pendiente);

        pedidos(productor, null).andExpect(jsonPath("$.items[*].numero", not(hasItem("MC-T0069"))));
    }

    @Test
    void soloElProductorConsultaYConUnEstadoValido() throws Exception {
        pedidos(loginAs("consumidor@ccpip.test"), null).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/productor/pedidos")).andExpect(status().isUnauthorized());
        pedidos(productor, "PENDIENTE_PAGO").andExpect(status().isBadRequest());
        pedidos(productor, "XYZ").andExpect(status().isBadRequest());
    }

    private ResultActions pedidos(Cookie quien, String estado) throws Exception {
        var pedido = get("/api/productor/pedidos").cookie(quien);
        if (estado != null) {
            pedido.param("estado", estado);
        }
        return mockMvc.perform(pedido);
    }

    private void agregarLinea(long pedido, long producto, int cantidad) {
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
                SELECT ?, id, productor_id, ?, precio, precio * ? FROM producto WHERE id = ?""",
                pedido, cantidad, cantidad, producto);
    }

    private int contar(String estado) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pedido pe
                WHERE pe.estado = ? AND pe.confirmado_en IS NOT NULL
                  AND EXISTS (SELECT 1 FROM item_pedido i JOIN productor p ON p.id = i.productor_id
                              JOIN usuario u ON u.id = p.usuario_id
                              WHERE i.pedido_id = pe.id AND u.correo = 'productor@ccpip.test')""", Integer.class, estado);
    }
}
