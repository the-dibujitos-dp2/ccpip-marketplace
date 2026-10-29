package pe.ccpip.territorio;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-61 (RF-42): el comprador elige departamento, provincia y distrito del catalogo territorial para
 * indicar el destino de su pedido. Cada nivel pide la eleccion del anterior; es solo para el comprador.
 */
class TerritorioTest extends IntegrationTest {

    private static final String BASE = "/api/territorio";

    @Test
    void listaLosDepartamentosSinRepetirYOrdenados() throws Exception {
        mockMvc.perform(get(BASE + "/departamentos").cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItems("Cusco", "Junín", "Lima", "Loreto", "San Martín", "Ucayali")))
                .andExpect(jsonPath("$[0]").value("Cusco"));
    }

    @Test
    void listaLasProvinciasDelDepartamento() throws Exception {
        mockMvc.perform(get(BASE + "/provincias").param("departamento", "Ucayali").cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", contains("Coronel Portillo")));
        // Un departamento que no existe no tiene provincias
        mockMvc.perform(get(BASE + "/provincias").param("departamento", "Atlantida").cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listaLosDistritosDeLaProvinciaConSuUbigeo() throws Exception {
        mockMvc.perform(get(BASE + "/distritos").param("departamento", "Lima").param("provincia", "Lima")
                        .cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ubigeo == '150122')].distrito").value("Miraflores"))
                .andExpect(jsonPath("$[?(@.ubigeo == '150122')].departamento").value("Lima"))
                .andExpect(jsonPath("$[?(@.ubigeo == '150122')].provincia").value("Lima"));
        // Una provincia de otro departamento no mezcla distritos
        mockMvc.perform(get(BASE + "/distritos").param("departamento", "Lima").param("provincia", "Maynas")
                        .cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void cadaNivelPideLaEleccionDelAnterior() throws Exception {
        var comprador = loginAs("consumidor@ccpip.test");
        mockMvc.perform(get(BASE + "/provincias").cookie(comprador))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TERRITORIO_INCOMPLETO"));
        mockMvc.perform(get(BASE + "/distritos").param("departamento", "Lima").cookie(comprador))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TERRITORIO_INCOMPLETO"));
        mockMvc.perform(get(BASE + "/distritos").param("provincia", "Lima").param("departamento", " ").cookie(comprador))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void esDelCompradorYDelPuntoDeAcopio() throws Exception {
        mockMvc.perform(get(BASE + "/departamentos")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/departamentos").cookie(loginAs("productor@ccpip.test"))).andExpect(status().isForbidden());
        // CCPIP-149: el punto de acopio elige del catalogo los distritos que declara como cobertura
        mockMvc.perform(get(BASE + "/departamentos").cookie(loginAs("admin_acopio@ccpip.test"))).andExpect(status().isOk());
    }
}
