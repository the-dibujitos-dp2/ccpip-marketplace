package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/** Caracteriza persistencia, validación e invariantes del PUT de direcciones propias. */
@Transactional
class ModificarDireccionPersistenciaTest extends IntegrationTest {
    private static final String COMPRADOR = "consumidor@ccpip.test";
    private Map<String, Object> payload() {
        Map<String, Object> d = new HashMap<>();
        d.put("alias", "Casa"); d.put("ubigeo", "150122");
        d.put("direccion", "Av. Larco 123, dpto. 4"); d.put("referencia", "Frente al parque Kennedy");
        return d;
    }
    private long guardar(String correo, String alias, String ubigeo, String direccion) {
        return jdbc.queryForObject("""
                INSERT INTO direccion_guardada (usuario_id, alias, ubigeo, direccion, referencia, activa)
                SELECT id, ?, ?, ?, 'Frente al parque Kennedy', true FROM usuario WHERE correo = ? RETURNING id""",
                Long.class, alias, ubigeo, direccion, correo);
    }
    private void put(Cookie cookie, long id, Map<String, Object> cuerpo) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cuenta/direcciones/{id}", id).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }
    private String fila(long id) {
        return jdbc.queryForObject("SELECT coalesce(alias,'<null>') || '|' || ubigeo || '|' || direccion || '|' || coalesce(referencia,'<null>') || '|' || usuario_id || '|' || activa FROM direccion_guardada WHERE id = ?", String.class, id);
    }

    @Test
    void aliasSoloYNoopPersistenSinAutoduplicadoYReferenciaVaciaSeNormaliza() throws Exception {
        long id = guardar(COMPRADOR, "Casa", "150122", "Av. Larco 123, dpto. 4");
        Cookie sesion = loginAs(COMPRADOR);
        Map<String, Object> soloAlias = payload(); soloAlias.put("alias", "Hogar");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cuenta/direcciones/{id}", id).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(soloAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.alias").value("Hogar"));
        soloAlias.put("referencia", "   ");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cuenta/direcciones/{id}", id).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(soloAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.referencia").doesNotExist());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cuenta/direcciones/{id}", id).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(soloAlias)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(fila(id)).isEqualTo("Hogar|150122|Av. Larco 123, dpto. 4|<null>|" + jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, COMPRADOR) + "|true");
    }

    @Test
    void payloadsInvalidosYDuplicadoNoAlteranLaFilaOriginalNiLaPropiedad() throws Exception {
        long id = guardar(COMPRADOR, "Casa", "150122", "Av. Larco 123, dpto. 4");
        long duplicado = guardar(COMPRADOR, "Trabajo", "150131", "Av. Javier Prado 500");
        long ajena = guardar("productor@ccpip.test", "Ajena", "150122", "Otra calle 999");
        Cookie sesion = loginAs(COMPRADOR);
        String original = fila(id);
        for (Map.Entry<String, Object> caso : Map.<String, Object>of(
                "alias", "x".repeat(41), "ubigeo", "999999", "direccion", "12345", "referencia", "r".repeat(256)).entrySet()) {
            Map<String, Object> invalido = payload(); invalido.put(caso.getKey(), caso.getValue());
            var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cuenta/direcciones/{id}", id).cookie(sesion)
                    .contentType(MediaType.APPLICATION_JSON).content(json(invalido))).andReturn().getResponse();
            assertThat(response.getStatus()).as("campo %s", caso.getKey()).isBetween(400, 499);
            assertThat(response.getContentAsString()).doesNotContain("ServletException");
            assertThat(fila(id)).isEqualTo(original);
        }
        Map<String, Object> colision = payload(); colision.put("ubigeo", "150131"); colision.put("direccion", "Av. Javier Prado 500");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/cuenta/direcciones/{id}", id).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(colision)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DIRECCION_YA_GUARDADA"));
        assertThat(fila(id)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT activa FROM direccion_guardada WHERE id = ?", Boolean.class, duplicado)).isTrue();
        assertThat(fila(ajena)).contains("|true");
    }
}
