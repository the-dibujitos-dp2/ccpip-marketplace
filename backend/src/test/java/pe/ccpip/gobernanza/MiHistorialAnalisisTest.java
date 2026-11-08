package pe.ccpip.gobernanza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

@Transactional
class MiHistorialAnalisisTest extends IntegrationTest {
    @Test
    void analizarGeneraSolicitudYResultadoEnElHistorialPropioSinAutorizar() throws Exception {
        long producto = productoConLote("Análisis historial propio", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(3));
        long autorizacion = jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id=?", Long.class, producto);
        jdbc.update("UPDATE autorizacion_producto SET estado='SOLICITADA', solicitada_en=now() WHERE id=?", autorizacion);
        var sub = loginAs("sub_admin@ccpip.test");
        var respuesta = mockMvc.perform(post("/api/admin/autorizaciones/"+autorizacion+"/analisis").cookie(sub)).andReturn().getResponse();
        assertThat(respuesta.getStatus()).isIn(200,503);
        mockMvc.perform(get("/api/admin/mi-historial?tipo=AUTORIZACIONES").cookie(sub))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.acciones[*].accion",hasItem("ANALISIS_SOLICITADO")))
            .andExpect(jsonPath("$.acciones[*].accion",hasItem("ANALISIS_RESULTADO")))
            .andExpect(jsonPath("$.acciones[?(@.accion == 'ANALISIS_RESULTADO')].objetoId",hasItem((int)autorizacion)));
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id=?",String.class,autorizacion)).isEqualTo("SOLICITADA");
        jdbc.update("INSERT INTO usuario(correo,contrasena_hash,nombre,rol_id,correo_confirmado) SELECT 'sub_hist_509@ccpip.test',contrasena_hash,'Otro sub',rol_id,true FROM usuario WHERE correo='sub_admin@ccpip.test'");
        mockMvc.perform(get("/api/admin/mi-historial?tipo=AUTORIZACIONES").cookie(loginAs("sub_hist_509@ccpip.test")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.acciones",hasSize(0)));
    }
}
