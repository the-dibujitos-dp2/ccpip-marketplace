package pe.ccpip.distribucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

@Transactional
class DistribucionUbicacionesTest extends IntegrationTest {
    private static final String NUMERO = "UBI-110";
    private Cookie distribuidor;
    private long pedido;
    private long producto;

    @BeforeEach
    void fixture() throws Exception {
        distribuidor = loginAs("distribuidor@ccpip.test");
        producto = productoConLote("Producto " + NUMERO, "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        pedido = pedidoConfirmado(NUMERO, "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET modalidad='ENVIO', estado='LISTO_PARA_DESPACHO' WHERE id=?", pedido);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, 'LISTO_PARA_DESPACHO')", pedido);
        jdbc.update("INSERT INTO entrega (pedido_id, ubigeo, direccion, referencia, contacto_nombre, contacto_celular) VALUES (?, '150122', 'Av. Direccion UBI-110 123', 'Puerta azul UBI-110', 'Reservado', '999888777')", pedido);
        mockMvc.perform(get("/api/distribuidor/pedidos").cookie(distribuidor)).andExpect(status().isOk());
        Long distribuidorId = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='distribuidor@ccpip.test'", Long.class);
        assertThat(jdbc.update("UPDATE entrega SET distribuidor_id=?, estado='ASIGNADA', asignada_en=now() WHERE pedido_id=?", distribuidorId, pedido)).isEqualTo(1);
    }

    @Test
    void devuelveUbicacionesRegistradasExactasDeEntregaPropia() throws Exception {
        mockMvc.perform(get("/api/distribuidor/pedidos/{numero}/ubicaciones", NUMERO).cookie(distribuidor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value(NUMERO))
                .andExpect(jsonPath("$.origen.direccion").value(jdbc.queryForObject("SELECT direccion FROM ubicacion WHERE id=(SELECT ubicacion_origen_id FROM pedido WHERE id=?)", String.class, pedido)))
                .andExpect(jsonPath("$.destino.territorio.ubigeo").value(jdbc.queryForObject("SELECT ubigeo FROM entrega WHERE pedido_id=?", String.class, pedido)))
                .andExpect(jsonPath("$.destino.direccion").value(jdbc.queryForObject("SELECT direccion FROM entrega WHERE pedido_id=?", String.class, pedido)))
                .andExpect(jsonPath("$.destino.referencia").value(jdbc.queryForObject("SELECT referencia FROM entrega WHERE pedido_id=?", String.class, pedido)));
    }
}
