package pe.ccpip.auth.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/** CCPIP-151: el rol se valida en el servidor en cada peticion. */
@ExtendWith(OutputCaptureExtension.class)
class AccessControlTest extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void cadaRolEntraASuPanel() throws Exception {
        assertPanel("consumidor@ccpip.test", "/api/panel/consumidor", 200);
        assertPanel("productor@ccpip.test", "/api/panel/productor", 200);
        assertPanel("admin_acopio@ccpip.test", "/api/panel/acopio", 200);
        assertPanel("sub_admin@ccpip.test", "/api/panel/administracion", 200);
        assertPanel("sudo@ccpip.test", "/api/panel/administracion", 200);
        assertPanel("distribuidor@ccpip.test", "/api/panel/distribuidor", 200);
    }

    @Test
    void elConsumidorNoEntraAFuncionesDeProductorNiDeAcopio() throws Exception {
        Cookie consumidor = loginAs("consumidor@ccpip.test");
        for (String ruta : List.of("/api/panel/productor", "/api/panel/acopio", "/api/panel/administracion")) {
            mockMvc.perform(get(ruta).cookie(consumidor))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        }
    }

    @Test
    void niElProductorNiElAcopioEntranAFuncionesDelConsumidor() throws Exception {
        assertPanel("productor@ccpip.test", "/api/panel/consumidor", 403);
        assertPanel("productor@ccpip.test", "/api/panel/acopio", 403);
        assertPanel("admin_acopio@ccpip.test", "/api/panel/consumidor", 403);
        assertPanel("admin_acopio@ccpip.test", "/api/panel/productor", 403);
    }

    @Test
    void elAccesoDenegadoQuedaRegistrado(CapturedOutput output) throws Exception {
        assertPanel("consumidor@ccpip.test", "/api/panel/productor", 403);
        assertThat(output.getOut()).contains("Acceso denegado")
                .contains("rol=CONSUMIDOR")
                .contains("/api/panel/productor");
    }

    @Test
    void sinSesionNoSeLlegaAlControlDeRoles() throws Exception {
        mockMvc.perform(get("/api/panel/consumidor")).andExpect(status().isUnauthorized());
    }

    @Test
    void cadaEndpointDeLaApiDeclaraQuienPuedeLlamarlo() {
        List<String> sinDeclarar = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/")))
                .filter(e -> !declaraAcceso(e.getValue()))
                .map(e -> e.getKey().toString())
                .toList();
        assertThat(sinDeclarar).as("Endpoints sin @Publico, @Autenticado ni @RolesPermitidos").isEmpty();
    }

    private void assertPanel(String email, String ruta, int esperado) throws Exception {
        mockMvc.perform(get(ruta).cookie(loginAs(email))).andExpect(status().is(esperado));
    }

    private static boolean declaraAcceso(HandlerMethod method) {
        return List.of(Publico.class, Autenticado.class, RolesPermitidos.class).stream().anyMatch(type ->
                AnnotatedElementUtils.hasAnnotation(method.getMethod(), type)
                        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), type));
    }
}
