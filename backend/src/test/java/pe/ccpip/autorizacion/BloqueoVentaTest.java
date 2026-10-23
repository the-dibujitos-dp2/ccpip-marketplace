package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.aviso.Correo;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-85 (RF-78) bloquear las ventas de un productor y CCPIP-322 (RF-215) desbloquearlas, siempre con
 * motivo: no borra historia ni pedidos, queda auditado y se notifica. Es independiente de la revocacion
 * de CCPIP-330. Transaccional: nada llega a otras pruebas.
 */
@Transactional
class BloqueoVentaTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String MOTIVO = "Denuncias reiteradas sobre el origen sin respuesta del productor";

    private Cookie subAdmin;
    private long productor;
    private long miel;

    @BeforeEach
    void preparar() throws Exception {
        subAdmin = loginAs("sub_admin@ccpip.test");
        // Publicado, con lote vendible, cuenta bancaria y autorizacion APROBADA: se puede comprar
        miel = productoConLote("Miel de abeja nativa 250 g", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now(java.time.ZoneId.of("America/Lima")).plusMonths(6));
        productor = jdbc.queryForObject("SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?",
                Long.class, PRODUCTOR);
    }

    @Test
    void bloquearPausaSusVentasSinTocarPedidosYDesbloquearLasDevuelve() throws Exception {
        long pedido = pedidoConfirmado("T-BLQ-0001", "Centro Nacional Lima", miel, 1);
        int aprobadas = jdbc.queryForObject("SELECT count(*) FROM autorizacion_producto WHERE productor_id = ? AND estado = 'APROBADA'",
                Integer.class, productor);
        comprable(true);

        decidir(productor, "bloquear", MOTIVO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventaBloqueada").value(true))
                .andExpect(jsonPath("$.ventaRevocada").value(false))
                .andExpect(jsonPath("$.decisiones[0].decision").value("BLOQUEAR_VENTA"))
                .andExpect(jsonPath("$.decisiones[0].motivo").value(MOTIVO))
                .andExpect(jsonPath("$.decisiones[0].decididoPor").value("Sub-Administrador de Prueba"))
                .andExpect(jsonPath("$.decisiones[0].alcance").value(aprobadas))
                .andExpect(jsonPath("$.productos[?(@.vendible == true)]").isEmpty());

        // Sigue visible en Venta pausada: no se agrega al carrito
        comprable(false);
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(status().isOk());
        mockMvc.perform(post("/api/carrito/items").cookie(loginAs("consumidor@ccpip.test"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("productoId", miel, "cantidad", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENTA_PAUSADA"));
        // No borra historia ni pedidos: el pedido confirmado y las autorizaciones siguen igual
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido)).isEqualTo("CONFIRMADO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autorizacion_producto WHERE productor_id = ? AND estado = 'APROBADA'",
                Integer.class, productor)).isEqualTo(aprobadas);
        // Auditado: tambien en el historial de cada producto alcanzado
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND cambios->>'decision' = 'BLOQUEAR_VENTA'""",
                Integer.class, miel)).isEqualTo(1);
        // Notificado
        Correo aviso = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(aviso.asunto()).startsWith("Bloqueamos las ventas de tu organización");
        assertThat(aviso.texto()).contains("Motivo: " + MOTIVO);
        conflicto(decidir(productor, "bloquear", "Otra vez"), "VENTA_YA_BLOQUEADA");

        decidir(productor, "desbloquear", "El productor respondió las denuncias y presentó su descargo")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventaBloqueada").value(false))
                .andExpect(jsonPath("$.decisiones[0].decision").value("DESBLOQUEAR_VENTA"))
                .andExpect(jsonPath("$.decisiones[1].decision").value("BLOQUEAR_VENTA"));
        comprable(true);
        assertThat(correos.ultimoPara(PRODUCTOR).orElseThrow().asunto()).startsWith("Desbloqueamos las ventas de tu organización");
        conflicto(decidir(productor, "desbloquear", "Otra vez"), "VENTA_NO_BLOQUEADA");
    }

    @Test
    void elBloqueoYLaRevocacionSonIndependientes() throws Exception {
        decidir(productor, "revocar", "Certificado de origen observado").andExpect(status().isOk());
        decidir(productor, "bloquear", MOTIVO).andExpect(status().isOk())
                .andExpect(jsonPath("$.ventaRevocada").value(true))
                .andExpect(jsonPath("$.ventaBloqueada").value(true));

        // Desbloquear no restituye una venta revocada
        decidir(productor, "desbloquear", "Descargo aceptado").andExpect(status().isOk())
                .andExpect(jsonPath("$.ventaRevocada").value(true))
                .andExpect(jsonPath("$.ventaBloqueada").value(false));
        comprable(false);

        decidir(productor, "restituir", "Certificado renovado").andExpect(status().isOk());
        comprable(true);
    }

    @Test
    void desbloquearAUnRevocadoNoPrometeQueVuelveAVender() throws Exception {
        decidir(productor, "revocar", "Certificado de origen observado").andExpect(status().isOk());
        decidir(productor, "bloquear", MOTIVO).andExpect(status().isOk());

        decidir(productor, "desbloquear", "Respondió las denuncias").andExpect(status().isOk());
        Correo desbloqueo = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(desbloqueo.asunto()).startsWith("Levantamos el bloqueo de tu organización");
        assertThat(desbloqueo.texto()).contains("sigue revocada").contains("Motivo: Respondió las denuncias")
                .doesNotContain("vuelven a estar a la venta");
        comprable(false);

        // Sin otra restriccion vigente, restituir si anuncia que vuelve a vender
        decidir(productor, "restituir", "Certificado renovado").andExpect(status().isOk());
        assertThat(correos.ultimoPara(PRODUCTOR).orElseThrow().texto()).contains("vuelven a estar a la venta");
        comprable(true);
    }

    @Test
    void restituirAUnBloqueadoNoPrometeQueVuelveAVender() throws Exception {
        decidir(productor, "bloquear", MOTIVO).andExpect(status().isOk());
        decidir(productor, "revocar", "Certificado de origen observado").andExpect(status().isOk());

        decidir(productor, "restituir", "Certificado renovado").andExpect(status().isOk());
        Correo restitucion = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(restitucion.asunto()).startsWith("Restituimos la autorización de venta de tu organización");
        assertThat(restitucion.texto()).contains("siguen bloqueadas").doesNotContain("vuelven a estar a la venta");
        comprable(false);

        decidir(productor, "desbloquear", "Respondió las denuncias").andExpect(status().isOk());
        Correo desbloqueo = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(desbloqueo.asunto()).startsWith("Desbloqueamos las ventas de tu organización");
        assertThat(desbloqueo.texto()).contains("vuelven a estar a la venta");
        comprable(true);
    }

    @Test
    void unProductorBloqueadoNoRecibeSuTienda() throws Exception {
        long nuevo = jdbc.queryForObject("SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?",
                Long.class, "productor_nuevo@ccpip.test");
        decidir(nuevo, "bloquear", MOTIVO).andExpect(status().isOk())
                .andExpect(jsonPath("$.puedeHabilitarTienda").value(false));

        conflicto(decidir(nuevo, "habilitar-tienda", "Comunidad incorporada por la Cámara"), "VENTA_BLOQUEADA");
        assertThat(jdbc.queryForObject("SELECT tienda_habilitada FROM productor WHERE id = ?", Boolean.class, nuevo)).isFalse();

        decidir(nuevo, "desbloquear", "Descargo aceptado").andExpect(status().isOk())
                .andExpect(jsonPath("$.puedeHabilitarTienda").value(true));
        decidir(nuevo, "habilitar-tienda", "Comunidad incorporada por la Cámara").andExpect(status().isOk())
                .andExpect(jsonPath("$.tiendaHabilitada").value(true));
    }

    @Test
    void siempreConMotivoYSobreUnProductorExistente() throws Exception {
        decidir(productor, "bloquear", "  ").andExpect(status().isBadRequest());
        decidir(productor, "desbloquear", "").andExpect(status().isBadRequest());
        decidir(999_999, "bloquear", "Motivo").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT venta_bloqueada FROM productor WHERE id = ?", Boolean.class, productor)).isFalse();
    }

    @Test
    void elBloqueoSeVeEnLasListasDelSubadministradorYEnMiTienda() throws Exception {
        mockMvc.perform(post("/api/denuncias").cookie(loginAs("consumidor@ccpip.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productoId", miel, "motivo", "ORIGEN_DUDOSO","descripcion", "La etiqueta no indica la comunidad de origen."))))
                .andExpect(status().isCreated());
        decidir(productor, "bloquear", MOTIVO).andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/productores").cookie(subAdmin))
                .andExpect(jsonPath("$[?(@.id == %d)].ventaBloqueada".formatted(productor), hasItem(true)));
        // Figma S-03: el estado Bloqueado en la bandeja de denuncias y en el expediente
        mockMvc.perform(get("/api/admin/denuncias").cookie(subAdmin))
                .andExpect(jsonPath("$.productores[?(@.productorId == %d)].ventaBloqueada".formatted(productor), hasItem(true)));
        mockMvc.perform(get("/api/admin/denuncias/productores/{id}", productor).cookie(subAdmin))
                .andExpect(jsonPath("$.productor.ventaBloqueada").value(true));
        // El productor ve el bloqueo y su motivo en su tienda
        mockMvc.perform(get("/api/productor/tienda").cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath("$.ventaBloqueada").value(true))
                .andExpect(jsonPath("$.motivoBloqueo").value(MOTIVO))
                .andExpect(jsonPath("$.ventaRevocada").value(false));
    }

    // --- Permisos (RNF-04) ---

    @ParameterizedTest
    @ValueSource(strings = {PRODUCTOR, "sudo@ccpip.test", "consumidor@ccpip.test", "admin_acopio@ccpip.test"})
    void bloquearYDesbloquearSonSoloDelSubadministrador(String correo) throws Exception {
        Cookie cookie = loginAs(correo);
        String motivo = json(Map.of("motivo", "x"));
        for (String accion : new String[] {"bloquear", "desbloquear"}) {
            mockMvc.perform(post("/api/admin/productores/{id}/{accion}", productor, accion).cookie(cookie)
                    .contentType(MediaType.APPLICATION_JSON).content(motivo)).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT venta_bloqueada FROM productor WHERE id = ?", Boolean.class, productor)).isFalse();
    }

    @Test
    void sinSesionPideIngresar() throws Exception {
        mockMvc.perform(post("/api/admin/productores/{id}/bloquear", productor)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", "x")))).andExpect(status().isUnauthorized());
    }

    // --- Apoyo ---

    private void comprable(boolean esperado) throws Exception {
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(esperado));
    }

    private static void conflicto(ResultActions respuesta, String codigo) throws Exception {
        respuesta.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(codigo));
    }

    private ResultActions decidir(long id, String accion, String motivo) throws Exception {
        return mockMvc.perform(post("/api/admin/productores/{id}/{accion}", id, accion).cookie(subAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", motivo))));
    }
}
