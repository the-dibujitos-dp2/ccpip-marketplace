package pe.ccpip.distribucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

@Transactional
class DistribucionEntregarTest extends IntegrationTest {
    private static final String NUMERO = "ENT-3191";
    private Cookie distribuidor;
    private long pedido;
    private long producto;

    @BeforeEach
    void fixture() throws Exception {
        distribuidor = loginAs("distribuidor@ccpip.test");
        producto = productoConLote("Producto " + NUMERO, "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        pedido = pedidoConfirmado(NUMERO, "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET modalidad='ENVIO', estado='LISTO_PARA_DESPACHO' WHERE id=?", pedido);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, 'LISTO_PARA_DESPACHO')", pedido);
        jdbc.update("INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular) VALUES (?, '150122', 'Calle 1', 'Reservado', '999888777')", pedido);
        mockMvc.perform(post("/api/distribuidor/pedidos/{n}/asumir", NUMERO).cookie(distribuidor)).andExpect(status().isOk());
        mockMvc.perform(post("/api/distribuidor/pedidos/{n}/retirar", NUMERO).cookie(distribuidor)).andExpect(status().isOk());
        mockMvc.perform(post("/api/distribuidor/pedidos/{n}/en-camino", NUMERO).cookie(distribuidor)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions entregar(Cookie sesion, String numero) throws Exception {
        var req = post("/api/distribuidor/pedidos/{n}/entregar", numero);
        return mockMvc.perform(sesion == null ? req : req.cookie(sesion));
    }

    @Test
    void entregaAtomicaIdempotenteVisibleSinCambiarStock() throws Exception {
        BigDecimal stock = jdbc.queryForObject("SELECT coalesce(sum(cantidad_actual),0) FROM lote WHERE producto_id=?", BigDecimal.class, producto);
        Integer movs = jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class);
        String body = entregar(distribuidor, NUMERO).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ENTREGADA")).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("ENTREGADO");
        assertThat(jdbc.queryForObject("SELECT entregada_en FROM entrega WHERE pedido_id=?", java.sql.Timestamp.class, pedido)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id=? AND estado='ENTREGADO'", Integer.class, pedido)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT usuario_id FROM pedido_estado_historial WHERE pedido_id=? AND estado='ENTREGADO'", Long.class, pedido)).isEqualTo(jdbc.queryForObject("SELECT id FROM usuario WHERE correo='distribuidor@ccpip.test'", Long.class));
        entregar(distribuidor, NUMERO).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(body));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id=? AND estado='ENTREGADO'", Integer.class, pedido)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(cantidad_actual),0) FROM lote WHERE producto_id=?", BigDecimal.class, producto)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class)).isEqualTo(movs);
        mockMvc.perform(get("/api/pedidos/{id}", pedido).cookie(loginAs("consumidor@ccpip.test"))).andExpect(jsonPath("$.estado").value("ENTREGADO"));
        mockMvc.perform(get("/api/distribuidor/pedidos").cookie(distribuidor)).andExpect(jsonPath("$[?(@.numero == 'ENT-3191' && @.estado == 'ENTREGADA')] ").exists());
    }

    @Test
    void permisosPropiedadEstadosYNumero() throws Exception {
        entregar(null, NUMERO).andExpect(status().isUnauthorized());
        entregar(loginAs("consumidor@ccpip.test"), NUMERO).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado) SELECT 'distribuidor2@ccpip.test', contrasena_hash, 'Otro', rol_id, true FROM usuario WHERE correo='distribuidor@ccpip.test'");
        entregar(loginAs("distribuidor2@ccpip.test"), NUMERO).andExpect(status().isConflict());
        entregar(distribuidor, "bad 1").andExpect(status().isNotFound());
        entregar(distribuidor, "ENT-9999").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("EN_CAMINO");
    }
}
