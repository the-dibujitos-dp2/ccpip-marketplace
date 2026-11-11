package pe.ccpip.catalogo;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.ia.AsistenteCatalogo;
import pe.ccpip.ia.AsistenteCatalogo.Candidato;
import pe.ccpip.ia.AsistenteCatalogo.Interpretacion;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-510: el asistente sugiere los productos que coinciden por nombre aunque la IA suponga otra linea.
 * "aguaje" como superfruto no debe terminar en el camu camu cuando el catalogo tiene aceite de aguaje.
 */
@Transactional
class AsistenteNombreMandaTest extends IntegrationTest {

    @MockitoBean
    AsistenteCatalogo asistente;

    @BeforeEach
    void ia() {
        when(asistente.proveedor()).thenReturn("simulado");
        // La IA cita todo lo que encontro el catalogo: la prueba mira que busco, no como redacta
        when(asistente.responder(anyString(), any(), anyBoolean())).thenAnswer(inv -> {
            List<Candidato> candidatos = inv.getArgument(1);
            return new AsistenteCatalogo.Respuesta("Respuesta de prueba", candidatos.stream().map(Candidato::id).toList());
        });
    }

    private org.springframework.test.web.servlet.ResultActions preguntar(String pregunta) throws Exception {
        return mockMvc.perform(post("/api/asistente/catalogo").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("pregunta", pregunta))));
    }

    @Test
    void siLaLineaSupuestaNoTieneEseNombreBuscaElNombreEnTodasLasLineas() throws Exception {
        when(asistente.interpretar(anyString(), any(), any()))
                .thenReturn(new Interpretacion(List.of("aguaje"), List.of("SUPERFRUTOS"), List.of(), null, null, false));
        preguntar("aguaje")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.productos[*].nombre", everyItem(startsWith("Aceite de aguaje"))))
                .andExpect(jsonPath("$.busqueda.q").value("aguaje"))
                .andExpect(jsonPath("$.busqueda.lineas.length()").value(0));
    }

    @Test
    void siElNombreNoExisteEnNingunaLineaSigueMostrandoLaLinea() throws Exception {
        when(asistente.interpretar(anyString(), any(), any()))
                .thenReturn(new Interpretacion(List.of("zzzinexistente"), List.of("SUPERFRUTOS"), List.of(), null, null, false));
        preguntar("superfrutos zzzinexistente")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.productos[*].linea.codigo", everyItem(org.hamcrest.Matchers.is("SUPERFRUTOS"))))
                .andExpect(jsonPath("$.busqueda.lineas[0]").value("SUPERFRUTOS"));
    }
}
