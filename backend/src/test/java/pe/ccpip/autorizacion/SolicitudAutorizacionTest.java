package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-29 (RF-04): el productor solicita expresamente la autorizacion de un producto ante la Camara. Adjuntar
 * evidencias la prepara (BORRADOR) y solo al solicitarla llega a la revision del subadministrador. Alta,
 * autorizacion y publicacion siguen separadas, y un rechazo no impide volver a solicitarla (C4).
 */
@Transactional
class SolicitudAutorizacionTest extends IntegrationTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));

    private Cookie productor;
    private Cookie subAdmin;
    private long producto;

    @BeforeEach
    void productoSinAutorizacion() throws Exception {
        productor = loginAs("productor@ccpip.test");
        subAdmin = loginAs("sub_admin@ccpip.test");
        producto = productoConLote("Aguaje en pulpa 500 g", "BORRADOR", "PUNTO_ACOPIO", HOY.plusMonths(6));
        jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", producto);
    }

    @Test
    void adjuntarEvidenciasLaPreparaSinEnviarlaARevision() throws Exception {
        certificado().andExpect(status().isCreated());

        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", producto).cookie(productor))
                .andExpect(jsonPath("$.autorizacion.estado").value("BORRADOR"))
                .andExpect(jsonPath("$.autorizacion.solicitadaEn").doesNotExist())
                .andExpect(jsonPath("$.puedeSolicitar").value(true));
        mockMvc.perform(get("/api/admin/autorizaciones").cookie(subAdmin))
                .andExpect(jsonPath("$.solicitudes[*].id", not(hasItem((int) autorizacion()))));
        // El subadministrador no decide lo que el productor no solicito
        mockMvc.perform(post("/api/admin/autorizaciones/{id}/aprobar", autorizacion()).cookie(subAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("motivo", "x"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLICITUD_YA_RESUELTA"));
        mockMvc.perform(get("/api/admin/autorizaciones?estado=BORRADOR").cookie(subAdmin))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void elProductorLaSolicitaYLlegaALaBandejaDelSubadministrador() throws Exception {
        certificado().andExpect(status().isCreated());

        solicitar(productor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productoId").value(producto))
                .andExpect(jsonPath("$.autorizacion.estado").value("SOLICITADA"))
                .andExpect(jsonPath("$.autorizacion.solicitadaEn").isString())
                .andExpect(jsonPath("$.puedeSolicitar").value(false));

        mockMvc.perform(get("/api/admin/autorizaciones").cookie(subAdmin))
                .andExpect(jsonPath("$.solicitudes[*].id", hasItem((int) autorizacion())));
        mockMvc.perform(get("/api/admin/autorizaciones/{id}", autorizacion()).cookie(subAdmin))
                .andExpect(jsonPath("$.puedeDecidir").value(true));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'AUTORIZACION'
                  AND cambios ->> 'solicitada' IS NOT NULL""", Integer.class, producto)).isEqualTo(1);
        // Solicitar no publica la oferta: son procesos separados (C1)
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, producto)).isEqualTo("BORRADOR");
    }

    @Test
    void sinEvidenciasNoSePuedeSolicitar() throws Exception {
        solicitar(productor).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SIN_EVIDENCIAS"));

        // Tampoco si retiro todas las que tenia
        String id = objectMapper.readTree(certificado().andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/productor/productos/{p}/evidencias/{e}", producto, id).cookie(productor));
        solicitar(productor).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SIN_EVIDENCIAS"));
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacion()))
                .isEqualTo("BORRADOR");
    }

    @Test
    void unaSolicitudEnRevisionNoSeVuelveASolicitar() throws Exception {
        certificado().andExpect(status().isCreated());
        solicitar(productor).andExpect(status().isOk());
        solicitar(productor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLICITUD_EN_REVISION"));
    }

    @Test
    void unaAutorizacionVigenteNoSeSolicitaOtraVez() throws Exception {
        certificado().andExpect(status().isCreated());
        solicitar(productor).andExpect(status().isOk());
        decidir("aprobar", "Certificado vigente").andExpect(status().isOk());

        solicitar(productor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTORIZACION_VIGENTE"));
    }

    @Test
    void trasUnRechazoSePuedeVolverASolicitarSinVetoPermanente() throws Exception {
        certificado().andExpect(status().isCreated());
        solicitar(productor).andExpect(status().isOk());
        decidir("rechazar", "Falta el registro sanitario").andExpect(status().isOk());

        // C4: sin veto permanente; el motivo anterior queda en el historial
        solicitar(productor).andExpect(status().isOk())
                .andExpect(jsonPath("$.autorizacion.estado").value("SOLICITADA"))
                .andExpect(jsonPath("$.autorizacion.motivo").doesNotExist());
        mockMvc.perform(get("/api/admin/autorizaciones/{id}", autorizacion()).cookie(subAdmin))
                .andExpect(jsonPath("$.decisiones[0].decision").value("RECHAZADA"))
                .andExpect(jsonPath("$.puedeDecidir").value(true));
    }

    @Test
    void soloElProductorDuenoLaSolicita() throws Exception {
        certificado().andExpect(status().isCreated());

        solicitar(loginAs("productor2@ccpip.test")).andExpect(status().isNotFound());
        solicitar(subAdmin).andExpect(status().isForbidden());
        solicitar(loginAs("consumidor@ccpip.test")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/productor/productos/{id}/autorizacion/solicitar", producto))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacion()))
                .isEqualTo("BORRADOR");
    }

    // --- Apoyo ---

    private ResultActions certificado() throws Exception {
        return mockMvc.perform(multipart("/api/productor/productos/{id}/evidencias", producto)
                .file(new MockMultipartFile("archivo", "c.pdf", "application/octet-stream", PDF))
                .param("tipo", "CERTIFICADO").param("titulo", "Certificado orgánico de aguaje")
                .param("emisor", "Control Unión").param("vigenteDesde", HOY.minusDays(1).toString())
                .param("vigenteHasta", HOY.plusYears(1).toString())
                .cookie(productor));
    }

    private ResultActions solicitar(Cookie cookie) throws Exception {
        return mockMvc.perform(post("/api/productor/productos/{id}/autorizacion/solicitar", producto).cookie(cookie));
    }

    private ResultActions decidir(String accion, String motivo) throws Exception {
        return mockMvc.perform(post("/api/admin/autorizaciones/{id}/{accion}", autorizacion(), accion).cookie(subAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("motivo", motivo))));
    }

    private long autorizacion() {
        return jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, producto);
    }
}
