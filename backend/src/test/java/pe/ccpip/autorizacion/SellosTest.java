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
 * CCPIP-38 (RF-15): el visitante ve el origen verificado y los sellos de un producto, cada uno con su
 * respaldo. Sin respaldo no hay sello: nunca se atribuyen reglas que nadie acredito.
 */
@Transactional
class SellosTest extends IntegrationTest {

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    private long producto;
    private long autorizacion;
    private long productor;

    @BeforeEach
    void ofertaVisible() {
        producto = productoConLote("Cacao con sellos " + UUID.randomUUID(), "PUBLICADO", null, null);
        autorizacion = jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, producto);
        productor = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, producto);
    }

    @Test
    void elOrigenVerificadoPorLaCamaraEsUnSelloConSuComunidadYFecha() throws Exception {
        jdbc.update("UPDATE productor SET origen_verificado = true, verificado_en = DATE '2026-06-15' WHERE id = ?", productor);
        String comunidad = jdbc.queryForObject("SELECT comunidad FROM productor WHERE id = ?", String.class, productor);

        mockMvc.perform(get("/api/productos/" + producto + "/sellos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productoId").value(producto))
                .andExpect(jsonPath("$.sellos.length()").value(1))
                .andExpect(jsonPath("$.sellos[0].tipo").value("ORIGEN_VERIFICADO"))
                .andExpect(jsonPath("$.sellos[0].titulo").value(comunidad))
                .andExpect(jsonPath("$.sellos[0].desde").value("2026-06-15"));
    }

    @Test
    void sinVerificacionNiCertificadosNoHaySellos() throws Exception {
        jdbc.update("UPDATE productor SET origen_verificado = false WHERE id = ?", productor);

        mockMvc.perform(get("/api/productos/" + producto + "/sellos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellos").isEmpty());
    }

    @Test
    void cadaCertificadoVigenteQueSustentoLaAprobacionEsUnSello() throws Exception {
        jdbc.update("UPDATE productor SET origen_verificado = false WHERE id = ?", productor);
        UUID organico = evidencia("CERTIFICADO", "Certificado orgánico", "SENASA", HOY + " - 30", HOY + " + 200");
        UUID foto = evidencia("EVIDENCIA", "Foto de la parcela", null, null, null);
        aprobar(List.of(organico, foto), HOY + " + 200");

        mockMvc.perform(get("/api/productos/" + producto + "/sellos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellos.length()").value(1))
                .andExpect(jsonPath("$.sellos[0].tipo").value("CERTIFICADO"))
                .andExpect(jsonPath("$.sellos[0].titulo").value("Certificado orgánico"))
                .andExpect(jsonPath("$.sellos[0].emisor").value("SENASA"))
                .andExpect(jsonPath("$.sellos[0].hasta").isString())
                // Ni la foto ni nada del archivo
                .andExpect(jsonPath("$.sellos[?(@.titulo == 'Foto de la parcela')]").isEmpty())
                .andExpect(jsonPath("$..clave").doesNotExist())
                .andExpect(jsonPath("$..nombreArchivo").doesNotExist());
    }

    @Test
    void unCertificadoVencidoONoRevisadoNoDaSello() throws Exception {
        jdbc.update("UPDATE productor SET origen_verificado = false WHERE id = ?", productor);
        UUID vencido = evidencia("CERTIFICADO", "Certificado vencido", "SENASA", HOY + " - 400", HOY + " - 1");
        aprobar(List.of(vencido), HOY + " + 30");
        // Subido despues de la aprobacion: nadie lo reviso
        evidencia("CERTIFICADO", "Certificado sin revisar", "Otro", HOY, HOY + " + 400");

        mockMvc.perform(get("/api/productos/" + producto + "/sellos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellos").isEmpty());
    }

    @Test
    void unaAutorizacionRevocadaPierdeSusSellosDeCertificado() throws Exception {
        jdbc.update("UPDATE productor SET origen_verificado = true WHERE id = ?", productor);
        aprobar(List.of(evidencia("CERTIFICADO", "Certificado orgánico", "SENASA", HOY + " - 30", HOY + " + 200")),
                HOY + " + 200");
        jdbc.update("UPDATE autorizacion_producto SET estado = 'REVOCADA' WHERE id = ?", autorizacion);

        mockMvc.perform(get("/api/productos/" + producto + "/sellos"))
                .andExpect(jsonPath("$.sellos.length()").value(1))
                .andExpect(jsonPath("$.sellos[0].tipo").value("ORIGEN_VERIFICADO"));
    }

    @Test
    void soloDeProductosVisiblesDelCatalogo() throws Exception {
        long borrador = productoConLote("Borrador " + UUID.randomUUID(), "BORRADOR", null, null);
        mockMvc.perform(get("/api/productos/" + borrador + "/sellos"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        mockMvc.perform(get("/api/productos/999999999/sellos")).andExpect(status().isNotFound());
    }

    @Test
    void esPublicaYConSesionDaLoMismo() throws Exception {
        mockMvc.perform(get("/api/productos/" + producto + "/sellos").cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productoId").value(producto));
    }

    // --- Ayudas (las mismas de CertificacionPublicaTest) ---

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
