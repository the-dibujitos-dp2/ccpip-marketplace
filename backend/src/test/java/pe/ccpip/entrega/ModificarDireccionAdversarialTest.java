package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/** Matriz adversarial CCPIP-394: solo comprueba el contrato del PUT; no modifica produccion. */
@Transactional
class ModificarDireccionAdversarialTest extends IntegrationTest {
    private static final String RUTA = "/api/cuenta/direcciones";
    private static final String COMPRADOR = "consumidor@ccpip.test";

    private Map<String, Object> direccion() {
        Map<String, Object> d = new HashMap<>();
        d.put("alias", "Casa");
        d.put("ubigeo", "150122");
        d.put("direccion", "Av. Larco 123, dpto. 4");
        d.put("referencia", "Frente al parque Kennedy");
        return d;
    }

    private long guardar(String correo, String alias, String ubigeo, String texto, boolean activa) {
        return jdbc.queryForObject("""
                INSERT INTO direccion_guardada (usuario_id, alias, ubigeo, direccion, referencia, activa)
                SELECT id, ?, ?, ?, 'Frente al parque Kennedy', ? FROM usuario WHERE correo = ? RETURNING id""",
                Long.class, alias, ubigeo, texto, activa, correo);
    }

    private ResultActions modificar(Cookie sesion, long id, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(put(RUTA + "/{id}", id).cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                .content(json(cuerpo)));
    }

    private int activa(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM direccion_guardada WHERE id = ? AND activa", Integer.class, id);
    }

    @Test
    void prioridadPropiedadSobreDuplicadoParaIdAjenoRetiradoEInexistente() throws Exception {
        String otra = "otra.compradora@ccpip.test";
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Otra Compradora', 1, true FROM usuario WHERE correo = ?
                ON CONFLICT (correo) DO NOTHING""", otra, COMPRADOR);
        long duplicadaPropia = guardar(COMPRADOR, "Casa", "150122", "Av. Larco 123, dpto. 4", true);
        long ajena = guardar(otra, "Ajena", "150131", "Otra dirección 456", true);
        long retirada = guardar(COMPRADOR, "Retirada", "150131", "Otra dirección 456", false);
        long inexistente = 999999999L;
        Map<String, Object> payload = direccion(); // Válido y duplicado de otra dirección activa propia.
        Cookie comprador = loginAs(COMPRADOR);

        var ajenaResponse = modificar(comprador, ajena, payload).andReturn().getResponse();
        var retiradaResponse = modificar(comprador, retirada, payload).andReturn().getResponse();
        var inexistenteResponse = modificar(comprador, inexistente, payload).andReturn().getResponse();
        assertThat(java.util.List.of(ajenaResponse.getStatus(), retiradaResponse.getStatus(), inexistenteResponse.getStatus()))
                .as("status HTTP en orden ajena/retirada/inexistente; cada respuesta debe ocultar el recurso")
                .containsExactly(404, 404, 404);
        assertThat(java.util.List.of(ajenaResponse.getContentAsString(), retiradaResponse.getContentAsString(),
                inexistenteResponse.getContentAsString()))
                .allSatisfy(body -> assertThat(body).contains("DIRECCION_NO_ENCONTRADA"));
        assertThat(activa(duplicadaPropia)).isEqualTo(1);
        assertThat(activa(ajena)).isEqualTo(1);
        assertThat(activa(retirada)).isZero();
    }

    @Test
    void rolesAnonimoYPropiedadPropia() throws Exception {
        long id = guardar(COMPRADOR, "Casa", "150122", "Av. Larco 123, dpto. 4", true);
        Map<String, Object> payload = direccion();
        mockMvc.perform(put(RUTA + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isUnauthorized());
        modificar(loginAs("productor@ccpip.test"), id, payload).andExpect(status().isForbidden());
        modificar(loginAs("admin_acopio@ccpip.test"), id, payload).andExpect(status().isForbidden());
        modificar(loginAs(COMPRADOR), id, payload).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }
}
