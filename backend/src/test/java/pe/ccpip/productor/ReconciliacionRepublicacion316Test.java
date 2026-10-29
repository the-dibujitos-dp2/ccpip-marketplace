package pe.ccpip.productor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/** Evidencia complementaria CCPIP-316: la autorización vencida tampoco habilita republicar. */
@Transactional
class ReconciliacionRepublicacion316Test extends IntegrationTest {

    @Test
    void soloRepublicaConAutorizacionVigenteYNoAlteraPedidosSiVence() throws Exception {
        Cookie productor = loginAs("productor@ccpip.test");
        LocalDate vigencia = LocalDate.now(ZoneId.of("America/Lima")).plusDays(90);
        long vigente = productoConLote("Oferta CCPIP-316 vigente", "RETIRADO", "PUNTO_ACOPIO", vigencia);
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", vigente).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_MODERACION"));
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, vigente))
                .isEqualTo("EN_MODERACION");

        long vencida = productoConLote("Oferta CCPIP-316 vencida", "RETIRADO", "PUNTO_ACOPIO", vigencia);
        long pedido = pedidoConfirmado("MC-CCPIP316", "Centro Nacional Lima", vencida, 1);
        jdbc.update("UPDATE autorizacion_producto SET vigente_hasta = ? WHERE producto_id = ?",
                LocalDate.now(ZoneId.of("America/Lima")).minusDays(1), vencida);

        mockMvc.perform(post("/api/productor/productos/{id}/publicar", vencida).cookie(productor))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_PUBLICABLE"))
                .andExpect(jsonPath("$.detalles").value(org.hamcrest.Matchers.hasItem("Una autorización de venta vigente")));
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, vencida))
                .isEqualTo("RETIRADO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_pedido WHERE pedido_id = ? AND producto_id = ?",
                Integer.class, pedido, vencida)).isEqualTo(1);
    }
}
