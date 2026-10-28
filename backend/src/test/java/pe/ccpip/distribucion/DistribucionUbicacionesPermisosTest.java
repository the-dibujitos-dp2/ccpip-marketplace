package pe.ccpip.distribucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/** Characterization of CCPIP-110 authorization, privacy, and read-only behavior. */
@Transactional
class DistribucionUbicacionesPermisosTest extends IntegrationTest {
    private static final String RUTA = "/api/distribuidor/pedidos/{numero}/ubicaciones";
    private static final String NUMERO = "UP-110";
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
        jdbc.update("INSERT INTO entrega (pedido_id, ubigeo, direccion, referencia, contacto_nombre, contacto_celular) VALUES (?, '150122', 'Av. Destino Privada UP-110 123', NULL, 'Contacto Confidencial', '999888777')", pedido);
        mockMvc.perform(get("/api/distribuidor/pedidos").cookie(distribuidor)).andExpect(status().isOk());
        Long distribuidorId = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='distribuidor@ccpip.test'", Long.class);
        assertThat(jdbc.update("UPDATE entrega SET distribuidor_id=?, estado='ASIGNADA', asignada_en=now() WHERE pedido_id=?", distribuidorId, pedido)).isEqualTo(1);
    }

    private long nuevoPedido(String numero, String modalidad, String asignadoA) {
        long nuevo = pedidoConfirmado(numero, "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET modalidad=?, estado='LISTO_PARA_DESPACHO' WHERE id=?", modalidad, nuevo);
        if ("ENVIO".equals(modalidad)) {
            jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, 'LISTO_PARA_DESPACHO')", nuevo);
            jdbc.update("INSERT INTO entrega (pedido_id, ubigeo, direccion, referencia, contacto_nombre, contacto_celular, distribuidor_id, estado) VALUES (?, '150122', concat('Direccion privada ', ?), 'Referencia privada', 'No revelar', '988877766', (SELECT id FROM usuario WHERE correo=?), 'ASIGNADA')", nuevo, numero, asignadoA);
        }
        return nuevo;
    }

    @Test
    void anonimoEs401() throws Exception {
        mockMvc.perform(get(RUTA, NUMERO)).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesNoDistribuidoresSon403() throws Exception {
        for (String correo : List.of("consumidor@ccpip.test", "productor@ccpip.test", "admin_acopio@ccpip.test", "sub_admin@ccpip.test", "sudo@ccpip.test")) {
            mockMvc.perform(get(RUTA, NUMERO).cookie(loginAs(correo))).andExpect(status().isForbidden());
        }
    }

    @Test
    void ajenoSinAsignarFormatoInexistenteYRecojoDan404() throws Exception {
        nuevoPedido("UP-AJENO", "ENVIO", "distribuidor2@ccpip.test");
        nuevoPedido("UP-SIN-ASIGNAR", "ENVIO", null);
        nuevoPedido("UP-RECOJO", "RECOJO", null);
        for (String numero : List.of("UP-AJENO", "UP-SIN-ASIGNAR", "UP-RECOJO", "UP-NO-EXISTE")) {
            mockMvc.perform(get(RUTA, numero).cookie(distribuidor)).andExpect(status().isNotFound());
        }
        mockMvc.perform(get(RUTA, "UP_110").cookie(distribuidor)).andExpect(status().isNotFound());
        mockMvc.perform(get(RUTA, "UP/110").cookie(distribuidor)).andExpect(status().isNotFound());
    }

    @Test
    void lecturaValidaEsCaseInsensitiveYReferenciaNullExactaSinFiltrarContactosNiComprador() throws Exception {
        String respuesta = mockMvc.perform(get(RUTA, NUMERO.toLowerCase()).cookie(distribuidor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value(NUMERO))
                .andExpect(jsonPath("$.origen.id").isNumber())
                .andExpect(jsonPath("$.origen.nombre").value("Centro Nacional Lima"))
                .andExpect(jsonPath("$.origen.distrito").isNotEmpty())
                .andExpect(jsonPath("$.origen.direccion").isNotEmpty())
                .andExpect(jsonPath("$.destino.territorio.ubigeo").value("150122"))
                .andExpect(jsonPath("$.destino.territorio.departamento").value("Lima"))
                .andExpect(jsonPath("$.destino.territorio.provincia").value("Lima"))
                .andExpect(jsonPath("$.destino.territorio.distrito").value("Miraflores"))
                .andExpect(jsonPath("$.destino.direccion").value("Av. Destino Privada UP-110 123"))
                .andExpect(jsonPath("$.destino.referencia").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        assertThat(respuesta).doesNotContain("contactoNombre", "contactoCelular", "comprador", "Contacto Confidencial", "999888777");
    }

    @Test
    void listadoDisponiblesIncluyeDireccionDeOrigenPublicaPeroNoDestinoPrivado() throws Exception {
        long disponible = pedidoConfirmado("UP-DISP", "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET modalidad='ENVIO', estado='LISTO_PARA_DESPACHO' WHERE id=?", disponible);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, 'LISTO_PARA_DESPACHO')", disponible);
        jdbc.update("INSERT INTO entrega (pedido_id, ubigeo, direccion, referencia, contacto_nombre, contacto_celular, estado) VALUES (?, '150122', 'Destino que no debe salir', 'Privada', 'Contacto oculto', '977766655', 'DISPONIBLE')", disponible);
        String cuerpo = mockMvc.perform(get("/api/distribuidor/pedidos/disponibles").cookie(distribuidor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).contains("Centro Nacional Lima", "Sede central CCPIP").doesNotContain("Destino que no debe salir", "Privada", "Contacto oculto", "977766655", "Consumidor de Prueba");
    }

    private List<Map<String, Object>> snapshot(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    private Map<String, Object> estadoRelevante() {
        return Map.of(
                "pedido", snapshot("SELECT * FROM pedido WHERE id=?", pedido),
                "items", snapshot("SELECT * FROM item_pedido WHERE pedido_id=? ORDER BY id", pedido),
                "entrega", snapshot("SELECT * FROM entrega WHERE pedido_id=?", pedido),
                "historial", snapshot("SELECT * FROM pedido_estado_historial WHERE pedido_id=? ORDER BY id", pedido),
                "lotes", snapshot("SELECT DISTINCT l.* FROM lote l JOIN item_pedido i ON i.producto_id=l.producto_id WHERE i.pedido_id=? ORDER BY l.id", pedido),
                "movimientos", snapshot("SELECT * FROM movimiento_inventario WHERE pedido_id=? OR lote_id IN (SELECT l.id FROM lote l JOIN item_pedido i ON i.producto_id=l.producto_id WHERE i.pedido_id=?) ORDER BY id", pedido, pedido));
    }

    @Test
    void getValidoYRechazosNoMutanPedidoEntregaHistorialLotesNiMovimientos() throws Exception {
        Map<String, Object> antes = estadoRelevante();
        mockMvc.perform(get(RUTA, NUMERO).cookie(distribuidor)).andExpect(status().isOk());
        mockMvc.perform(get(RUTA, "UP/110").cookie(distribuidor)).andExpect(status().isNotFound());
        mockMvc.perform(get(RUTA, NUMERO).cookie(loginAs("productor@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get(RUTA, NUMERO)).andExpect(status().isUnauthorized());
        assertThat(estadoRelevante()).isEqualTo(antes);
    }
}
