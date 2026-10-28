package pe.ccpip.cobertura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

@Transactional
class CoberturasDeclaradasTest extends IntegrationTest {
    @Test
    void consultaTerritorioRegistradoDePuntoPropioSinMutarCobertura() throws Exception {
        var sesion = loginAs("admin_acopio@ccpip.test");
        Long punto = jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN usuario_ubicacion uu ON uu.ubicacion_id=u.id
                JOIN usuario a ON a.id=uu.usuario_id
                WHERE a.correo='admin_acopio@ccpip.test' AND u.tipo='PUNTO_ACOPIO'
                ORDER BY u.id LIMIT 1""", Long.class);
        String departamento = jdbc.queryForObject("SELECT departamento FROM ubigeo WHERE codigo='150122'", String.class);
        jdbc.update("""
                INSERT INTO cobertura_entrega(ubicacion_id,ubigeo,activa) VALUES (?, '150122', true)
                ON CONFLICT(ubicacion_id,ubigeo) DO UPDATE SET activa=true""", punto);
        var antes = jdbc.queryForList("SELECT * FROM cobertura_entrega ORDER BY id");
        mockMvc.perform(get("/api/acopio/coberturas").param("punto", punto.toString()).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].puntoId").value(punto.intValue()))
                .andExpect(jsonPath("$[0].zonas[?(@.ubigeo == '150122')].departamento").value(hasItem(departamento)));
        assertThat(jdbc.queryForList("SELECT * FROM cobertura_entrega ORDER BY id")).isEqualTo(antes);
    }
}
