package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.aviso.Correo;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-321 (RF-205): el subadministrador registra una solicitud de renovacion de un certificado por
 * producto. Alerta al productor y queda para la revision; no cambia la autorizacion ni pausa la venta
 * (C5) y nunca la bloquea.
 */
@Transactional
class RenovacionTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String SUB = "sub_admin@ccpip.test";

    private long producto;
    private long autorizacion;
    private UUID certificado;
    private LocalDate vence;

    /** Una oferta aprobada de productor@ con un certificado que vence en 20 dias. */
    @BeforeEach
    void ofertaConCertificado() {
        producto = productoConLote("Miel certificada " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", null);
        autorizacion = jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, producto);
        vence = hoy().plusDays(20);
        certificado = evidencia("CERTIFICADO", vence);
        jdbc.update("UPDATE autorizacion_producto SET vigente_hasta = ? WHERE id = ?", vence, autorizacion);
    }

    @Test
    void registrarLaSolicitudAvisaAlProductorYNoCambiaLaVenta() throws Exception {
        int decisiones = decisiones();
        boolean comprable = comprable();

        solicitar(loginAs(SUB), certificado, "El certificado orgánico vence este mes", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.autorizacionId").value(autorizacion))
                .andExpect(jsonPath("$.productoId").value(producto))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.motivo").value("El certificado orgánico vence este mes"))
                .andExpect(jsonPath("$.certificado.evidenciaId").value(certificado.toString()))
                .andExpect(jsonPath("$.certificado.vencido").value(false))
                // Sin plazo, el vencimiento del certificado
                .andExpect(jsonPath("$.plazo").value(vence.toString()))
                // C5: si no se renueva, la venta se pausa al dia siguiente del vencimiento
                .andExpect(jsonPath("$.ventaPausadaDesde").value(vence.plusDays(1).toString()))
                .andExpect(jsonPath("$.solicitadaPor").value("Sub-Administrador de Prueba"));

        // La autorizacion sigue igual: aprobada, sin decision nueva, y la oferta se sigue vendiendo
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacion))
                .isEqualTo("APROBADA");
        assertThat(decisiones()).isEqualTo(decisiones);
        assertThat(comprable()).isEqualTo(comprable);

        // Alerta por correo, registrada como aviso
        Correo correo = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(correo.asunto()).startsWith("Renueva el certificado de Miel certificada");
        assertThat(correo.texto()).contains("Motivo: El certificado orgánico vence este mes", "no se revoca");
        assertThat(jdbc.queryForObject("SELECT estado FROM aviso WHERE tipo = 'RENOVACION_CERTIFICADO' ORDER BY id DESC LIMIT 1",
                String.class)).isEqualTo("ENVIADO");
        // Y en la auditoria del producto
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'AUTORIZACION'
                  AND cambios ->> 'renovacionSolicitada' IS NOT NULL""", Integer.class, producto)).isEqualTo(1);
    }

    @Test
    void elSubadministradorLaVeEnElExpedienteYElProductorEnSuProducto() throws Exception {
        solicitar(loginAs(SUB), certificado, "Renovar", vence.minusDays(5)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/autorizaciones/" + autorizacion + "/renovaciones").cookie(loginAs(SUB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].plazo").value(vence.minusDays(5).toString()));

        mockMvc.perform(get("/api/productor/productos/" + producto + "/renovaciones").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].motivo").value("Renovar"))
                .andExpect(jsonPath("$[0].solicitadaPor").doesNotExist());
        // Otro productor no ve las de un producto ajeno (RNF-05)
        mockMvc.perform(get("/api/productor/productos/" + producto + "/renovaciones").cookie(loginAs("productor2@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void unCertificadoVencidoTambienSePuedePedirYElPlazoEsHoy() throws Exception {
        UUID vencido = evidencia("CERTIFICADO", hoy().minusDays(3));
        solicitar(loginAs(SUB), vencido, "Ya venció", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificado.vencido").value(true))
                .andExpect(jsonPath("$.plazo").value(hoy().toString()));
    }

    @Test
    void unaSolaPendientePorCertificado() throws Exception {
        Cookie sesion = loginAs(SUB);
        solicitar(sesion, certificado, "Primera", null).andExpect(status().isCreated());
        solicitar(sesion, certificado, "Segunda", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENOVACION_PENDIENTE"));
    }

    @Test
    void reglasDeLaSolicitud() throws Exception {
        Cookie sesion = loginAs(SUB);
        solicitar(sesion, certificado, "Motivo", hoy().minusDays(1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLAZO_PASADO"));
        solicitar(sesion, certificado, "  ", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.motivo").isString());
        // Solo un certificado de esta autorizacion
        solicitar(sesion, evidencia("EVIDENCIA", null), "Motivo", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICADO_INVALIDO"));
        solicitar(sesion, UUID.randomUUID(), "Motivo", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICADO_INVALIDO"));
        mockMvc.perform(post("/api/admin/autorizaciones/999999999/renovaciones").cookie(sesion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("evidenciaId", certificado.toString(), "motivo", "Motivo"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLICITUD_NO_ENCONTRADA"));
    }

    @Test
    void soloDeUnaAutorizacionAprobada() throws Exception {
        jdbc.update("UPDATE autorizacion_producto SET estado = 'SOLICITADA', solicitada_en = now() WHERE id = ?", autorizacion);
        solicitar(loginAs(SUB), certificado, "Motivo", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTORIZACION_NO_APROBADA"));
    }

    @Test
    void siElCorreoFallaLaSolicitudIgualQueda() throws Exception {
        correos.fallar(true);
        solicitar(loginAs(SUB), certificado, "Motivo", null).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM renovacion_certificado WHERE evidencia_id = ?",
                Integer.class, certificado)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT estado FROM aviso WHERE tipo = 'RENOVACION_CERTIFICADO' ORDER BY id DESC LIMIT 1",
                String.class)).isEqualTo("FALLIDO");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sudo@ccpip.test", PRODUCTOR, "consumidor@ccpip.test", "admin_acopio@ccpip.test"})
    void registrarlaEsDelSubadministrador(String correo) throws Exception {
        solicitar(loginAs(correo), certificado, "Motivo", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void laSolicitudNoSeEditaNiSeBorra() throws Exception {
        solicitar(loginAs(SUB), certificado, "Motivo", null).andExpect(status().isCreated());
        assertRechazaLaBase(() -> jdbc.update("UPDATE renovacion_certificado SET motivo = 'Otro' WHERE evidencia_id = ?",
                certificado), "solo cambia de PENDIENTE");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM renovacion_certificado WHERE evidencia_id = ?", certificado))
                .isInstanceOf(DataAccessException.class);
    }

    // --- Ayudas ---

    private ResultActions solicitar(Cookie sesion, UUID evidencia, String motivo, LocalDate plazo) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("evidenciaId", evidencia.toString());
        cuerpo.put("motivo", motivo);
        if (plazo != null) {
            cuerpo.put("plazo", plazo.toString());
        }
        return mockMvc.perform(post("/api/admin/autorizaciones/" + autorizacion + "/renovaciones").cookie(sesion)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private UUID evidencia(String tipo, LocalDate hasta) {
        return jdbc.queryForObject("""
                INSERT INTO evidencia_producto (autorizacion_id, productor_id, producto_id, tipo, titulo, emisor,
                                                vigente_desde, vigente_hasta, nombre_archivo, content_type, bytes, clave,
                                                subida_por)
                SELECT a.id, a.productor_id, a.producto_id, ?, 'Certificado orgánico', 'SENASA', ?::date - 365, ?,
                       'cert.pdf', 'application/pdf', 10, 'evidencias/' || gen_random_uuid(),
                       (SELECT usuario_id FROM productor WHERE id = a.productor_id)
                FROM autorizacion_producto a WHERE a.id = ?
                RETURNING id""", UUID.class, tipo, hasta == null ? hoy() : hasta, hasta, autorizacion);
    }

    /** Lo que dice la ficha publica: si hoy se puede comprar (CatalogoRepository.HABILITADA). */
    private boolean comprable() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/productos/" + producto)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("comprable").asBoolean();
    }

    private int decisiones() {
        return jdbc.queryForObject("SELECT count(*) FROM autorizacion_decision WHERE autorizacion_id = ?", Integer.class,
                autorizacion);
    }

    private LocalDate hoy() {
        return jdbc.queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
    }
}
