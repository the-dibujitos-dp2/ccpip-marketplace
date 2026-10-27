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
class DistribucionEnCaminoTest extends IntegrationTest {
    private static final String NUMERO = "CAM-3181";
    private Cookie distribuidor;
    private long pedido;
    private long producto;

    @BeforeEach
    void fixture() throws Exception {
        distribuidor = loginAs("distribuidor@ccpip.test");
        producto = productoConLote("Producto " + NUMERO, "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        pedido = pedidoConfirmado(NUMERO, "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET modalidad = 'ENVIO', estado = 'LISTO_PARA_DESPACHO' WHERE id = ?", pedido);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, 'LISTO_PARA_DESPACHO')", pedido);
        jdbc.update("INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular) VALUES (?, '150122', 'Calle Secreta 999', 'Persona Reservada', '999888777')", pedido);
        mockMvc.perform(post("/api/distribuidor/pedidos/{n}/asumir", NUMERO).cookie(distribuidor)).andExpect(status().isOk());
        mockMvc.perform(post("/api/distribuidor/pedidos/{n}/retirar", NUMERO).cookie(distribuidor)).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions enCamino(Cookie sesion, String numero) throws Exception {
        var req = post("/api/distribuidor/pedidos/{n}/en-camino", numero);
        return mockMvc.perform(sesion == null ? req : req.cookie(sesion));
    }

    @Test
    void transicionAtomicaConActorFechaHistorialVisibilidadYStockInvariante() throws Exception {
        BigDecimal stock = jdbc.queryForObject("SELECT coalesce(sum(cantidad_actual),0) FROM lote WHERE producto_id=?", BigDecimal.class, producto);
        Integer movimientos = jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class);
        String respuesta = enCamino(distribuidor, NUMERO).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_CAMINO")).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("EN_CAMINO");
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id=?", String.class, pedido)).isEqualTo("EN_CAMINO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id=? AND estado='EN_CAMINO'", Integer.class, pedido)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT usuario_id FROM pedido_estado_historial WHERE pedido_id=? AND estado='EN_CAMINO'", Long.class, pedido)).isEqualTo(jdbc.queryForObject("SELECT id FROM usuario WHERE correo='distribuidor@ccpip.test'", Long.class));
        assertThat(jdbc.queryForObject("SELECT registrado_en <= now() FROM pedido_estado_historial WHERE pedido_id=? AND estado='EN_CAMINO'", Boolean.class, pedido)).isTrue();
        enCamino(distribuidor, NUMERO).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(respuesta));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id=? AND estado='EN_CAMINO'", Integer.class, pedido)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(cantidad_actual),0) FROM lote WHERE producto_id=?", BigDecimal.class, producto)).isEqualByComparingTo(stock);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class)).isEqualTo(movimientos);
        mockMvc.perform(get("/api/pedidos/{id}", pedido).cookie(loginAs("consumidor@ccpip.test"))).andExpect(jsonPath("$.entrega.estado").value("EN_CAMINO"));
        assertThat(respuesta).isNotBlank();
    }

    @Test
    void permisosYPropiedad() throws Exception {
        enCamino(null, NUMERO).andExpect(status().isUnauthorized());
        enCamino(loginAs("consumidor@ccpip.test"), NUMERO).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado) SELECT 'distribuidor2@ccpip.test', contrasena_hash, 'Otro', rol_id, true FROM usuario WHERE correo='distribuidor@ccpip.test'");
        enCamino(loginAs("distribuidor2@ccpip.test"), NUMERO).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PEDIDO_NO_ASIGNADO"));
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("LISTO_PARA_DESPACHO");
    }

    @Test
    void invalidoInexistenteRecojoYEstadosNoPermitidos() throws Exception {
        enCamino(distribuidor, "CAM 1;--").andExpect(status().isNotFound());
        enCamino(distribuidor, "CAM-99999").andExpect(status().isNotFound());
        long recojo = pedidoConfirmado("CAM-3182", "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET estado='LISTO_PARA_RECOJO' WHERE id=?", recojo);
        enCamino(distribuidor, "CAM-3182").andExpect(status().isNotFound());
        jdbc.update("UPDATE pedido SET estado='CANCELADO' WHERE id=?", pedido);
        enCamino(distribuidor, NUMERO).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id=?", String.class, pedido)).isEqualTo("RETIRADA");
    }
}
