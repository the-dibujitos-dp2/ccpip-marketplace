package pe.ccpip.gobernanza;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

/** Contrato integrado de la proyección de auditoría CCPIP-509. */
@Transactional
class AuditoriaAmpliadaTest extends IntegrationTest {
    @Autowired AuditoriaEventos eventos;

    @Test
    void exponeSnapshotYPermiteFiltrarPorActorUbicacionYReferencia() throws Exception {
        long actor = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='sudo@ccpip.test'", Long.class);
        eventos.registrar(actor, "BANCO_CAMBIADO", "CUENTA_BANCARIA", "banco/7", "EXITOSA",
                91L, "corr-7", null, Map.of("campo", "BANCO", "estadoNuevo", "ACTIVO"));
        var sudo = loginAs("sudo@ccpip.test");
        mockMvc.perform(get("/api/admin/auditoria").param("actor", "USUARIO").param("ubicacion", "91")
                        .param("referencia", "banco/7").cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[0].accion").value("BANCO_CAMBIADO"))
                .andExpect(jsonPath("$.registros[0].rolHistorico").value(true))
                .andExpect(jsonPath("$.registros[0].ubicacionId").value(91))
                .andExpect(jsonPath("$.registros[0].referencia").value("banco/7"));
    }
}
