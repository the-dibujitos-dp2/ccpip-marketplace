package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-34 (RF-11): el productor declara por producto las reglas de origen y sostenibilidad de la Camara que
 * cumple, con fecha. No hay reglas universales: cada producto declara las suyas, y declarar no es acreditar.
 */
@Transactional
class CumplimientoTest extends IntegrationTest {

    private Cookie productor;
    private long producto;

    @BeforeEach
    void productoPropio() throws Exception {
        productor = loginAs("productor@ccpip.test");
        producto = productoConLote("Cacao fino " + UUID.randomUUID(), "PUBLICADO", null, null);
    }

    @Test
    void sinDeclaracionMuestraElCatalogoDeLaCamaraSinAsumirNingunaRegla() throws Exception {
        consultar(productor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productoId").value(producto))
                .andExpect(jsonPath("$.reglas.length()").value(3))
                .andExpect(jsonPath("$.reglas[0].codigo").value("CERO_DEFORESTACION"))
                .andExpect(jsonPath("$.reglas[0].nombre").value("Cero deforestación"))
                .andExpect(jsonPath("$.reglas[0].nombreEn").value("Zero deforestation"))
                .andExpect(jsonPath("$.reglas[0].descripcion").value("Cero deforestación"))
                .andExpect(jsonPath("$.reglas[0].descripcionEn").value("Zero deforestation"))
                .andExpect(jsonPath("$.reglas[1].descripcion").value("Cero pesticidas o herbicidas"))
                .andExpect(jsonPath("$.reglas[1].descripcionEn").value("Zero pesticides or herbicides"))
                .andExpect(jsonPath("$.reglas[2].descripcion").value("Elaborado por comunidad indígena"))
                .andExpect(jsonPath("$.reglas[2].descripcionEn").value("Made by an Indigenous community"))
                .andExpect(jsonPath("$.vigente").doesNotExist())
                .andExpect(jsonPath("$.historial").isEmpty());
    }

    @Test
    void declaraLasReglasQueCumpleElProductoConFechaYQuedaAuditada() throws Exception {
        declarar(productor, List.of("CERO_DEFORESTACION", "ELABORADO_POR_COMUNIDAD"), "Cosecha de bosque en pie")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vigente.reglas.length()").value(2))
                .andExpect(jsonPath("$.vigente.reglas[0]").value("CERO_DEFORESTACION"))
                .andExpect(jsonPath("$.vigente.reglas[1]").value("ELABORADO_POR_COMUNIDAD"))
                .andExpect(jsonPath("$.vigente.observaciones").value("Cosecha de bosque en pie"))
                .andExpect(jsonPath("$.vigente.declaradaPor").isString())
                .andExpect(jsonPath("$.vigente.declaradaEn").isString());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'CUMPLIMIENTO'""",
                Integer.class, producto)).isEqualTo(1);
    }

    @Test
    void cadaProductoDeclaraLasSuyas() throws Exception {
        long otro = productoConLote("Otro " + UUID.randomUUID(), "PUBLICADO", null, null);
        declarar(productor, List.of("CERO_PESTICIDAS"), null).andExpect(status().isCreated());

        mockMvc.perform(get("/api/productor/productos/{id}/cumplimiento", otro).cookie(productor))
                .andExpect(jsonPath("$.vigente").doesNotExist());
    }

    @Test
    void unaNuevaDeclaracionReemplazaALaAnteriorQueQuedaEnElHistorial() throws Exception {
        declarar(productor, List.of("CERO_DEFORESTACION", "CERO_PESTICIDAS"), null).andExpect(status().isCreated());
        // Ya no cumple ninguna: tambien se declara
        declarar(productor, List.of(), "Cambiamos de proveedor de semillas")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vigente.reglas").isEmpty())
                .andExpect(jsonPath("$.historial.length()").value(2))
                .andExpect(jsonPath("$.historial[1].reglas.length()").value(2));

        // El registro no se reescribe ni se borra
        Long primera = jdbc.queryForObject("SELECT min(id) FROM declaracion_cumplimiento WHERE producto_id = ?", Long.class, producto);
        assertThatThrownBy(() -> jdbc.update("UPDATE declaracion_cumplimiento SET observaciones = 'x' WHERE id = ?", primera))
                .hasMessageContaining("CCPIP-34");
    }

    @Test
    void soloSeDeclaranReglasVigentesDeLaCamaraSinRepetir() throws Exception {
        declarar(productor, List.of("CERO_DEFORESTACION", "INVENTADA"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REGLA_INVALIDA"));
        declarar(productor, List.of("CERO_PESTICIDAS", "CERO_PESTICIDAS"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REGLA_INVALIDA"));
        jdbc.update("UPDATE regla_cumplimiento SET activa = false WHERE codigo = 'CERO_PESTICIDAS'");
        declarar(productor, List.of("CERO_PESTICIDAS"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REGLA_INVALIDA"));
        Map<String, Object> sinReglas = new HashMap<>();
        sinReglas.put("observaciones", "x");
        mockMvc.perform(post("/api/productor/productos/{id}/cumplimiento", producto).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(sinReglas)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.reglas").isString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM declaracion_cumplimiento WHERE producto_id = ?",
                Integer.class, producto)).isZero();
    }

    @Test
    void soloElProductorDuenoDeclaraYConsulta() throws Exception {
        Cookie otro = loginAs("productor2@ccpip.test");
        consultar(otro).andExpect(status().isNotFound());
        declarar(otro, List.of("CERO_PESTICIDAS"), null).andExpect(status().isNotFound());
        for (String correo : new String[] {"sub_admin@ccpip.test", "consumidor@ccpip.test"}) {
            Cookie cookie = loginAs(correo);
            consultar(cookie).andExpect(status().isForbidden());
            declarar(cookie, List.of("CERO_PESTICIDAS"), null).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/productor/productos/{id}/cumplimiento", producto)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM declaracion_cumplimiento WHERE producto_id = ?",
                Integer.class, producto)).isZero();
    }

    @Test
    void declararNoEsAcreditarNiDaSellos() throws Exception {
        String antes = jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE producto_id = ?", String.class, producto);
        declarar(productor, List.of("CERO_DEFORESTACION", "CERO_PESTICIDAS", "ELABORADO_POR_COMUNIDAD"), null)
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE producto_id = ?", String.class, producto))
                .isEqualTo(antes);
        // La certificacion publica no cambia por una declaracion
        mockMvc.perform(get("/api/productos/{id}/certificacion", producto))
                .andExpect(jsonPath("$.estado").value("SIN_EVIDENCIA"));
    }

    // --- Ayudas ---

    private ResultActions consultar(Cookie cookie) throws Exception {
        return mockMvc.perform(get("/api/productor/productos/{id}/cumplimiento", producto).cookie(cookie));
    }

    private ResultActions declarar(Cookie cookie, List<String> reglas, String observaciones) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("reglas", reglas);
        cuerpo.put("observaciones", observaciones);
        return mockMvc.perform(post("/api/productor/productos/{id}/cumplimiento", producto).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(cuerpo)));
    }
}
