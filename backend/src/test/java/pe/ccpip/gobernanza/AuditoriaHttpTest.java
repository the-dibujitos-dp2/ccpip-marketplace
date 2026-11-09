package pe.ccpip.gobernanza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

@Transactional
class AuditoriaHttpTest extends IntegrationTest {
    @Test
    void denegadoConservaActorSinRegistrarNavegacionOrdinaria() throws Exception {
        var comprador=loginAs("consumidor@ccpip.test");
        long uid=jdbc.queryForObject("SELECT id FROM usuario WHERE correo='consumidor@ccpip.test'",Long.class);
        long anteriores=jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE accion='ACCESO_DENEGADO' AND usuario_id=?",Long.class,uid);
        mockMvc.perform(get("/api/admin/auditoria").cookie(comprador)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE accion='ACCESO_DENEGADO' AND usuario_id=?",Long.class,uid)).isEqualTo(anteriores+1);
        mockMvc.perform(get("/api/auth/me").cookie(comprador)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE accion='ACCESO_DENEGADO' AND usuario_id=?",Long.class,uid)).isEqualTo(anteriores+1);
    }
    @Test
    void recuperacionInexistenteNoEnumeraCuentaNiGuardaCorreo() throws Exception {
        mockMvc.perform(post("/api/auth/recuperar-contrasena").contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("email","no-existe-auditoria@ccpip.test")))).andExpect(status().isAccepted());
        var fila=jdbc.queryForMap("SELECT * FROM auditoria_evento WHERE accion='RECUPERACION_SOLICITADA' ORDER BY id DESC LIMIT 1");
        assertThat(fila.get("actor_tipo")).isEqualTo("ANONIMO");
        assertThat(fila.toString()).doesNotContain("no-existe-auditoria", "password", "token");
    }
    @Test
    void descargaPdfRegistraRutaDeReporteNoContenido() throws Exception {
        var productor=loginAs("productor@ccpip.test");
        mockMvc.perform(get("/api/productor/liquidaciones/pdf").cookie(productor)).andExpect(status().isOk());
        var fila=jdbc.queryForMap("SELECT * FROM auditoria_evento WHERE accion='REPORTE_DESCARGADO' ORDER BY id DESC LIMIT 1");
        assertThat(fila.get("rol")).isEqualTo("PRODUCTOR");
        assertThat(fila.get("referencia")).isEqualTo("/api/productor/liquidaciones/pdf");
        assertThat(fila.get("detalle").toString()).doesNotContain("%PDF", "correo", "cuenta");
    }
}
