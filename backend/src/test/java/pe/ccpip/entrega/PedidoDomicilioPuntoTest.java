package pe.ccpip.entrega;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-59: el pedido a domicilio sale del punto de acopio, que puede verlo y prepararlo, pero no
 * marcarlo listo para recojo ni entregarlo en el mostrador: su despacho llega con la distribucion. La
 * base ademas cuida la cobertura y la entrega.
 */
@Transactional
class PedidoDomicilioPuntoTest extends IntegrationTest {

    private static final String LIMA = "Centro Nacional Lima";

    private Cookie admin;
    private long pedido;

    @BeforeEach
    void pedidoADomicilioConfirmadoEnLima() throws Exception {
        admin = loginAs("admin_acopio@ccpip.test");
        long producto = productoConLote("Aguaje a domicilio", "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        pedido = pedidoConfirmado("DOM-0001", LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET modalidad = 'ENVIO' WHERE id = ?", pedido);
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular)
                VALUES (?, '150122', 'Av. Larco 100', 'María Quispe', '987654321')""", pedido);
    }

    private ResultActions accion(String que, Object cuerpo) throws Exception {
        var peticion = post("/api/acopio/pedidos/DOM-0001/" + que).cookie(admin);
        return mockMvc.perform(cuerpo == null ? peticion : peticion.contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    @Test
    void elPuntoPuedePrepararloPeroNoMarcarloListoParaRecojo() throws Exception {
        accion("preparacion", null).andExpect(status().isOk());
        accion("listo", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_A_DOMICILIO"));
    }

    @Test
    void elTableroDelPuntoDiceQueEsADomicilio() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/acopio/pedidos").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.numero == 'DOM-0001')].modalidad").value("ENVIO"));
    }

    @Test
    void noSeRegistraSuRecojoEnElMostrador() throws Exception {
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO' WHERE id = ?", pedido);
        accion("recojo", Map.of("persona", "Consumidor de Prueba", "celular", "987654321"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_A_DOMICILIO"));
    }

    @Test
    void laBaseCuidaLaEntrega() {
        assertRechazaLaBase(() -> jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular)
                VALUES (?, '150122', 'Otra', 'Otra', '987654321')""", pedido), "entrega_pedido_id_key");
        assertRechazaLaBase(() -> jdbc.update("UPDATE entrega SET contacto_celular = '12345' WHERE pedido_id = ?", pedido),
                "entrega_celular_valido");
        assertRechazaLaBase(() -> jdbc.update("UPDATE entrega SET estado = 'PERDIDA' WHERE pedido_id = ?", pedido),
                "entrega_estado_valido");
        assertRechazaLaBase(() -> jdbc.update("UPDATE entrega SET ubigeo = '000000' WHERE pedido_id = ?", pedido),
                "entrega_ubigeo_fkey");
    }

    @Test
    void laBaseCuidaLaCobertura() {
        assertRechazaLaBase(() -> jdbc.update("""
                INSERT INTO cobertura_entrega (ubicacion_id, ubigeo)
                SELECT id, '150122' FROM ubicacion WHERE nombre = ?""", LIMA), "cobertura_entrega_unica");
        assertRechazaLaBase(() -> jdbc.update("""
                INSERT INTO cobertura_entrega (ubicacion_id, ubigeo)
                SELECT id, '000000' FROM ubicacion WHERE nombre = ?""", LIMA), "cobertura_entrega_ubigeo_fkey");
        assertRechazaLaBase(() -> jdbc.update("INSERT INTO ubigeo VALUES ('12345', 'X', 'X', 'X')"), "ubigeo_codigo_valido");
    }
}
