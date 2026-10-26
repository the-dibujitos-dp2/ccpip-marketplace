package pe.ccpip.moderacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Moderacion de la publicacion de ofertas. CCPIP-46 (RF-21): publicar pide la revision del
 * subadministrador, que consulta la oferta pendiente con su autorizacion y sus evidencias. Transaccional:
 * las solicitudes que arma la prueba no llegan a las demas.
 */
@Transactional
class ModeracionTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String SUB_ADMIN = "sub_admin@ccpip.test";
    private static final LocalDate EN_UN_ANIO = LocalDate.now(ZoneId.of("America/Lima")).plusYears(1);

    private Cookie productor;
    private Cookie subAdmin;
    private long oferta;

    /** Una oferta autorizada, con stock en Lima, que el productor saco del catalogo. */
    @BeforeEach
    void preparar() throws Exception {
        productor = loginAs(PRODUCTOR);
        subAdmin = loginAs(SUB_ADMIN);
        oferta = productoConLote("Miel de abeja melipona MOD", "RETIRADO", "PUNTO_ACOPIO", EN_UN_ANIO);
    }

    // ---------- CCPIP-46: publicar pide la revision ----------

    @Test
    void publicarDejaLaOfertaEnRevisionYFueraDelCatalogo() throws Exception {
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", oferta).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_MODERACION"))
                .andExpect(jsonPath("$.moderacion.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.moderacion.motivo").doesNotExist());
        mockMvc.perform(get("/api/productos/{id}", oferta)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/productos").param("estado", "EN_MODERACION").cookie(productor))
                .andExpect(jsonPath("$.items[*].id").value(hasItem((int) oferta)))
                .andExpect(jsonPath("$.conteo.EN_MODERACION").value(greaterThanOrEqualTo(1)));

        // Pedirlo otra vez no abre otra solicitud
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", oferta).cookie(productor))
                .andExpect(jsonPath("$.estado").value("EN_MODERACION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderacion_publicacion WHERE producto_id = ?",
                Integer.class, oferta)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT estado_anterior FROM moderacion_publicacion WHERE producto_id = ?",
                String.class, oferta)).isEqualTo("RETIRADO");
        assertThat(jdbc.queryForList("SELECT accion FROM producto_auditoria WHERE producto_id = ? ORDER BY id",
                String.class, oferta)).containsExactly("MODERACION");
    }

    @Test
    void unaOfertaEnRevisionSeEditaPeroNoQuedaIncompleta() throws Exception {
        publicar();
        mockMvc.perform(post("/api/productor/productos/{id}/retirar", oferta).cookie(productor))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOLO_PUBLICADOS"));
        mockMvc.perform(put("/api/productor/productos/{id}", oferta)
                        .cookie(productor).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Miel de abeja melipona MOD", "lineas", List.of(),
                                "modalidad", "MINORISTA", "precio", 20))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCTO_INCOMPLETO"));
    }

    // ---------- CCPIP-46: la bandeja y el expediente ----------

    @Test
    void elSubadministradorVeLaOfertaPendienteConSuAutorizacion() throws Exception {
        long solicitud = publicar();

        mockMvc.perform(get("/api/admin/moderacion").cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contadores.pendientes").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].producto".formatted(solicitud)).value("Miel de abeja melipona MOD"))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].estado".formatted(solicitud)).value("PENDIENTE"))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].republicacion".formatted(solicitud)).value(true))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].autorizacion".formatted(solicitud)).value("APROBADA"))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].autorizada".formatted(solicitud)).value(true))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].linea".formatted(solicitud)).value("Superfrutos"));
        // Una solicitud pendiente no aparece entre las decididas
        mockMvc.perform(get("/api/admin/moderacion").param("estado", "APROBADA").cookie(subAdmin))
                .andExpect(jsonPath("$.solicitudes[*].id").value(not(hasItem((int) solicitud))));

        mockMvc.perform(get("/api/admin/moderacion/{id}", solicitud).cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.oferta.id").value((int) oferta))
                .andExpect(jsonPath("$.oferta.nombre").value("Miel de abeja melipona MOD"))
                .andExpect(jsonPath("$.oferta.lineas[0]").value("Superfrutos"))
                .andExpect(jsonPath("$.oferta.precio").value(20.00))
                .andExpect(jsonPath("$.oferta.enVenta").value(5))
                .andExpect(jsonPath("$.productor.organizacion").isString())
                .andExpect(jsonPath("$.autorizacion.estado").value("APROBADA"))
                .andExpect(jsonPath("$.autorizacion.vigente").value(true))
                .andExpect(jsonPath("$.autorizacion.evidencias").isArray())
                .andExpect(jsonPath("$.historial.length()").value(1))
                .andExpect(jsonPath("$.historial[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.faltan").value(empty()))
                .andExpect(jsonPath("$.puedeDecidir").value(true))
                .andExpect(jsonPath("$.puedeAprobar").value(true));
    }

    @Test
    void elExpedienteDiceQueFaltaSiLaOfertaNoTieneAutorizacionVigente() throws Exception {
        long solicitud = publicar();
        jdbc.update("UPDATE autorizacion_producto SET estado = 'REVOCADA' WHERE producto_id = ?", oferta);
        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE producto_id = ?", oferta);

        mockMvc.perform(get("/api/admin/moderacion/{id}", solicitud).cookie(subAdmin))
                .andExpect(jsonPath("$.autorizacion.estado").value("REVOCADA"))
                .andExpect(jsonPath("$.autorizacion.vigente").value(false))
                .andExpect(jsonPath("$.faltan").value(hasItem("Una autorización de venta vigente para este producto")))
                .andExpect(jsonPath("$.faltan").value(hasItem("Un lote vigente con stock en un punto de acopio")))
                .andExpect(jsonPath("$.puedeDecidir").value(true))
                .andExpect(jsonPath("$.puedeAprobar").value(false));
        mockMvc.perform(get("/api/admin/moderacion").cookie(subAdmin))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].autorizada".formatted(solicitud)).value(false));
    }

    // ---------- CCPIP-331: aprobar ----------

    @Test
    void aprobarPublicaLaOfertaYAvisaAlProductor() throws Exception {
        long solicitud = publicar();
        decidir(solicitud, "aprobar", "  Fotos y precios conformes con la autorización.  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.puedeDecidir").value(false))
                .andExpect(jsonPath("$.historial[0].motivo").value("Fotos y precios conformes con la autorización."))
                .andExpect(jsonPath("$.historial[0].decididaPor").isString());

        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, oferta)).isEqualTo("PUBLICADO");
        mockMvc.perform(get("/api/productos/{id}", oferta)).andExpect(status().isOk());
        // Venia de un retiro: el historial del producto la registra como republicacion, con su motivo
        Map<String, Object> ultima = jdbc.queryForMap("""
                SELECT a.accion, a.cambios ->> 'motivo' AS motivo, u.correo FROM producto_auditoria a
                JOIN usuario u ON u.id = a.usuario_id WHERE a.producto_id = ? ORDER BY a.id DESC LIMIT 1""", oferta);
        assertThat(ultima).containsEntry("accion", "REPUBLICAR").containsEntry("correo", SUB_ADMIN)
                .containsEntry("motivo", "Fotos y precios conformes con la autorización.");
        assertThat(correos.ultimoPara(PRODUCTOR).orElseThrow().asunto()).startsWith("Publicamos tu oferta Miel de abeja melipona MOD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aviso WHERE tipo = 'DECISION_MODERACION' AND referencia_id = ?",
                Integer.class, solicitud)).isOne();

        // Ya decidida: no se decide otra vez, y queda en la pestana de aprobadas y en Mi historial
        decidir(solicitud, "aprobar", "Otra vez").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODERACION_YA_RESUELTA"));
        mockMvc.perform(get("/api/admin/moderacion").param("estado", "APROBADA").cookie(subAdmin))
                .andExpect(jsonPath("$.solicitudes[*].id").value(hasItem((int) solicitud)))
                .andExpect(jsonPath("$.contadores.aprobadasMes").value(greaterThanOrEqualTo(1)));
        mockMvc.perform(get("/api/admin/mi-historial").param("tipo", "MODERACION").cookie(subAdmin))
                .andExpect(jsonPath("$.acciones[?(@.clave == 'MP-%d')].accion".formatted(solicitud)).value("PUBLICACION_APROBADA"))
                .andExpect(jsonPath("$.acciones[?(@.clave == 'MP-%d')].objeto".formatted(solicitud)).value("MODERACION"))
                .andExpect(jsonPath("$.acciones[?(@.clave == 'MP-%d')].detalle".formatted(solicitud)).value("Miel de abeja melipona MOD"));
    }

    @Test
    void noSeApruebaSinMotivoNiSinAutorizacionVigente() throws Exception {
        long solicitud = publicar();
        decidir(solicitud, "aprobar", "   ").andExpect(status().isBadRequest());

        jdbc.update("UPDATE autorizacion_producto SET estado = 'REVOCADA' WHERE producto_id = ?", oferta);
        decidir(solicitud, "aprobar", "Conforme")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFERTA_NO_APROBABLE"))
                .andExpect(jsonPath("$.detalles").value(hasItem("Una autorización de venta vigente para este producto")));
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, oferta)).isEqualTo("EN_MODERACION");
        assertThat(jdbc.queryForObject("SELECT decision FROM moderacion_publicacion WHERE id = ?", String.class, solicitud)).isNull();
    }

    @Test
    void soloElSubadministradorModera() throws Exception {
        long solicitud = publicar();
        mockMvc.perform(get("/api/admin/moderacion").cookie(loginAs("sudo@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/moderacion/{id}", solicitud).cookie(productor)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/moderacion")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/moderacion/{id}/aprobar", solicitud).cookie(loginAs("sudo@ccpip.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Conforme\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/moderacion/{id}", 999_999).cookie(subAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOLICITUD_NO_ENCONTRADA"));
    }

    private ResultActions decidir(long solicitud, String accion, String motivo) throws Exception {
        return mockMvc.perform(post("/api/admin/moderacion/{id}/{accion}", solicitud, accion).cookie(subAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("motivo", motivo))));
    }

    /** El productor pide publicar la oferta; devuelve el id de la solicitud. */
    private long publicar() throws Exception {
        String cuerpo = mockMvc.perform(post("/api/productor/productos/{id}/publicar", oferta).cookie(productor))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode producto = objectMapper.readTree(cuerpo);
        assertThat(producto.get("estado").asText()).isEqualTo("EN_MODERACION");
        return jdbc.queryForObject("SELECT id FROM moderacion_publicacion WHERE producto_id = ? AND decision IS NULL",
                Long.class, oferta);
    }
}
