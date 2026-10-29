package pe.ccpip.catalogomaestro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-119: el productor pide una unidad de medida nueva. CCPIP-137: la Camara (superadministrador) la aprueba
 * y entra al catalogo. CCPIP-353: la rechaza con su motivo. Pendiente, no se puede usar.
 */
@Transactional
class UnidadesSolicitadasTest extends IntegrationTest {

    private static final String PRODUCTOR = "/api/productor/unidades/solicitudes";
    private static final String ADMIN = "/api/admin/catalogo/unidades/solicitudes";

    private ResultActions pedir(Cookie sesion, String nombre, String justificacion) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("nombre", nombre);
        cuerpo.put("justificacion", justificacion);
        return mockMvc.perform(post(PRODUCTOR).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private ResultActions decidir(Cookie sesion, long id, String accion, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post(ADMIN + "/{id}/" + accion, id).cookie(sesion)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private long pedida(String nombre) throws Exception {
        String r = pedir(loginAs("productor@ccpip.test"), nombre, "Vendemos el sachainchi por atados de 10 vainas")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(r).get("id").asLong();
    }

    private boolean enCatalogo(String nombre) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM unidad_medida WHERE nombre = ?)", Boolean.class, nombre));
    }

    // ---------------------------------------------------------------- CCPIP-119

