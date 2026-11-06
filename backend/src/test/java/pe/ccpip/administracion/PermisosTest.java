package pe.ccpip.administracion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-28: el superadministrador consulta los permisos de cada rol en los permisos configurables
 * (CCPIP-296). El servidor no publica el listado de sus rutas y metodos: seria el mapa completo del API.
 */
class PermisosTest extends IntegrationTest {

    private static final String SUPER = "sudo@ccpip.test";

    @ParameterizedTest
    @ValueSource(strings = {"sudo@ccpip.test", "sub_admin@ccpip.test", "consumidor@ccpip.test"})
    void elServidorNoPublicaElListadoDeSusRutas(String correo) throws Exception {
        mockMvc.perform(get("/api/admin/permisos").cookie(loginAs(correo))).andExpect(status().isNotFound());
    }

    @Test
    void losPermisosDeCadaRolSeConsultanEnLosConfigurables() throws Exception {
        mockMvc.perform(get("/api/admin/permisos/configurables").cookie(loginAs(SUPER))).andExpect(status().isOk());
    }

    @Test
    void elSuperadministradorNoEditaDatosOperativos() throws Exception {
        var sesion = loginAs(SUPER);
        mockMvc.perform(put("/api/productor/tienda").cookie(sesion)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/pedidos").cookie(sesion)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/productor/almacen/movimientos").cookie(sesion)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/productor/productos/1").cookie(sesion)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/cuenta/datos").cookie(sesion)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
}
