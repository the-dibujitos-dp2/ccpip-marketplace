package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/** Adversarial characterization del contrato RF-226, separada de las pruebas de revisión históricas. */
@Transactional
class CertificadoLoteAdversarialTest extends IntegrationTest {
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDate HOY = LocalDate.now(java.time.ZoneId.of("America/Lima"));
    @Autowired private JdbcTemplate jdbc;
    private Cookie productor, otro, subAdmin;
    private long producto, lote;
    private String ruta;

    @BeforeEach void preparar() throws Exception {
        productor=loginAs("productor@ccpip.test"); otro=loginAs("productor2@ccpip.test"); subAdmin=loginAs("sub_admin@ccpip.test");
        producto=productoConLote("QA336-"+System.nanoTime(),"PUBLICADO","PUNTO_ACOPIO",HOY.plusMonths(5));
        jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?",producto);
        lote=jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?",Long.class,producto);
        ruta="/api/productor/productos/{p}/evidencias/{e}/lote";
    }

    @Test void metadatosObligatoriosYTipoDocumentoRechazadosSinEscriturasParciales() throws Exception {
        String noEmisor=subir(productor,producto,"CERTIFICADO","Emisor temporal",HOY.minusDays(1),HOY.plusDays(5));
        String sinInicio=subir(productor,producto,"CERTIFICADO","Emisor",HOY.minusDays(1),HOY.plusDays(5));
        jdbc.update("UPDATE evidencia_producto SET emisor=NULL WHERE id=?::uuid",noEmisor);
        jdbc.update("UPDATE evidencia_producto SET vigente_desde=NULL WHERE id=?::uuid",sinInicio);
        // La restricción CHECK de la BD impide fabricar CERTIFICADO sin vigente_hasta: se conserva evidencia del setup fallido en el log, no se cuenta como RED.
        String documento=subir(productor,producto,"DOCUMENTO",null,null,null);
        for (String id: new String[]{noEmisor,sinInicio}) {
            var r=mockMvc.perform(post(ruta,producto,id).cookie(productor).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId",lote))))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CERTIFICADO_INCOMPLETO")).andReturn();
            assertThat(r.getResponse().getContentAsString()).contains("code");
        }
        mockMvc.perform(post(ruta,producto,documento).cookie(productor).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId",lote))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_ES_CERTIFICADO"));
        assertThat(jdbc.queryForList("SELECT lote_id FROM evidencia_producto WHERE id IN (?::uuid,?::uuid,?::uuid)",Object.class,noEmisor,sinInicio,documento)).containsOnly((Object)null);
    }

    @Test void rechazaLotesInexistentesAjenoAOtroProductorYCertificadosDeOtraPropiedad() throws Exception {
        String own=subir(productor,producto,"CERTIFICADO","Emisor",HOY.minusDays(1),HOY.plusDays(5));
        long otroProducto=productoConLote("QA336-OTRO-"+System.nanoTime(),"PUBLICADO","PUNTO_ACOPIO",HOY.plusMonths(5),"productor2@ccpip.test");
        jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", otroProducto);
        long loteAjeno=jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?",Long.class,otroProducto);
        for (long bad:new long[]{loteAjeno,Long.MAX_VALUE}) {
            mockMvc.perform(post(ruta,producto,own).cookie(productor).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId",bad))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOTE_NO_ENCONTRADO"));
        }
        mockMvc.perform(post(ruta,producto,own).cookie(otro).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId",lote))))
                .andExpect(status().isNotFound());
        String foreign=subir(otro,otroProducto,"CERTIFICADO","Emisor",HOY.minusDays(1),HOY.plusDays(5));
        mockMvc.perform(post(ruta,producto,foreign).cookie(productor).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId",lote))))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForList("SELECT lote_id FROM evidencia_producto WHERE id IN (?::uuid,?::uuid)",Object.class,own,foreign)).hasSize(2).containsOnly((Object)null);
    }

    @Test void dtoInvalidoTieneRespuestaEstructuradaYNoModificaElNegocio() throws Exception {
        String id=subir(productor,producto,"CERTIFICADO","Emisor",HOY.minusDays(1),HOY.plusDays(5));
        for(String body:new String[]{"{}", "{\"loteId\":0}", "{\"loteId\":-1}", "{\"loteId\":\"x\"}"}) {
            MvcResult r=mockMvc.perform(post(ruta,producto,id).cookie(productor).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            assertThat(r.getResponse().getStatus()).isBetween(400,499);
            assertThat(r.getResponse().getContentType()).contains("json");
            assertThat(r.getResponse().getContentAsString()).contains("code");
            assertThat(jdbc.queryForObject("SELECT lote_id FROM evidencia_producto WHERE id=?::uuid",Long.class,id)).isNull();
        }
    }

    @Test void permiteDosCertificadosParaUnLoteYLecturaPersistidaExactaSinCambiarRevisionNiArchivo() throws Exception {
        String a=subir(productor,producto,"CERTIFICADO","Emisor A",HOY.minusDays(1),HOY.plusDays(5));
        String b=subir(productor,producto,"CERTIFICADO","Emisor B",HOY.minusDays(1),HOY.plusDays(5));
        String clave=jdbc.queryForObject("SELECT clave FROM evidencia_producto WHERE id=?::uuid",String.class,a);
        String estado=jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE producto_id=?",String.class,producto);
        for(String id:new String[]{a,b}) {
            mockMvc.perform(post(ruta,producto,id).cookie(productor).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId",lote)))).andExpect(status().isOk());
            mockMvc.perform(get(ruta,producto,id).cookie(productor)).andExpect(status().isOk()).andExpect(jsonPath("$.evidenciaId").value(id)).andExpect(jsonPath("$.productoId").value(producto)).andExpect(jsonPath("$.loteId").value(lote));
            mockMvc.perform(get("/api/productor/evidencias/{id}/archivo",id).cookie(productor)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        }
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE producto_id=?",String.class,producto)).isEqualTo(estado);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidencia_producto WHERE lote_id=?",Integer.class,lote)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT clave FROM evidencia_producto WHERE id=?::uuid",String.class,a)).isEqualTo(clave);
    }

    @Test void propietarioRolesYSesionSeAplicanEnLecturaYEscritura() throws Exception {
        String id=subir(productor,producto,"CERTIFICADO","Emisor",HOY.minusDays(1),HOY.plusDays(5));
        var body=json(Map.of("loteId",lote));
        mockMvc.perform(post(ruta,producto,id).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(ruta,producto,id).cookie(subAdmin).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(get(ruta,producto,id)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(ruta,producto,id).cookie(subAdmin)).andExpect(status().isForbidden());
        mockMvc.perform(get(ruta,producto,id).cookie(otro)).andExpect(status().isNotFound());
    }

    private String subir(Cookie cookie,long p,String tipo,String emisor,LocalDate desde,LocalDate hasta) throws Exception {
        var req= multipart("/api/productor/productos/{id}/evidencias",p).file(new MockMultipartFile("archivo","cert.pdf","application/octet-stream",PDF)).param("tipo",tipo).param("titulo","QA certificado");
        if(emisor!=null) req.param("emisor",emisor); if(desde!=null) req.param("vigenteDesde",desde.toString()); if(hasta!=null) req.param("vigenteHasta",hasta.toString());
        JsonNode node=objectMapper.readTree(mockMvc.perform(req.cookie(cookie)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