    @Test
    void elProductorPideUnaUnidadQueQuedaPendienteYNoSePuedeUsar() throws Exception {
        Cookie sesion = loginAs("productor@ccpip.test");
        pedir(sesion, "  Atado  ", "Vendemos el sachainchi por atados de 10 vainas")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Atado"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.unidad").doesNotExist());

        mockMvc.perform(get(PRODUCTOR).cookie(sesion)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].justificacion").value("Vendemos el sachainchi por atados de 10 vainas"));
        // No esta en el catalogo que usan los formularios
        assertThat(enCatalogo("Atado")).isFalse();
        mockMvc.perform(get("/api/catalogos")).andExpect(jsonPath("$.unidades[?(@.nombre == 'Atado')]").isEmpty());
    }

    @Test
    void noSePideUnaUnidadQueYaExisteNiSeRepiteUnaPendiente() throws Exception {
        Cookie sesion = loginAs("productor@ccpip.test");
        pedir(sesion, "kilogramo", "Para granos").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDAD_YA_EXISTE"));
        pedir(sesion, "Atado", "Por atados").andExpect(status().isCreated());
        pedir(sesion, "ÁTADO", "Otra vez").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLICITUD_UNIDAD_PENDIENTE"));
    }

    @Test
    void validaLosDatosDeLaSolicitud() throws Exception {
        Cookie sesion = loginAs("productor@ccpip.test");
        pedir(sesion, " ", "Algo").andExpect(status().isBadRequest());
        pedir(sesion, "Atado", "").andExpect(status().isBadRequest());
        pedir(sesion, "x".repeat(61), "Algo").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM solicitud_unidad_medida", Long.class)).isZero();
    }

    @Test
    void cadaProductorVeSoloLasSuyasYSoloElProductorPide() throws Exception {
        pedida("Atado");
        mockMvc.perform(get(PRODUCTOR).cookie(loginAs("productor2@ccpip.test"))).andExpect(jsonPath("$", hasSize(0)));
        pedir(loginAs("consumidor@ccpip.test"), "Atado", "x").andExpect(status().isForbidden());
        mockMvc.perform(post(PRODUCTOR).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- CCPIP-137

    @Test
    void laCamaraLaApruebaYEntraAlCatalogoConAuditoria() throws Exception {
        long id = pedida("Atado");
        Cookie sudo = loginAs("sudo@ccpip.test");
        mockMvc.perform(get(ADMIN).cookie(sudo)).andExpect(jsonPath("$[?(@.id == " + id + ")].productor").isNotEmpty());

        decidir(sudo, id, "aprobar", Map.of("nombre", "Atado de 10", "nombreEn", "Bundle of 10", "motivo", "Se usa en varias comunidades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.unidad.codigo").value("ATADO_DE_10"))
                .andExpect(jsonPath("$.unidad.nombre").value("Atado de 10"))
                .andExpect(jsonPath("$.motivo").value("Se usa en varias comunidades"))
                .andExpect(jsonPath("$.decididaPor").isNotEmpty());

        mockMvc.perform(get("/api/catalogos")).andExpect(jsonPath("$.unidades[?(@.codigo == 'ATADO_DE_10')].nombre").value("Atado de 10"));
        mockMvc.perform(get("/api/catalogos").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.unidades[?(@.codigo == 'ATADO_DE_10')].nombre").value("Bundle of 10"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM catalogo_maestro_auditoria a JOIN unidad_medida u ON u.id = a.entidad_id
                WHERE a.entidad = 'UNIDAD' AND a.accion = 'CREAR' AND u.codigo = 'ATADO_DE_10'""", Long.class)).isEqualTo(1);
        // El productor ve la decision
        mockMvc.perform(get(PRODUCTOR).cookie(loginAs("productor@ccpip.test")))
                .andExpect(jsonPath("$[0].estado").value("APROBADA"))
                .andExpect(jsonPath("$[0].unidad.codigo").value("ATADO_DE_10"));
    }

    @Test
    void noApruebaDosVecesNiUnaUnidadQueYaExisteNiUnCodigoInvalido() throws Exception {
        Cookie sudo = loginAs("sudo@ccpip.test");
        long id = pedida("Atado");
        decidir(sudo, id, "aprobar", Map.of("nombre", "Litro", "motivo", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDAD_YA_EXISTE"));
        decidir(sudo, id, "aprobar", Map.of("codigo", "kg", "motivo", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODIGO_DUPLICADO"));
        decidir(sudo, id, "aprobar", Map.of("codigo", "ATADO-10", "motivo", "x")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CODIGO_INVALIDO"));
        decidir(sudo, id, "aprobar", Map.of("motivo", " ")).andExpect(status().isBadRequest());
        assertThat(enCatalogo("Atado")).isFalse();

        decidir(sudo, id, "aprobar", Map.of("motivo", "Aprobada")).andExpect(status().isOk())
                .andExpect(jsonPath("$.unidad.codigo").value("ATADO"));
        decidir(sudo, id, "aprobar", Map.of("motivo", "Otra vez")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLICITUD_YA_RESUELTA"));
        decidir(sudo, id, "rechazar", Map.of("motivo", "Tarde")).andExpect(status().isConflict());
        decidir(sudo, 999_999, "aprobar", Map.of("motivo", "x")).andExpect(status().isNotFound());
    }

    @Test
    void soloElSuperadministradorDecide() throws Exception {
        long id = pedida("Atado");
        decidir(loginAs("sub_admin@ccpip.test"), id, "aprobar", Map.of("motivo", "x")).andExpect(status().isForbidden());
        decidir(loginAs("productor@ccpip.test"), id, "aprobar", Map.of("motivo", "x")).andExpect(status().isForbidden());
        mockMvc.perform(get(ADMIN).cookie(loginAs("sub_admin@ccpip.test"))).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- CCPIP-353

    @Test
    void laCamaraLaRechazaConMotivoYElCatalogoNoCambia() throws Exception {
        long id = pedida("Atado");
        long unidades = jdbc.queryForObject("SELECT count(*) FROM unidad_medida", Long.class);
        Cookie sudo = loginAs("sudo@ccpip.test");

        decidir(sudo, id, "rechazar", Map.of("motivo", "")).andExpect(status().isBadRequest());
        decidir(sudo, id, "rechazar", Map.of("motivo", "Usen Unidad y escriban «atado de 10» en la presentación"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.motivo").value("Usen Unidad y escriban «atado de 10» en la presentación"))
                .andExpect(jsonPath("$.unidad").doesNotExist());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM unidad_medida", Long.class)).isEqualTo(unidades);
        mockMvc.perform(get(PRODUCTOR).cookie(loginAs("productor@ccpip.test")))
                .andExpect(jsonPath("$[0].estado").value("RECHAZADA"))
                .andExpect(jsonPath("$[0].motivo").value("Usen Unidad y escriban «atado de 10» en la presentación"));
        mockMvc.perform(get(ADMIN).param("estado", "RECHAZADA").cookie(sudo)).andExpect(jsonPath("$", hasSize(1)));
        // Rechazada, puede volver a pedirla
        pedir(loginAs("productor@ccpip.test"), "Atado", "Lo necesitamos igual").andExpect(status().isCreated());
    }

    @Test
    void unaSolicitudDecididaNoCambiaNiSeBorra() throws Exception {
        long id = pedida("Atado");
        decidir(loginAs("sudo@ccpip.test"), id, "rechazar", Map.of("motivo", "No")).andExpect(status().isOk());
        assertRechazaLaBase(() -> jdbc.update("UPDATE solicitud_unidad_medida SET motivo = 'Otro' WHERE id = ?", id), "ya decidida no cambia");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM solicitud_unidad_medida WHERE id = ?", id), "no se borra");
    }

    @Test
    void derivaElCodigoDelNombre() {
        assertThat(UnidadesSolicitadasService.codigoDe("Atado de 10")).isEqualTo("ATADO_DE_10");
        assertThat(UnidadesSolicitadasService.codigoDe("  Mazo (pequeño) ")).isEqualTo("MAZO_PEQUENO");
        assertThat(UnidadesSolicitadasService.codigoDe("Racimo de plátanos maduros")).isEqualTo("RACIMO_DE_PLATANOS_M");
    }
}
