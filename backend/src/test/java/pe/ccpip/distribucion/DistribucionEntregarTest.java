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
    void recojoResponde404SinMutarPedidoEntregaEInventario() throws Exception {
        jdbc.update("UPDATE pedido SET modalidad='RECOJO' WHERE id=?", pedido);
        var lotesAntes = jdbc.queryForList("SELECT * FROM lote ORDER BY id");
        var movimientosAntes = jdbc.queryForList("SELECT * FROM movimiento_inventario ORDER BY id");
        var historialAntes = jdbc.queryForList("SELECT * FROM pedido_estado_historial WHERE pedido_id=? ORDER BY id", pedido);
        entregar(distribuidor, NUMERO).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("EN_CAMINO");
        assertThat(jdbc.queryForObject("SELECT modalidad FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("RECOJO");
        assertThat(jdbc.queryForList("SELECT * FROM lote ORDER BY id")).isEqualTo(lotesAntes);
        assertThat(jdbc.queryForList("SELECT * FROM movimiento_inventario ORDER BY id")).isEqualTo(movimientosAntes);
        assertThat(jdbc.queryForList("SELECT * FROM pedido_estado_historial WHERE pedido_id=? ORDER BY id", pedido)).isEqualTo(historialAntes);
        assertThat(jdbc.queryForObject("SELECT entregada_en FROM entrega WHERE pedido_id=?", java.sql.Timestamp.class, pedido)).isNull();
    }

    @Test
    void retryConservaFechaEntregaYFechaDeHistorial() throws Exception {
        entregar(distribuidor, NUMERO).andExpect(status().isOk());
        var entregaAntes = jdbc.queryForObject("SELECT entregada_en FROM entrega WHERE pedido_id=?", java.sql.Timestamp.class, pedido);
        var historialAntes = jdbc.queryForObject("SELECT registrado_en FROM pedido_estado_historial WHERE pedido_id=? AND estado='ENTREGADO'", java.sql.Timestamp.class, pedido);
        assertThat(entregaAntes).isNotNull();
        assertThat(historialAntes).isNotNull();
        entregar(distribuidor, NUMERO).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT entregada_en FROM entrega WHERE pedido_id=?", java.sql.Timestamp.class, pedido)).isEqualTo(entregaAntes);
        assertThat(jdbc.queryForObject("SELECT registrado_en FROM pedido_estado_historial WHERE pedido_id=? AND estado='ENTREGADO'", java.sql.Timestamp.class, pedido)).isEqualTo(historialAntes);
    }

    @Test
    void permisosRolesAjenoYEstadosNoValidosNoMutanPedidoNiEntrega() throws Exception {
        for (String email : new String[]{"productor@ccpip.test", "admin_acopio@ccpip.test", "sub_admin@ccpip.test", "sudo@ccpip.test"}) {
            var respuesta = entregar(loginAs(email), NUMERO).andReturn().getResponse();
            assertThat(respuesta.getStatus()).as(email).isEqualTo(403);
            assertSinCambios();
        }
        jdbc.update("UPDATE entrega SET distribuidor_id=NULL WHERE pedido_id=?", pedido);
        entregar(distribuidor, NUMERO).andExpect(status().isConflict());
        assertSinCambios();
        jdbc.update("UPDATE entrega SET distribuidor_id=(SELECT id FROM usuario WHERE correo='distribuidor@ccpip.test'), estado='ASIGNADA' WHERE pedido_id=?", pedido);
        jdbc.update("UPDATE pedido SET estado='CANCELADO' WHERE id=?", pedido);
        entregar(distribuidor, NUMERO).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("CANCELADO");
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id=?", String.class, pedido)).isEqualTo("ASIGNADA");
    }

    @Test
    void rechazaParDeEstadosDesincronizadoSinMutarNiInventario() throws Exception {
        BigDecimal existencias = jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id=?", BigDecimal.class, producto);
        jdbc.update("UPDATE pedido SET estado='LISTO_PARA_DESPACHO' WHERE id=?", pedido);
        entregar(distribuidor, NUMERO).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("LISTO_PARA_DESPACHO");
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id=?", String.class, pedido)).isEqualTo("EN_CAMINO");
        assertThat(jdbc.queryForObject("SELECT entregada_en FROM entrega WHERE pedido_id=?", java.sql.Timestamp.class, pedido)).isNull();
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id=?", BigDecimal.class, producto)).isEqualByComparingTo(existencias);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id=? AND estado='ENTREGADO'", Integer.class, pedido)).isZero();
    }

    private void assertSinCambios() {
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("EN_CAMINO");
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id=?", String.class, pedido)).isEqualTo("EN_CAMINO");
        assertThat(jdbc.queryForObject("SELECT entregada_en FROM entrega WHERE pedido_id=?", java.sql.Timestamp.class, pedido)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id=? AND estado='ENTREGADO'", Integer.class, pedido)).isZero();
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
