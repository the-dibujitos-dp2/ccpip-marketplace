package pe.ccpip.catalogo;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-51 y CCPIP-175 con el asistente simulado (sin IA): la busqueda es la del catalogo, los
 * productos son reales y visibles, y sin coincidencias se reconoce que no hay informacion.
 */
@Transactional
class AsistenteCatalogoControllerTest extends IntegrationTest {

    private org.springframework.test.web.servlet.ResultActions preguntar(String pregunta, String idioma) throws Exception {
        var pedido = post("/api/asistente/catalogo").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("pregunta", pregunta)));
        if (idioma != null) {
            pedido.header(HttpHeaders.ACCEPT_LANGUAGE, idioma);
        }
        return mockMvc.perform(pedido);
    }

    @Test
    void respondeSinSesionConProductosRealesDelCatalogo() throws Exception {
        preguntar("Busco café para regalar", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.texto").isNotEmpty())
                .andExpect(jsonPath("$.productos.length()").value(2))
                .andExpect(jsonPath("$.productos[*].nombre", everyItem(startsWith("Cafe"))))
                .andExpect(jsonPath("$.productos[0].id").isNumber())
                .andExpect(jsonPath("$.productos[0].tienda.nombre").exists())
                .andExpect(jsonPath("$.busqueda.q").value("café"))
                .andExpect(jsonPath("$.sinResultados").value(false))
                .andExpect(jsonPath("$.proveedor").value("simulado"));
    }

    @Test
    void sinCoincidenciasReconoceQueNoHayInformacion() throws Exception {
        preguntar("Quiero un televisor", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos.length()").value(0))
                .andExpect(jsonPath("$.sinResultados").value(true))
                .andExpect(jsonPath("$.texto", Matchers.containsString("No encontré productos")));
    }

    @Test
    void unaPreguntaAjenaAlCatalogoNoSeResponde() throws Exception {
        preguntar("hola, ¿qué tal?", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fueraDeAlcance").value(true))
                .andExpect(jsonPath("$.productos.length()").value(0));
    }

    @Test
    void respondeEnElIdiomaPedido() throws Exception {
        preguntar("café", "en-US,en;q=0.9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.texto", Matchers.startsWith("Test answer")));
    }

    @Test
    void rechazaUnaPreguntaVaciaOLarga() throws Exception {
        preguntar("  ", null).andExpect(status().isBadRequest());
        preguntar("a".repeat(301), null).andExpect(status().isBadRequest());
    }
}
