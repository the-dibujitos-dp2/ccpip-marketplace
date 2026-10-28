package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
 * CCPIP-35 (RF-12) y CCPIP-329 (RF-219): el subadministrador registra el envio y la recepcion de una
 * evidencia fisica de la solicitud de un producto. Es opcional, conserva resultado y observaciones, y no
 * decide la solicitud.
 */
@Transactional
class EvidenciasFisicasTest extends IntegrationTest {

    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));

    private Cookie subAdmin;
    private long producto;
    private long solicitud;

    /** Una solicitud pendiente de evaluacion (la semilla de la prueba la deja aprobada). */
    @BeforeEach
    void solicitudPendiente() throws Exception {
        subAdmin = loginAs("sub_admin@ccpip.test");
        producto = productoConLote("Cacao en grano " + UUID.randomUUID(), "BORRADOR", null, null);
        solicitud = jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, producto);
        jdbc.update("UPDATE autorizacion_producto SET estado = 'SOLICITADA', decidido_en = NULL WHERE id = ?", solicitud);
    }

    // --- CCPIP-35: envio ---

    @Test
    void registraElEnvioDeUnaEvidenciaFisicaYLoAudita() throws Exception {
        enviar(subAdmin, envio("Muestra de 500 g de cacao en grano", HOY.minusDays(2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.autorizacionId").value(solicitud))
                .andExpect(jsonPath("$.productoId").value(producto))
                .andExpect(jsonPath("$.descripcion").value("Muestra de 500 g de cacao en grano"))
                .andExpect(jsonPath("$.referencia").value("Guía 001-2345"))
                .andExpect(jsonPath("$.enviadaEl").value(HOY.minusDays(2).toString()))
                .andExpect(jsonPath("$.observaciones").value("La lleva la agencia de Pucallpa"))
                .andExpect(jsonPath("$.registradaPor").isString())
                .andExpect(jsonPath("$.estado").value("ENVIADA"))
                .andExpect(jsonPath("$.recepcion").doesNotExist());

        mockMvc.perform(get("/api/admin/autorizaciones/{id}/evidencias-fisicas", solicitud).cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].estado").value("ENVIADA"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'EVIDENCIA'
                  AND cambios ->> 'evidenciaFisicaEnviada' IS NOT NULL""", Integer.class, producto)).isEqualTo(1);
    }

    @Test
    void laEvidenciaFisicaEsOpcionalYNoCambiaLaSolicitud() throws Exception {
        // Sin ninguna evidencia fisica, la solicitud sigue como estaba: nada la exige
        mockMvc.perform(get("/api/admin/autorizaciones/{id}/evidencias-fisicas", solicitud).cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        long fisica = idDe(enviar(subAdmin, envio("Muestra", HOY)).andExpect(status().isCreated()));
        recibir(subAdmin, fisica, recepcion(HOY, "NO_CONFORME", "Llegó con humedad")).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, solicitud))
                .isEqualTo("SOLICITADA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autorizacion_decision WHERE autorizacion_id = ?",
                Integer.class, solicitud)).isZero();
    }

    @Test
    void elEnvioRechazaDatosInvalidos() throws Exception {
        enviar(subAdmin, envio(" ", HOY)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.descripcion").isString());
        Map<String, Object> sinFecha = envio("Muestra", HOY);
        sinFecha.remove("enviadaEl");
        enviar(subAdmin, sinFecha).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.enviadaEl").isString());
        enviar(subAdmin, envio("x".repeat(301), HOY)).andExpect(status().isBadRequest());
        enviar(subAdmin, envio("Muestra", HOY.plusDays(1))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FECHA_FUTURA"));
        assertThat(cuantas()).isZero();
    }

    @Test
    void soloSeRegistraElEnvioDeUnaSolicitudEnEvaluacion() throws Exception {
        jdbc.update("UPDATE autorizacion_producto SET estado = 'RECHAZADA', decidido_en = now() WHERE id = ?", solicitud);
        enviar(subAdmin, envio("Muestra", HOY)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLICITUD_YA_RESUELTA"));
        mockMvc.perform(post("/api/admin/autorizaciones/{id}/evidencias-fisicas", 999_999_999L).cookie(subAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(envio("Muestra", HOY))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLICITUD_NO_ENCONTRADA"));
        assertThat(cuantas()).isZero();
    }

    // --- CCPIP-329: recepcion ---

    @Test
    void registraLaRecepcionConResultadoYObservaciones() throws Exception {
        long fisica = idDe(enviar(subAdmin, envio("Muestra de 500 g", HOY.minusDays(3))).andExpect(status().isCreated()));

        recibir(subAdmin, fisica, recepcion(HOY.minusDays(1), "OBSERVADA", "El empaque llegó abierto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECIBIDA"))
                // El envio se conserva tal cual
                .andExpect(jsonPath("$.descripcion").value("Muestra de 500 g"))
                .andExpect(jsonPath("$.enviadaEl").value(HOY.minusDays(3).toString()))
                .andExpect(jsonPath("$.recepcion.recibidaEl").value(HOY.minusDays(1).toString()))
                .andExpect(jsonPath("$.recepcion.resultado").value("OBSERVADA"))
                .andExpect(jsonPath("$.recepcion.observaciones").value("El empaque llegó abierto"))
                .andExpect(jsonPath("$.recepcion.registradaPor").isString())
                .andExpect(jsonPath("$.recepcion.registradaEn").isString());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'EVIDENCIA'
                  AND cambios ->> 'evidenciaFisicaRecibida' IS NOT NULL""", Integer.class, producto)).isEqualTo(1);
    }

    @Test
    void conformeNoExigeObservaciones() throws Exception {
        long fisica = idDe(enviar(subAdmin, envio("Muestra", HOY)).andExpect(status().isCreated()));
        recibir(subAdmin, fisica, recepcion(HOY, "CONFORME", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recepcion.resultado").value("CONFORME"));
    }

    @Test
    void laRecepcionSeRegistraUnaSolaVezYNoSeReescribe() throws Exception {
        long fisica = idDe(enviar(subAdmin, envio("Muestra", HOY)).andExpect(status().isCreated()));
        recibir(subAdmin, fisica, recepcion(HOY, "CONFORME", null)).andExpect(status().isOk());

        recibir(subAdmin, fisica, recepcion(HOY, "NO_CONFORME", "Otra cosa")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCIA_FISICA_YA_RECIBIDA"));
        assertThat(jdbc.queryForObject("SELECT resultado FROM evidencia_fisica WHERE id = ?", String.class, fisica))
                .isEqualTo("CONFORME");
        // Ni por fuera de la API: la base conserva el registro (despues de este error la transaccion queda abortada)
        assertThatThrownBy(() -> jdbc.update("UPDATE evidencia_fisica SET resultado = 'NO_CONFORME' WHERE id = ?", fisica))
                .hasMessageContaining("CCPIP-329");
    }

    @Test
    void unaEvidenciaFisicaNoSeBorra() throws Exception {
        long fisica = idDe(enviar(subAdmin, envio("Muestra", HOY)).andExpect(status().isCreated()));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM evidencia_fisica WHERE id = ?", fisica))
                .hasMessageContaining("CCPIP-35");
    }

    @Test
    void laRecepcionRechazaDatosInvalidos() throws Exception {
        long fisica = idDe(enviar(subAdmin, envio("Muestra", HOY.minusDays(1))).andExpect(status().isCreated()));

        recibir(subAdmin, fisica, recepcion(HOY.minusDays(2), "CONFORME", null)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECEPCION_ANTES_DEL_ENVIO"));
        recibir(subAdmin, fisica, recepcion(HOY.plusDays(1), "CONFORME", null)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FECHA_FUTURA"));
        recibir(subAdmin, fisica, recepcion(HOY, "NO_CONFORME", "  ")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OBSERVACIONES_REQUERIDAS"));
        recibir(subAdmin, fisica, recepcion(HOY, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.resultado").isString());
        recibir(subAdmin, fisica, recepcion(HOY, "APROBADA", null)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT estado FROM evidencia_fisica WHERE id = ?", String.class, fisica))
                .isEqualTo("ENVIADA");
    }

    @Test
    void laRecepcionEsDeUnaEvidenciaDeEstaSolicitud() throws Exception {
        long fisica = idDe(enviar(subAdmin, envio("Muestra", HOY)).andExpect(status().isCreated()));
        long otroProducto = productoConLote("Otro " + UUID.randomUUID(), "BORRADOR", null, null);
        long otraSolicitud = jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, otroProducto);

        mockMvc.perform(post("/api/admin/autorizaciones/{id}/evidencias-fisicas/{f}/recepcion", otraSolicitud, fisica)
                        .cookie(subAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(recepcion(HOY, "CONFORME", null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVIDENCIA_FISICA_NO_ENCONTRADA"));
    }

    @Test
    void laMuestraQueLlegaDespuesDeDecidirTambienSeRegistra() throws Exception {
        long fisica = idDe(enviar(subAdmin, envio("Muestra", HOY)).andExpect(status().isCreated()));
        jdbc.update("UPDATE autorizacion_producto SET estado = 'RECHAZADA', decidido_en = now() WHERE id = ?", solicitud);
        recibir(subAdmin, fisica, recepcion(HOY, "CONFORME", null)).andExpect(status().isOk());
    }

    // --- Permisos (RNF-04) ---

    @Test
    void soloElSubadministradorRegistraYConsulta() throws Exception {
        long fisica = idDe(enviar(subAdmin, envio("Muestra", HOY)).andExpect(status().isCreated()));
        for (String correo : new String[] {"productor@ccpip.test", "sudo@ccpip.test", "consumidor@ccpip.test"}) {
            Cookie otro = loginAs(correo);
            enviar(otro, envio("Muestra", HOY)).andExpect(status().isForbidden());
            recibir(otro, fisica, recepcion(HOY, "CONFORME", null)).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/autorizaciones/{id}/evidencias-fisicas", solicitud).cookie(otro))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/admin/autorizaciones/{id}/evidencias-fisicas", solicitud)).andExpect(status().isUnauthorized());
        assertThat(cuantas()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT estado FROM evidencia_fisica WHERE id = ?", String.class, fisica))
                .isEqualTo("ENVIADA");
    }

    // --- Ayudas ---

    private static Map<String, Object> envio(String descripcion, LocalDate enviadaEl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("descripcion", descripcion);
        m.put("referencia", "Guía 001-2345");
        m.put("enviadaEl", enviadaEl.toString());
        m.put("observaciones", "La lleva la agencia de Pucallpa");
        return m;
    }

    private static Map<String, Object> recepcion(LocalDate recibidaEl, String resultado, String observaciones) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recibidaEl", recibidaEl.toString());
        m.put("resultado", resultado);
        m.put("observaciones", observaciones);
        return m;
    }

    private ResultActions enviar(Cookie cookie, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post("/api/admin/autorizaciones/{id}/evidencias-fisicas", solicitud).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(cuerpo)));
    }

    private ResultActions recibir(Cookie cookie, long fisica, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post("/api/admin/autorizaciones/{id}/evidencias-fisicas/{f}/recepcion", solicitud, fisica)
                .cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(cuerpo)));
    }

    private long idDe(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private int cuantas() {
        return jdbc.queryForObject("SELECT count(*) FROM evidencia_fisica WHERE autorizacion_id = ?", Integer.class, solicitud);
    }
}
