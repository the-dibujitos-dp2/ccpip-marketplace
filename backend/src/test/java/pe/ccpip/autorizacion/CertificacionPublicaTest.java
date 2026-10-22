package pe.ccpip.autorizacion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-365 (RF-255): el visitante consulta las evidencias publicas de certificacion de un producto:
 * emisor, fecha y vigencia, sin documentos privados y sin afirmar una aprobacion que no tiene evidencia.
 */
@Transactional
class CertificacionPublicaTest extends IntegrationTest {

    private long producto;
    private long autorizacion;

    /** Una oferta visible del catalogo con su autorizacion aprobada (de la semilla, sin evidencias). */
    @BeforeEach
    void ofertaVisible() {
        producto = productoConLote("Cafe certificado " + UUID.randomUUID(), "PUBLICADO", null, null);
        autorizacion = jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, producto);
    }

    @Test
    void unProductoVerificadoMuestraEmisorFechaYVigenciaSinDocumentosPrivados() throws Exception {
        UUID certificado = evidencia("CERTIFICADO", "Certificado orgánico", "SENASA", "current_date - 30", "current_date + 200");
        UUID foto = evidencia("EVIDENCIA", "Foto de la parcela", null, null, null);
        UUID acta = evidencia("DOCUMENTO", "Acta de la comunidad", "Comunidad", null, null);
        aprobar(List.of(certificado, foto, acta), "current_date + 200");
        // Subida despues de la aprobacion: no la sustento, no se publica
        evidencia("CERTIFICADO", "Certificado nuevo sin revisar", "Otro", "current_date", "current_date + 400");

        mockMvc.perform(get("/api/productos/" + producto + "/certificacion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("VERIFICADA"))
                .andExpect(jsonPath("$.verificadaEn").isNotEmpty())
                .andExpect(jsonPath("$.evidencias.length()").value(2))
                .andExpect(jsonPath("$.evidencias[0].tipo").value("CERTIFICADO"))
                .andExpect(jsonPath("$.evidencias[0].titulo").value("Certificado orgánico"))
                .andExpect(jsonPath("$.evidencias[0].emisor").value("SENASA"))
                .andExpect(jsonPath("$.evidencias[0].vigenteHasta").isString())
                .andExpect(jsonPath("$.evidencias[0].vigente").value(true))
                .andExpect(jsonPath("$.evidencias[1].tipo").value("EVIDENCIA"))
                // Nada del archivo ni del documento privado
                .andExpect(jsonPath("$..id").doesNotExist())
                .andExpect(jsonPath("$..clave").doesNotExist())
                .andExpect(jsonPath("$..nombreArchivo").doesNotExist())
                .andExpect(jsonPath("$..archivo").doesNotExist())
                .andExpect(jsonPath("$.evidencias[?(@.titulo == 'Acta de la comunidad')]").isEmpty())
                .andExpect(jsonPath("$.evidencias[?(@.titulo == 'Certificado nuevo sin revisar')]").isEmpty());
    }

    @Test
    void conElCertificadoVencidoNoSeDiceVerificado() throws Exception {
        UUID certificado = evidencia("CERTIFICADO", "Certificado orgánico", "SENASA", "current_date - 400", "current_date - 1");
        aprobar(List.of(certificado), "current_date - 1");

        mockMvc.perform(get("/api/productos/" + producto + "/certificacion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("VENCIDA"))
                .andExpect(jsonPath("$.evidencias[0].vigente").value(false));
    }

    @Test
    void unaAprobacionSinEvidenciasPublicasNoSeAfirma() throws Exception {
        // La semilla aprueba sin decision ni evidencias
        mockMvc.perform(get("/api/productos/" + producto + "/certificacion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("SIN_EVIDENCIA"))
                .andExpect(jsonPath("$.verificadaEn").doesNotExist())
                .andExpect(jsonPath("$.evidencias").isEmpty());

        // Aprobada solo con una foto y un documento: tampoco, porque no hay certificado
        aprobar(List.of(evidencia("EVIDENCIA", "Foto", null, null, null), evidencia("DOCUMENTO", "Acta", null, null, null)),
                "current_date + 30");
        mockMvc.perform(get("/api/productos/" + producto + "/certificacion"))
                .andExpect(jsonPath("$.estado").value("SIN_EVIDENCIA"))
                .andExpect(jsonPath("$.evidencias").isEmpty());
    }

    @Test
    void unaAutorizacionRevocadaNoMuestraEvidencias() throws Exception {
        UUID certificado = evidencia("CERTIFICADO", "Certificado orgánico", "SENASA", "current_date - 30", "current_date + 200");
        aprobar(List.of(certificado), "current_date + 200");
        jdbc.update("UPDATE autorizacion_producto SET estado = 'REVOCADA' WHERE id = ?", autorizacion);

        mockMvc.perform(get("/api/productos/" + producto + "/certificacion"))
                .andExpect(jsonPath("$.estado").value("SIN_EVIDENCIA"))
                .andExpect(jsonPath("$.evidencias").isEmpty());
    }

    @Test
    void soloDeProductosVisiblesDelCatalogo() throws Exception {
        long borrador = productoConLote("Borrador " + UUID.randomUUID(), "BORRADOR", null, null);
        mockMvc.perform(get("/api/productos/" + borrador + "/certificacion"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        mockMvc.perform(get("/api/productos/999999999/certificacion")).andExpect(status().isNotFound());
    }

    @Test
    void esPublicaYConSesionDaLoMismo() throws Exception {
        mockMvc.perform(get("/api/productos/" + producto + "/certificacion").cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productoId").value(producto));
    }

    // --- Ayudas ---

    /** Una evidencia del productor del producto. Las fechas son expresiones SQL o nulas. */
    private UUID evidencia(String tipo, String titulo, String emisor, String desde, String hasta) {
        return jdbc.queryForObject("""
                INSERT INTO evidencia_producto (autorizacion_id, productor_id, producto_id, tipo, titulo, emisor,
                                                vigente_desde, vigente_hasta, nombre_archivo, content_type, bytes, clave,
                                                subida_por)
                SELECT a.id, a.productor_id, a.producto_id, ?, ?, ?, %s, %s, 'privado.pdf', 'application/pdf', 10,
                       'evidencias/' || gen_random_uuid(), (SELECT usuario_id FROM productor WHERE id = a.productor_id)
                FROM autorizacion_producto a WHERE a.id = ?
                RETURNING id""".formatted(desde == null ? "NULL::date" : desde, hasta == null ? "NULL::date" : hasta),
                UUID.class, tipo, titulo, emisor, autorizacion);
    }

    /** La decision APROBADA del sub administrador que cita {@code evidencias}, como la deja S-02. */
    private void aprobar(List<UUID> evidencias, String vigenteHasta) {
        Long decision = jdbc.queryForObject("""
                INSERT INTO autorizacion_decision (autorizacion_id, decision, motivo, evidencias, vigente_hasta, decidido_por)
                SELECT ?, 'APROBADA', 'Cumple las reglas de origen', ?::uuid[], %s, id FROM usuario WHERE correo = 'sub_admin@ccpip.test'
                RETURNING id""".formatted(vigenteHasta), Long.class, autorizacion,
                "{" + String.join(",", evidencias.stream().map(UUID::toString).toList()) + "}");
        jdbc.update("UPDATE autorizacion_producto SET estado = 'APROBADA', decision_id = ?, vigente_hasta = %s WHERE id = ?"
                .formatted(vigenteHasta), decision, autorizacion);
    }
}
