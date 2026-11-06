package pe.ccpip.productor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-492: en Mis productos se ve si la Camara rechazo la autorizacion de un producto y por que, sin entrar
 * a sus certificados. Va antes que las demas alertas, porque es lo que impide venderlo.
 */
@Transactional
class RechazoEnListaTest extends IntegrationTest {

    private static final String MOTIVO = "Falta el certificado orgánico vigente";

    private Cookie productor;
    private long producto;
    private String estado;

    @BeforeEach
    void preparar() throws Exception {
        productor = loginAs("productor@ccpip.test");
        producto = jdbc.queryForObject("""
                SELECT p.id FROM producto p JOIN productor pr ON pr.id = p.productor_id JOIN usuario u ON u.id = pr.usuario_id
                JOIN autorizacion_producto a ON a.producto_id = p.id
                WHERE u.correo = 'productor@ccpip.test' ORDER BY p.id LIMIT 1""", Long.class);
        estado = jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, producto);
    }

    private String fila(String campo) {
        return "$.items[?(@.id == %d)].%s".formatted(producto, campo);
    }

    @Test
    void laAutorizacionRechazadaSeVeEnLaListaConSuMotivo() throws Exception {
        jdbc.update("UPDATE autorizacion_producto SET estado = 'RECHAZADA', motivo = ? WHERE producto_id = ?", MOTIVO, producto);

        mockMvc.perform(get("/api/productor/productos").param("estado", estado).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath(fila("alerta")).value("AUTORIZACION_RECHAZADA"))
                .andExpect(jsonPath(fila("motivo")).value(MOTIVO));
    }

    @Test
    void sinRechazoNoHayMotivo() throws Exception {
        mockMvc.perform(get("/api/productor/productos").param("estado", estado).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath(fila("alerta")).value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("AUTORIZACION_RECHAZADA"))))
                .andExpect(jsonPath(fila("motivo")).value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }
}
