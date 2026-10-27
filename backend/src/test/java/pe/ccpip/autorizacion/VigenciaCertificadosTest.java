package pe.ccpip.autorizacion;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-184 (RF-149): el subadministrador consulta la vigencia de los certificados de cada producto
 * autorizado: vencidos, por vencer en el horizonte que elige y vigentes. Solo consulta: no cambia la
 * autorizacion ni la venta. Transaccional: lo que arma la prueba no llega a las demas.
 */
@Transactional
class VigenciaCertificadosTest extends IntegrationTest {

    private static final String CERTIFICADOS = "/api/admin/certificados";
    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));

    private Cookie subAdmin;
    private UUID vencido;
    private UUID en10Dias;

    @BeforeEach
    void preparar() throws Exception {
        subAdmin = loginAs("sub_admin@ccpip.test");
        // Solo cuentan los certificados de la prueba
        jdbc.update("UPDATE evidencia_producto SET retirada_en = now(), retirada_por = subida_por WHERE retirada_en IS NULL");
        long miel = productoConLote("VC Miel de abeja nativa", "PUBLICADO", "PUNTO_ACOPIO", HOY.plusMonths(6));
        long aceite = productoConLote("VC Aceite de sacha inchi", "PUBLICADO", "PUNTO_ACOPIO", HOY.plusMonths(6),
                "productor2@ccpip.test");
        vencido = certificado(miel, "CERTIFICADO", "Orgánico vencido", HOY.minusDays(1));
        en10Dias = certificado(miel, "CERTIFICADO", "Registro sanitario", HOY.plusDays(10));
        certificado(aceite, "CERTIFICADO", "Comercio justo", HOY.plusDays(45));
        certificado(aceite, "CERTIFICADO", "Denominación de origen", HOY.plusDays(200));
        // No entran: un documento, un certificado retirado y uno de una autorizacion que no esta aprobada
        certificado(miel, "DOCUMENTO", "Acta de la asamblea", HOY.plusDays(5));
        UUID retirado = certificado(miel, "CERTIFICADO", "Certificado retirado", HOY.plusDays(3));
        jdbc.update("UPDATE evidencia_producto SET retirada_en = now(), retirada_por = subida_por WHERE id = ?", retirado);
        long enRevision = productoConLote("VC Camu camu en revision", "PUBLICADO", "PUNTO_ACOPIO", HOY.plusMonths(6));
        jdbc.update("UPDATE autorizacion_producto SET estado = 'SOLICITADA', decidido_en = NULL WHERE producto_id = ?", enRevision);
        certificado(enRevision, "CERTIFICADO", "En revisión", HOY.plusDays(2));
        // La renovacion del registro sanitario ya se pidio (CCPIP-321)
        jdbc.update("""
                INSERT INTO renovacion_certificado (autorizacion_id, productor_id, producto_id, evidencia_id, motivo, plazo, solicitada_por)
                SELECT e.autorizacion_id, e.productor_id, e.producto_id, e.id, 'Vence pronto', ?, (SELECT id FROM usuario WHERE correo = 'sub_admin@ccpip.test')
                FROM evidencia_producto e WHERE e.id = ?""", HOY.plusDays(10), en10Dias);
    }

    @Test
    void porDefectoMuestraLoQueVenceEnLos30DiasConSuProductoProductorYRenovacion() throws Exception {
        mockMvc.perform(get(CERTIFICADOS).cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias").value(30))
                .andExpect(jsonPath("$.estado").value("POR_VENCER"))
                .andExpect(jsonPath("$.contadores.vencidos").value(1))
                .andExpect(jsonPath("$.contadores.porVencer").value(1))
                .andExpect(jsonPath("$.contadores.vigentes").value(2))
                .andExpect(jsonPath("$.certificados.length()").value(1))
                .andExpect(jsonPath("$.certificados[0].evidenciaId").value(en10Dias.toString()))
                .andExpect(jsonPath("$.certificados[0].producto").value("VC Miel de abeja nativa"))
                .andExpect(jsonPath("$.certificados[0].organizacion").exists())
                .andExpect(jsonPath("$.certificados[0].autorizacionId").exists())
                .andExpect(jsonPath("$.certificados[0].diasRestantes").value(10))
                .andExpect(jsonPath("$.certificados[0].renovacionPlazo").value(HOY.plusDays(10).toString()));
    }

    @Test
    void elHorizonteCambiaLoQueEstaPorVencerYLosVencidosSeListanAparte() throws Exception {
        mockMvc.perform(get(CERTIFICADOS).param("dias", "60").cookie(subAdmin))
                .andExpect(jsonPath("$.contadores.porVencer").value(2))
                .andExpect(jsonPath("$.contadores.vigentes").value(1))
                // Primero el que vence antes
                .andExpect(jsonPath("$.certificados[0].titulo").value("Registro sanitario"))
                .andExpect(jsonPath("$.certificados[1].titulo").value("Comercio justo"));
        mockMvc.perform(get(CERTIFICADOS).param("estado", "VENCIDO").cookie(subAdmin))
                .andExpect(jsonPath("$.certificados.length()").value(1))
                .andExpect(jsonPath("$.certificados[0].evidenciaId").value(vencido.toString()))
                .andExpect(jsonPath("$.certificados[0].diasRestantes").value(-1))
                .andExpect(jsonPath("$.certificados[0].renovacionPlazo").doesNotExist());
        mockMvc.perform(get(CERTIFICADOS).param("estado", "VIGENTE").param("dias", "90").cookie(subAdmin))
                .andExpect(jsonPath("$.certificados[*].titulo").value(hasItem("Denominación de origen")))
                .andExpect(jsonPath("$.certificados[*].titulo").value(not(hasItem("Acta de la asamblea"))))
                .andExpect(jsonPath("$.certificados[*].titulo").value(not(hasItem("Certificado retirado"))))
                .andExpect(jsonPath("$.certificados[*].titulo").value(not(hasItem("En revisión"))));
    }

    @Test
    void soloConsultaYNoCambiaLaAutorizacion() throws Exception {
        Integer antes = jdbc.queryForObject("SELECT count(*) FROM autorizacion_producto WHERE estado = 'APROBADA'", Integer.class);
        mockMvc.perform(get(CERTIFICADOS).param("estado", "VENCIDO").cookie(subAdmin)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM autorizacion_producto WHERE estado = 'APROBADA'", Integer.class))
                .isEqualTo(antes);
    }

    @Test
    void rechazaUnHorizonteOEstadoDesconocidoYOtrosRoles() throws Exception {
        mockMvc.perform(get(CERTIFICADOS).param("dias", "45").cookie(subAdmin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("HORIZONTE_INVALIDO"));
        mockMvc.perform(get(CERTIFICADOS).param("estado", "TODOS").cookie(subAdmin)).andExpect(status().isBadRequest());
        mockMvc.perform(get(CERTIFICADOS).cookie(loginAs("productor@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get(CERTIFICADOS)).andExpect(status().isUnauthorized());
    }

    /** Una evidencia de la autorizacion del producto, sin archivo real (la consulta no lo lee). */
    private UUID certificado(long productoId, String tipo, String titulo, LocalDate vigenteHasta) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evidencia_producto (id, autorizacion_id, productor_id, producto_id, tipo, titulo, emisor,
                                                vigente_hasta, nombre_archivo, content_type, bytes, clave, subida_por)
                SELECT ?, a.id, a.productor_id, a.producto_id, ?, ?, 'SENASA', ?, 'c.pdf', 'application/pdf', 10, ?,
                       (SELECT id FROM usuario WHERE correo = 'productor@ccpip.test')
                FROM autorizacion_producto a WHERE a.producto_id = ?""",
                id, tipo, titulo, vigenteHasta, "pruebas/" + id, productoId);
        return id;
    }
}
