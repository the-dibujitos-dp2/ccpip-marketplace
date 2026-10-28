package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.ia.AnalistaEvidencias;
import pe.ccpip.ia.AnalistaNoConfigurado;
import pe.ccpip.imagenes.Imagenes;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-36 (RF-13): evidencias por producto. CCPIP-50 (RF-74): analisis preliminar (simulado en
 * pruebas). CCPIP-174 (RF-166) y CCPIP-284 (RF-204): aprobar y rechazar con motivo. Transaccional:
 * las solicitudes que arma la prueba no llegan a las demas.
 */
@Transactional
class EvidenciasRevisionTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String OTRO_PRODUCTOR = "productor2@ccpip.test";
    private static final String SUB_ADMIN = "sub_admin@ccpip.test";
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDate EN_UN_ANIO = LocalDate.now(java.time.ZoneId.of("America/Lima")).plusYears(1);

    @Autowired
    private RevisionService revision;

    private Cookie productor;
    private Cookie subAdmin;
    private long miel;

    @BeforeEach
    void preparar() throws Exception {
        productor = loginAs(PRODUCTOR);
        subAdmin = loginAs(SUB_ADMIN);
        // Publicado, con existencias y cuenta bancaria, pero aun sin autorizacion: en "Venta pausada"
        miel = productoConLote("Miel de abeja nativa 250 g", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now(java.time.ZoneId.of("America/Lima")).plusMonths(6));
        jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", miel);
    }

    @Test
    void vinculaUnCertificadoCompletoAlLotePropioSinCambiarRevisionNiPrivacidad() throws Exception {
        String id = json(subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "Emisor", EN_UN_ANIO))
                .get("id").asText();
        long autorizacionId = solicitud(miel);
        decidir(autorizacionId, "aprobar", "Revisión humana previa al vínculo").andExpect(status().isOk());
        String estadoPrevio = jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacionId);
        long lote = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ? ORDER BY id LIMIT 1", Long.class, miel);
        mockMvc.perform(post("/api/productor/productos/{p}/evidencias/{e}/lote", miel, id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId", lote))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenciaId").value(id))
                .andExpect(jsonPath("$.productoId").value(miel))
                .andExpect(jsonPath("$.loteId").value(lote));
        assertThat(jdbc.queryForObject("SELECT lote_id FROM evidencia_producto WHERE id = ?::uuid", Long.class, id)).isEqualTo(lote);
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacionId)).isEqualTo(estadoPrevio);
        mockMvc.perform(get("/api/productor/evidencias/{id}/archivo", id).cookie(productor))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void rechazaLoteDeOtroProductoYNoPermiteReasignarElCertificado() throws Exception {
        String id = json(subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "Emisor", EN_UN_ANIO)).get("id").asText();
        long otroProducto = productoConLote("Café ajeno", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_ANIO);
        long loteAjeno = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?", Long.class, otroProducto);
        mockMvc.perform(post("/api/productor/productos/{p}/evidencias/{e}/lote", miel, id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId", loteAjeno))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOTE_NO_ENCONTRADO"));
        long lotePropio = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?", Long.class, miel);
        mockMvc.perform(post("/api/productor/productos/{p}/evidencias/{e}/lote", miel, id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId", lotePropio))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/productor/productos/{p}/evidencias/{e}/lote", miel, id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId", lotePropio))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CERTIFICADO_YA_VINCULADO"));
        assertThat(jdbc.queryForObject("SELECT lote_id FROM evidencia_producto WHERE id = ?::uuid", Long.class, id)).isEqualTo(lotePropio);
    }

    @Test
    void soloProductorAutenticadoPuedeSolicitarVinculo() throws Exception {
        String id = json(subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "Emisor", EN_UN_ANIO)).get("id").asText();
        long lote = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?", Long.class, miel);
        mockMvc.perform(post("/api/productor/productos/{p}/evidencias/{e}/lote", miel, id)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId", lote))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/productor/productos/{p}/evidencias/{e}/lote", miel, id).cookie(subAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId", lote))))
                .andExpect(status().isForbidden());
    }

    @Test
    void noVinculaCertificadoSinEmisorOVigenciaCompleta() throws Exception {
        String id = json(subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado incompleto", null, EN_UN_ANIO))
                .get("id").asText();
        long lote = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?", Long.class, miel);
        mockMvc.perform(post("/api/productor/productos/{p}/evidencias/{e}/lote", miel, id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId", lote))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICADO_INCOMPLETO"));
        assertThat(jdbc.queryForObject("SELECT lote_id FROM evidencia_producto WHERE id = ?::uuid", Long.class, id)).isNull();
    }

    // --- CCPIP-36: el productor adjunta evidencias ---



    @Test
    void elProductorAdjuntaUnCertificadoQueIdentificaProductoTipoYVigencia() throws Exception {
        JsonNode evidencia = json(subir(productor, miel, PDF, "certificado.pdf", "CERTIFICADO",
                "Certificado orgánico de miel nativa", "Control Unión", EN_UN_ANIO)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productoId").value(miel))
                .andExpect(jsonPath("$.tipo").value("CERTIFICADO"))
                .andExpect(jsonPath("$.vigenteHasta").value(EN_UN_ANIO.toString()))
                .andExpect(jsonPath("$.vencida").value(false))
                .andExpect(jsonPath("$.formato").value("PDF"))
                .andExpect(jsonPath("$.nombreArchivo").value("certificado.pdf"))
                .andExpect(jsonPath("$.url").value(startsWith("/api/productor/evidencias/"))));

        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", miel).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autorizacion.estado").value("SOLICITADA"))
                .andExpect(jsonPath("$.evidencias.length()").value(1))
                .andExpect(jsonPath("$.puedeAdjuntar").value(true));

        // El archivo es privado: se sirve con sesion, con su tipo real y sin cache compartida (RNF-08)
        byte[] bajado = mockMvc.perform(get(evidencia.get("url").asText()).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(bajado).isEqualTo(PDF);
        mockMvc.perform(get(evidencia.get("url").asText())).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'EVIDENCIA'",
                Integer.class, miel)).isEqualTo(1);
    }

    @Test
    void aceptaImagenesYRechazaLoQueNoEsPdfNiImagenAunqueDigaPdf() throws Exception {
        subir(productor, miel, Imagenes.jpg(40, 40), "chacra.jpg", "EVIDENCIA", "Fotos de la cosecha", null, null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.formato").value("JPG"));
        subir(productor, miel, Imagenes.png(40, 40), "acta.png", "DOCUMENTO", "Acta comunal", null, null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.formato").value("PNG"));

        subir(productor, miel, "MZ ejecutable".getBytes(StandardCharsets.US_ASCII), "certificado.pdf", "DOCUMENTO", "Falso", null, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORMATO_NO_PERMITIDO"));
        subir(productor, miel, Imagenes.WEBP, "foto.webp", "EVIDENCIA", "WebP", null, null)
                .andExpect(status().isUnprocessableEntity());
        // Un certificado siempre dice hasta cuando vale
        subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "Emisor", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIGENCIA_REQUERIDA"));
    }

    @Test
    void elProductorRetiraUnaEvidenciaQueSigueGuardada() throws Exception {
        String id = json(subir(productor, miel, PDF, "a.pdf", "DOCUMENTO", "Acta", null, null)).get("id").asText();

        mockMvc.perform(delete("/api/productor/productos/{p}/evidencias/{e}", miel, id).cookie(productor))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", miel).cookie(productor))
                .andExpect(jsonPath("$.evidencias.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT retirada_en IS NOT NULL FROM evidencia_producto WHERE id = ?::uuid",
                Boolean.class, id)).isTrue();
    }

    @Test
    void unProductorNoVeNiTocaLasEvidenciasDeOtro() throws Exception {
        String url = json(subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "E", EN_UN_ANIO))
                .get("url").asText();
        String id = url.split("/")[4];
        Cookie otro = loginAs(OTRO_PRODUCTOR);

        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", miel).cookie(otro)).andExpect(status().isNotFound());
        subir(otro, miel, PDF, "x.pdf", "DOCUMENTO", "Intruso", null, null).andExpect(status().isNotFound());
        mockMvc.perform(get(url).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/productor/productos/{p}/evidencias/{e}", miel, id).cookie(otro))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {PRODUCTOR, "sudo@ccpip.test", "consumidor@ccpip.test", "admin_acopio@ccpip.test"})
    void laRevisionEsSoloDelSubadministrador(String correo) throws Exception {
        Cookie cookie = loginAs(correo);
        mockMvc.perform(get("/api/admin/autorizaciones").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/autorizaciones/1/aprobar").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", "x")))).andExpect(status().isForbidden());
    }

    @Test
    void sinSesionLaRevisionYLasEvidenciasPidenIngresar() throws Exception {
        mockMvc.perform(get("/api/admin/autorizaciones")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", miel)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", miel).cookie(subAdmin)).andExpect(status().isForbidden());
    }

    // --- CCPIP-174: aprobar ---

    @Test
    void aprobarConMotivoAutorizaElProductoYHabilitaLaCompra() throws Exception {
        LocalDate vence = LocalDate.now(java.time.ZoneId.of("America/Lima")).plusMonths(8);
        subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado orgánico de miel", "Control Unión", EN_UN_ANIO);
        subir(productor, miel, PDF, "rs.pdf", "CERTIFICADO", "Registro sanitario miel", "DIGESA", vence);
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(false));

        long solicitud = solicitud(miel);
        mockMvc.perform(get("/api/admin/autorizaciones").cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitudes[*].id", hasItem((int) solicitud)))
                .andExpect(jsonPath("$.contadores.pendientes").isNumber());
        mockMvc.perform(get("/api/admin/autorizaciones/{id}", solicitud).cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.producto").value("Miel de abeja nativa 250 g"))
                .andExpect(jsonPath("$.evidencias.length()").value(2))
                .andExpect(jsonPath("$.evidencias[0].url").value(startsWith("/api/admin/autorizaciones/" + solicitud)))
                .andExpect(jsonPath("$.certificadosVigentes").value(2))
                .andExpect(jsonPath("$.puedeDecidir").value(true));

        // Sin analisis: la revision manual basta (CCPIP-174)
        decidir(solicitud, "aprobar", "Certificados vigentes y coherentes con el producto")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.vigenteHasta").value(vence.toString()))
                .andExpect(jsonPath("$.puedeDecidir").value(false))
                .andExpect(jsonPath("$.decisiones[0].decision").value("APROBADA"))
                .andExpect(jsonPath("$.decisiones[0].decididoPor").value("Sub-Administrador de Prueba"))
                .andExpect(jsonPath("$.decisiones[0].conAnalisis").value(false));

        // La regla de compra de CCPIP-269 se cumple sin cambios: autorizacion APROBADA y vigente
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(true));
        assertThat(jdbc.queryForObject("SELECT decision_id IS NOT NULL FROM autorizacion_producto WHERE id = ?",
                Boolean.class, solicitud)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'AUTORIZACION'",
                Integer.class, miel)).isEqualTo(1);

        // Las evidencias de una autorizacion vigente sustentan la decision: no se cambian
        subir(productor, miel, PDF, "otro.pdf", "DOCUMENTO", "Otro", null, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUTORIZACION_VIGENTE"));
        // Y una solicitud resuelta no se decide otra vez
        decidir(solicitud, "rechazar", "Cambio de opinion")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLICITUD_YA_RESUELTA"));
    }

    @Test
    void unCertificadoDeOtroProductoNoAutorizaElSolicitado() throws Exception {
        long cafe = productoConLote("Café de altura 250 g", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now(java.time.ZoneId.of("America/Lima")).plusMonths(6));
        jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", cafe);
        subir(productor, cafe, PDF, "c.pdf", "CERTIFICADO", "Certificado orgánico de café", "Control Unión", EN_UN_ANIO);
        subir(productor, miel, Imagenes.jpg(30, 30), "f.jpg", "EVIDENCIA", "Fotos de la cosecha de miel", null, null);
        long solicitudMiel = solicitud(miel);

        decidir(solicitudMiel, "aprobar", "Usa el certificado del café")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIN_CERTIFICADO_VIGENTE"));
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, solicitudMiel))
                .isEqualTo("SOLICITADA");
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(false));

        // Ni siquiera en la base se puede colgar el certificado del cafe de la solicitud de la miel
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE evidencia_producto SET autorizacion_id = ? WHERE producto_id = ?""", solicitudMiel, cafe))
                .hasMessageContaining("evidencia_de_su_autorizacion");
    }

    @Test
    void unCertificadoVencidoNoAutoriza() throws Exception {
        subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "E", LocalDate.now(java.time.ZoneId.of("America/Lima")).minusDays(3))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.vencida").value(true));
        decidir(solicitud(miel), "aprobar", "Todo en orden")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIN_CERTIFICADO_VIGENTE"));
    }

    @Test
    void elMotivoEsObligatorioParaAprobarYRechazar() throws Exception {
        subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "E", EN_UN_ANIO);
        long solicitud = solicitud(miel);

        decidir(solicitud, "aprobar", "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.motivo").value("Escribe el motivo de la decisión"));
        mockMvc.perform(post("/api/admin/autorizaciones/{id}/rechazar", solicitud).cookie(subAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, solicitud))
                .isEqualTo("SOLICITADA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autorizacion_decision WHERE autorizacion_id = ?",
                Integer.class, solicitud)).isZero();
    }

    // --- CCPIP-284: rechazar ---

    @Test
    void rechazarConMotivoLoVeElProductorYPuedeVolverAPostular() throws Exception {
        subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "E", EN_UN_ANIO);
        long solicitud = solicitud(miel);

        decidir(solicitud, "rechazar", "El certificado no corresponde al lote declarado")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.motivo").value("El certificado no corresponde al lote declarado"));
        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", miel).cookie(productor))
                .andExpect(jsonPath("$.autorizacion.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.autorizacion.motivo").value("El certificado no corresponde al lote declarado"))
                .andExpect(jsonPath("$.puedeAdjuntar").value(true));
        mockMvc.perform(get("/api/admin/autorizaciones?estado=RECHAZADA").cookie(subAdmin))
                .andExpect(jsonPath("$.solicitudes[*].id", hasItem((int) solicitud)));

        // Una evidencia nueva vuelve a poner la solicitud en revision; la decision anterior se conserva
        subir(productor, miel, PDF, "c2.pdf", "CERTIFICADO", "Certificado de miel 2026", "E", EN_UN_ANIO)
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/admin/autorizaciones/{id}", solicitud).cookie(subAdmin))
                .andExpect(jsonPath("$.estado").value("SOLICITADA"))
                .andExpect(jsonPath("$.motivo").doesNotExist())
                .andExpect(jsonPath("$.decisiones[0].decision").value("RECHAZADA"));
    }

    @Test
    void lasDecisionesNoSeEditanNiSeBorran() throws Exception {
        subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "E", EN_UN_ANIO);
        long solicitud = solicitud(miel);
        decidir(solicitud, "rechazar", "Ilegible").andExpect(status().isOk());

        assertThatThrownBy(() -> jdbc.update("UPDATE autorizacion_decision SET motivo = 'otro' WHERE autorizacion_id = ?", solicitud))
                .hasMessageContaining("solo insercion");
        // El borrado usa el mismo disparador; tras el error la transaccion de la prueba ya no admite otra sentencia
    }

    // --- CCPIP-50: analisis preliminar ---

    @Test
    void elAnalisisSimuladoDetectaFaltantesEInconsistenciasSinDecidir() throws Exception {
        String bueno = json(subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado orgánico de miel", "Control Unión", EN_UN_ANIO))
                .get("id").asText();
        String ajeno = json(subir(productor, miel, PDF, "x.pdf", "CERTIFICADO", "Certificado de cacao", "Control Unión", EN_UN_ANIO))
                .get("id").asText();
        long solicitud = solicitud(miel);

        mockMvc.perform(post("/api/admin/autorizaciones/{id}/analisis", solicitud).cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COMPLETADO"))
                .andExpect(jsonPath("$.simulado").value(true))
                .andExpect(jsonPath("$.proveedor").value("simulado"))
                .andExpect(jsonPath("$.recomendacion").value("REVISAR"))
                .andExpect(jsonPath("$.resumen").value(startsWith("Resultado de prueba")))
                .andExpect(jsonPath("$.desactualizado").value(false))
                .andExpect(jsonPath("$.hallazgos[?(@.evidenciaId == '%s')].tipo".formatted(bueno)).value("CONFORME"))
                .andExpect(jsonPath("$.hallazgos[?(@.evidenciaId == '%s')].tipo".formatted(ajeno)).value("INCONSISTENCIA"))
                .andExpect(jsonPath("$.hallazgos[?(@.tipo == 'FALTANTE')].detalle")
                        .value("Faltan fotos o registros del proceso de elaboración."));

        // La IA no decide: la solicitud sigue pendiente hasta la decision humana
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, solicitud))
                .isEqualTo("SOLICITADA");
        // El resultado queda ligado a la solicitud y a cada certificado
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM analisis_hallazgo h JOIN analisis_evidencias a ON a.id = h.analisis_id
                WHERE a.autorizacion_id = ? AND h.evidencia_id = ?::uuid""", Integer.class, solicitud, ajeno)).isEqualTo(1);

        // Una evidencia nueva deja el analisis desactualizado
        subir(productor, miel, Imagenes.jpg(20, 20), "f.jpg", "EVIDENCIA", "Fotos", null, null);
        mockMvc.perform(get("/api/admin/autorizaciones/{id}", solicitud).cookie(subAdmin))
                .andExpect(jsonPath("$.analisis.desactualizado").value(true));

        decidir(solicitud, "aprobar", "Revisado: el certificado de cacao se ignora, el de miel es valido")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisiones[0].conAnalisis").value(true));
    }

    @Test
    void sinServicioDeAnalisisSeDerivaARevisionManualYSePuedeDecidir() throws Exception {
        subir(productor, miel, PDF, "c.pdf", "CERTIFICADO", "Certificado de miel", "E", EN_UN_ANIO);
        long solicitud = solicitud(miel);
        RevisionService objetivo = AopTestUtils.getTargetObject(revision);
        AnalistaEvidencias original = (AnalistaEvidencias) ReflectionTestUtils.getField(objetivo, "analista");
        ReflectionTestUtils.setField(objetivo, "analista", new AnalistaNoConfigurado());
        try {
            mockMvc.perform(post("/api/admin/autorizaciones/{id}/analisis", solicitud).cookie(subAdmin))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("IA_NO_DISPONIBLE"))
                    .andExpect(jsonPath("$.error").value(startsWith("El análisis asistido no está disponible")));
        } finally {
            ReflectionTestUtils.setField(objetivo, "analista", original);
        }

        mockMvc.perform(get("/api/admin/autorizaciones/{id}", solicitud).cookie(subAdmin))
                .andExpect(jsonPath("$.analisis.estado").value("NO_DISPONIBLE"))
                .andExpect(jsonPath("$.puedeDecidir").value(true));
        decidir(solicitud, "aprobar", "Revision manual de los certificados")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.decisiones[0].conAnalisis").value(false));
    }

    // --- Apoyo ---

    private ResultActions subir(Cookie cookie, long producto, byte[] contenido, String nombre, String tipo, String titulo,
                                String emisor, LocalDate vigenteHasta) throws Exception {
        var peticion = multipart("/api/productor/productos/{id}/evidencias", producto)
                .file(new MockMultipartFile("archivo", nombre, "application/octet-stream", contenido))
                .param("tipo", tipo).param("titulo", titulo);
        if (emisor != null) {
            peticion.param("emisor", emisor);
        }
        if (vigenteHasta != null) {
            if (!vigenteHasta.isBefore(LocalDate.now(java.time.ZoneId.of("America/Lima")))) {
                peticion.param("vigenteDesde", LocalDate.now(java.time.ZoneId.of("America/Lima")).minusDays(1).toString());
            }
            peticion.param("vigenteHasta", vigenteHasta.toString());
        }
        return mockMvc.perform(peticion.cookie(cookie));
    }

    private ResultActions decidir(long solicitud, String accion, String motivo) throws Exception {
        return mockMvc.perform(post("/api/admin/autorizaciones/{id}/{accion}", solicitud, accion).cookie(subAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", motivo))));
    }

    private long solicitud(long producto) {
        return jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, producto);
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }
}
