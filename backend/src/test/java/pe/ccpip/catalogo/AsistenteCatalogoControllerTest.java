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
 * productos son reales y visibles, y sin coincidencias se reconoce que no hay informacion. CCPIP-347: tambien
 * muestra tiendas reales y publicadas.
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
    void conLosProductosMuestraLasTiendasRealesQueLosVenden() throws Exception {
        String r = preguntar("Busco café para regalar", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var json = objectMapper.readTree(r);
        var deProductos = new java.util.TreeSet<Long>();
        json.get("productos").forEach(p -> deProductos.add(p.get("tienda").get("id").asLong()));
        var tiendas = new java.util.TreeSet<Long>();
        json.get("tiendas").forEach(t -> tiendas.add(t.get("id").asLong()));
        org.assertj.core.api.Assertions.assertThat(tiendas).containsAll(deProductos);
    }

    @Test
    void sinProductosMuestraLasTiendasQueCoincidenConTextoFijo() throws Exception {
        preguntar("¿Quiénes son las tejedoras de Chinchero?", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos.length()").value(0))
                .andExpect(jsonPath("$.tiendas.length()").value(1))
                .andExpect(jsonPath("$.tiendas[0].comunidad").value("Comunidad de Tejedoras de Chinchero"))
                .andExpect(jsonPath("$.tiendas[0].region").value("Cusco"))
                .andExpect(jsonPath("$.sinResultados").value(false))
                .andExpect(jsonPath("$.texto", Matchers.startsWith("No encontré productos que coincidan, pero estas tiendas")));
    }

    @Test
    void soloTiendasEnIngles() throws Exception {
        preguntar("Chinchero weavers", "en")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiendas.length()").value(1))
                .andExpect(jsonPath("$.texto", Matchers.startsWith("I didn't find matching products, but these catalog stores")));
        preguntar("television", "en")
                .andExpect(jsonPath("$.texto", Matchers.startsWith("I couldn't find catalog products or stores")));
    }

    @Test
    void noMuestraTiendasNoPublicadas() throws Exception {
        jdbc.update("UPDATE productor SET tienda_publicada = false WHERE comunidad = 'Comunidad de Tejedoras de Chinchero'");
        preguntar("¿Quiénes son las tejedoras de Chinchero?", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiendas.length()").value(0))
                .andExpect(jsonPath("$.sinResultados").value(true));
    }

    @Test
    void sinCoincidenciasReconoceQueNoHayInformacion() throws Exception {
        preguntar("Quiero un televisor", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos.length()").value(0))
                .andExpect(jsonPath("$.tiendas.length()").value(0))
                .andExpect(jsonPath("$.sinResultados").value(true))
                .andExpect(jsonPath("$.texto", Matchers.containsString("No encontré productos ni tiendas")));
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
