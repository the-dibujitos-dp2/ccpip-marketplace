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
class AuditoriaCapturaTest extends IntegrationTest {
    @Test
    void cuentaBancariaConservaEventoSinDatosBancariosYNoDuplicaNoop() throws Exception {
        var productor = loginAs("productor_nuevo@ccpip.test");
        var datos = Map.of("titular", "Comunidad de prueba", "documento", "20609876543", "banco", "Banco de la Nación",
                "tipoCuenta", "AHORROS", "numeroCuenta", "04-110-123456", "cci", "01811000004110123456", "moneda", "PEN");
        mockMvc.perform(post("/api/productor/cuenta-bancaria").cookie(productor)
                .contentType(MediaType.APPLICATION_JSON).content(json(datos))).andExpect(status().isCreated());
        long uid = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='productor_nuevo@ccpip.test'", Long.class);
        var evento = jdbc.queryForMap("SELECT * FROM auditoria_evento WHERE accion='CUENTA_BANCARIA_CREADA' AND usuario_id=?", uid);
        assertThat(evento.get("rol")).isEqualTo("PRODUCTOR");
        assertThat(evento.get("detalle").toString()).doesNotContain("123456", "20609876543", "018110", "Comunidad de prueba");
        mockMvc.perform(put("/api/productor/cuenta-bancaria").cookie(productor)
                .contentType(MediaType.APPLICATION_JSON).content(json(datos))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE entidad='CUENTA_BANCARIA' AND usuario_id=?", Long.class, uid)).isEqualTo(1);
        mockMvc.perform(post("/api/productor/cuenta-bancaria/desactivar").cookie(productor)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", "ROBO", "confirmo", true))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE accion='CUENTA_BANCARIA_DESACTIVADA' AND usuario_id=?", Long.class, uid)).isEqualTo(1);
    }

    @Test
    void perfilCapturaSoloCamposAlteradosYSnapshotHistorico() throws Exception {
        var consumidor = loginAs("consumidor@ccpip.test");
        mockMvc.perform(put("/api/cuenta/datos").cookie(consumidor).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nombres", "Juan", "apellidos", "Prueba", "celular", "987111222"))))
                .andExpect(status().isOk());
        long uid = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='consumidor@ccpip.test'", Long.class);
        var evento = jdbc.queryForMap("SELECT * FROM auditoria_evento WHERE accion='PERFIL_ACTUALIZADO' AND usuario_id=?", uid);
        assertThat(evento.get("detalle").toString()).doesNotContain("Juan", "987111222", "Prueba");
        assertThat(evento.get("rol")).isEqualTo("CONSUMIDOR");
        jdbc.update("UPDATE usuario SET nombre='Nombre posterior' WHERE id=?", uid);
        assertThat(jdbc.queryForObject("SELECT usuario_nombre FROM auditoria_evento WHERE id=?", String.class, evento.get("id")))
                .isNotEqualTo("Nombre posterior");
    }
}
