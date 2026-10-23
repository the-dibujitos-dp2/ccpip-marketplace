package pe.ccpip.administracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;

import pe.ccpip.support.IntegrationTest;

class PermisosTest extends IntegrationTest {

    private static final String BASE = "/api/admin/permisos";
    private static final String SUPER = "sudo@ccpip.test";

    @Test
    void elSuperadministradorConsultaPermisosRealesDeCadaRolEnSoloLectura() throws Exception {
        String respuesta = mockMvc.perform(get(BASE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode roles = objectMapper.readTree(respuesta);

        assertThat(roles.size()).isEqualTo(6);
        assertThat(StreamSupport.stream(roles.spliterator(), false)
                .map(r -> r.path("rol").asText()).toList())
                .containsExactly("CONSUMIDOR", "PRODUCTOR", "ADMIN_PUNTO", "SUB_ADMIN", "SUPER_ADMIN", "DISTRIBUIDOR");
        assertThat(permisos(roles, "PRODUCTOR")).anySatisfy(p -> {
            assertThat(p.path("metodo").asText()).isEqualTo("POST");
            assertThat(p.path("ruta").asText()).isEqualTo("/api/productor/productos");
        });
        assertThat(permisos(roles, "SUPER_ADMIN")).anySatisfy(p -> {
            assertThat(p.path("metodo").asText()).isEqualTo("GET");
            assertThat(p.path("ruta").asText()).isEqualTo("/api/admin/tablero");
        });
        assertThat(permisos(roles, "CONSUMIDOR")).anySatisfy(p -> {
            assertThat(p.path("metodo").asText()).isEqualTo("GET");
            assertThat(p.path("ruta").asText()).isEqualTo("/api/auth/me");
        });
        assertThat(permisos(roles, "SUPER_ADMIN")).noneSatisfy(p -> {
            assertThat(p.path("ruta").asText()).contains("/api/productor/productos");
            assertThat(p.path("metodo").asText()).isIn("POST", "PUT", "PATCH", "DELETE");
        });
        assertThat(permisos(roles, "SUPER_ADMIN")).noneMatch(p -> {
            String ruta = p.path("ruta").asText();
            String metodo = p.path("metodo").asText();
            boolean recursoProtegido = ruta.startsWith("/api/productor/tienda")
                    || ruta.startsWith("/api/productor/productos")
                    || ruta.startsWith("/api/acopio/") || ruta.startsWith("/api/pedidos")
                    || ruta.startsWith("/api/carrito") || ruta.startsWith("/api/cuenta/datos");
            return recursoProtegido && !metodo.equals("GET");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub_admin@ccpip.test", "consumidor@ccpip.test", "productor@ccpip.test",
            "admin_acopio@ccpip.test", "distribuidor@ccpip.test"})
    void soloElSuperadministradorPuedeConsultarLaMatriz(String correo) throws Exception {
        mockMvc.perform(get(BASE).cookie(loginAs(correo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void sinSesionNoPuedeConsultarLaMatriz() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void laMatrizNoPermiteCambiosYElSuperadministradorNoEditaDatosOperativos() throws Exception {
        var sesion = loginAs(SUPER);
        mockMvc.perform(post(BASE).cookie(sesion)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(BASE).cookie(sesion)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(BASE).cookie(sesion)).andExpect(status().isMethodNotAllowed());
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

    @Test
    void consultarLaMatrizNoGeneraRegistrosDeAuditoria() throws Exception {
        var sesion = loginAs(SUPER);
        Integer productoAntes = jdbc.queryForObject("SELECT count(*) FROM producto_auditoria", Integer.class);
        Integer catalogoAntes = jdbc.queryForObject("SELECT count(*) FROM catalogo_maestro_auditoria", Integer.class);

        mockMvc.perform(get(BASE).cookie(sesion)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM producto_auditoria", Integer.class)).isEqualTo(productoAntes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalogo_maestro_auditoria", Integer.class)).isEqualTo(catalogoAntes);
    }

    private static java.util.List<JsonNode> permisos(JsonNode roles, String rol) {
        return StreamSupport.stream(roles.spliterator(), false)
                .filter(r -> rol.equals(r.path("rol").asText()))
                .flatMap(r -> StreamSupport.stream(r.path("permisos").spliterator(), false))
                .toList();
    }
}
