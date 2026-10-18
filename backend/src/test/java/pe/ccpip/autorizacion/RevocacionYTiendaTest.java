package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * CCPIP-39 (RF-16) revocar un producto, CCPIP-330 (RF-220) revocar la venta de un productor,
 * CCPIP-291 (RF-147) habilitar su tienda, CCPIP-292 (RF-148) la asociacion productor-producto con sus
 * lineas, y el aviso al productor de CCPIP-37 y CCPIP-297. Transaccional: nada llega a otras pruebas.
 */
@Transactional
class RevocacionYTiendaTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String NUEVO = "productor_nuevo@ccpip.test";

    private Cookie subAdmin;
    private long miel;
    private long autorizacion;

    @BeforeEach
    void preparar() throws Exception {
        subAdmin = loginAs("sub_admin@ccpip.test");
        // Publicado, con lote vendible, cuenta bancaria y autorizacion APROBADA: se puede comprar
        miel = productoConLote("Miel de abeja nativa 250 g", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        autorizacion = jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, miel);
    }

    // --- CCPIP-39: revocar un producto ---

    @Test
    void revocarUnProductoAprobadoLoSacaDeLaVentaYConservaElHistorial() throws Exception {
        long pedido = pedidoConfirmado("T-REV-0001", "Centro Nacional Lima", miel, 1);
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(true));
        mockMvc.perform(get("/api/admin/autorizaciones/{id}", autorizacion).cookie(subAdmin))
                .andExpect(jsonPath("$.puedeRevocar").value(true))
                .andExpect(jsonPath("$.puedeDecidir").value(false));

        decidir("autorizaciones", autorizacion, "revocar", "El certificado orgánico fue suspendido por el emisor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("REVOCADA"))
                .andExpect(jsonPath("$.puedeRevocar").value(false))
                .andExpect(jsonPath("$.decisiones[0].decision").value("REVOCADA"))
                .andExpect(jsonPath("$.decisiones[0].motivo").value("El certificado orgánico fue suspendido por el emisor"))
                .andExpect(jsonPath("$.decisiones[0].decididoPor").value("Sub-Administrador de Prueba"));

        // Sigue visible, pero en Venta pausada: no se agrega al carrito
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(false));
        mockMvc.perform(post("/api/carrito/items").cookie(loginAs("consumidor@ccpip.test"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("productoId", miel, "cantidad", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENTA_PAUSADA"));
        // El pedido ya confirmado no cambia
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido)).isEqualTo("CONFIRMADO");
        // Trazabilidad: historial del producto y aparece en la pestana de revocadas
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'AUTORIZACION'
                  AND cambios->>'decision' = 'REVOCADA'""", Integer.class, miel)).isEqualTo(1);
        mockMvc.perform(get("/api/admin/autorizaciones").param("estado", "REVOCADA").cookie(subAdmin))
                .andExpect(jsonPath("$.solicitudes[*].id", hasItem((int) autorizacion)))
                .andExpect(jsonPath("$.contadores.revocadasMes").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        // Se avisa al productor
        Correo aviso = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(aviso.asunto()).contains("Revocamos").contains("Miel de abeja nativa 250 g");
        assertThat(aviso.texto()).contains("El certificado orgánico fue suspendido por el emisor");
        // El productor lo ve y puede volver a solicitarla: no es un bloqueo irreversible
        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", miel).cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath("$.autorizacion.estado").value("REVOCADA"))
                .andExpect(jsonPath("$.autorizacion.motivo").value("El certificado orgánico fue suspendido por el emisor"))
                .andExpect(jsonPath("$.puedeAdjuntar").value(true));
    }

    @Test
    void soloSeRevocaLoAprobadoYSiempreConMotivo() throws Exception {
        decidir("autorizaciones", autorizacion, "revocar", "  ")
                .andExpect(status().isBadRequest());
        decidir("autorizaciones", autorizacion, "revocar", "Motivo").andExpect(status().isOk());
        decidir("autorizaciones", autorizacion, "revocar", "Otra vez")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTORIZACION_NO_APROBADA"));
        // Una solicitud pendiente se aprueba o rechaza, no se revoca
        jdbc.update("UPDATE autorizacion_producto SET estado = 'SOLICITADA', solicitada_en = now() WHERE id = ?", autorizacion);
        decidir("autorizaciones", autorizacion, "revocar", "Motivo")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTORIZACION_NO_APROBADA"));
        decidir("autorizaciones", 999_999, "revocar", "Motivo").andExpect(status().isNotFound());
    }

    @Test
    void unCertificadoVencidoNoEsUnaRevocacion() throws Exception {
        // C5: vencer pausa la venta, pero no cambia el estado ni deja una decision; la revocacion es humana
        jdbc.update("UPDATE autorizacion_producto SET vigente_hasta = current_date - 3 WHERE id = ?", autorizacion);
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(false));
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacion))
                .isEqualTo("APROBADA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autorizacion_decision WHERE autorizacion_id = ?",
                Integer.class, autorizacion)).isZero();
    }

    // --- CCPIP-37 y CCPIP-297: el resultado se notifica ---

    @Test
    void aprobarYRechazarSeAvisanAlProductorEnSuIdioma() throws Exception {
        jdbc.update("UPDATE autorizacion_producto SET estado = 'SOLICITADA', solicitada_en = now() WHERE id = ?", autorizacion);
        decidir("autorizaciones", autorizacion, "rechazar", "Falta el registro sanitario").andExpect(status().isOk());
        Correo rechazo = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(rechazo.asunto()).startsWith("No autorizamos la venta de Miel de abeja nativa 250 g");
        assertThat(rechazo.texto()).contains("Motivo: Falta el registro sanitario");

        jdbc.update("UPDATE usuario SET idioma = 'en' WHERE correo = ?", PRODUCTOR);
        jdbc.update("""
                INSERT INTO evidencia_producto (autorizacion_id, productor_id, producto_id, tipo, titulo, vigente_hasta,
                                                nombre_archivo, content_type, bytes, clave, subida_por)
                SELECT a.id, a.productor_id, a.producto_id, 'CERTIFICADO', 'Certificado', current_date + 90, 'c.pdf',
                       'application/pdf', 10, 'evidencias/prueba/' || a.id, u.id
                FROM autorizacion_producto a, usuario u WHERE a.id = ? AND u.correo = ?""", autorizacion, PRODUCTOR);
        jdbc.update("UPDATE autorizacion_producto SET estado = 'SOLICITADA' WHERE id = ?", autorizacion);
        decidir("autorizaciones", autorizacion, "aprobar", "Certificado vigente").andExpect(status().isOk());
        Correo aprobado = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(aprobado.asunto()).startsWith("We authorized the sale of Miel de abeja nativa 250 g");
        assertThat(aprobado.texto()).contains("Reason: Certificado vigente");
    }

    @Test
    void siElCorreoFallaLaDecisionIgualQuedaGuardada() throws Exception {
        correos.fallar(true);
        decidir("autorizaciones", autorizacion, "revocar", "Motivo").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacion))
                .isEqualTo("REVOCADA");
    }

    // --- CCPIP-292: asociacion productor-producto y sus lineas ---

    @Test
    void laFichaDelProductorMuestraCadaProductoQuePuedeVenderConSusLineas() throws Exception {
        long productor = productorId(PRODUCTOR);
        mockMvc.perform(get("/api/admin/productores").cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem((int) productor)))
                .andExpect(jsonPath("$[?(@.id == %d)].autorizados".formatted(productor), hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(1))));
        mockMvc.perform(get("/api/admin/productores/{id}", productor).cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiendaHabilitada").value(true))
                .andExpect(jsonPath("$.ventaRevocada").value(false))
                .andExpect(jsonPath("$.cuentaActiva").value(true))
                .andExpect(jsonPath("$.productos[?(@.productoId == %d)].estado".formatted(miel), hasItem("APROBADA")))
                .andExpect(jsonPath("$.productos[?(@.productoId == %d)].lineasAutorizadas[0]".formatted(miel), hasItem("Superfrutos")))
                .andExpect(jsonPath("$.productos[?(@.productoId == %d)].vendible".formatted(miel), hasItem(true)));
        mockMvc.perform(get("/api/admin/productores/{id}", 999_999).cookie(subAdmin)).andExpect(status().isNotFound());
    }

    @Test
    void cambiarLaLineaDelProductoNoExtiendeLaAutorizacion() throws Exception {
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, id FROM linea_producto WHERE codigo = 'CAFE'", miel);

        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(false));
        mockMvc.perform(get("/api/admin/productores/{id}", productorId(PRODUCTOR)).cookie(subAdmin))
                .andExpect(jsonPath("$.productos[?(@.productoId == %d)].cubreLineas".formatted(miel), hasItem(false)))
                .andExpect(jsonPath("$.productos[?(@.productoId == %d)].vendible".formatted(miel), hasItem(false)));
        // El productor ve por que y puede pedir una nueva revision con evidencias
        mockMvc.perform(get("/api/productor/productos/{id}/evidencias", miel).cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath("$.autorizacion.estado").value("APROBADA"))
                .andExpect(jsonPath("$.autorizacion.cubreLineas").value(false))
                .andExpect(jsonPath("$.puedeAdjuntar").value(true));

        // Al volver a su linea original, la autorizacion vuelve a cubrirlo
        jdbc.update("DELETE FROM producto_linea WHERE producto_id = ? AND linea_id = (SELECT id FROM linea_producto WHERE codigo = 'CAFE')", miel);
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(true));
    }

    // --- CCPIP-330: revocar la venta de un productor ---

    @Test
    void revocarAlProductorPausaTodosSusProductosYRestituirLosDevuelve() throws Exception {
        long productor = productorId(PRODUCTOR);
        long pedido = pedidoConfirmado("T-REV-0002", "Centro Nacional Lima", miel, 1);
        int aprobadas = jdbc.queryForObject("SELECT count(*) FROM autorizacion_producto WHERE productor_id = ? AND estado = 'APROBADA'",
                Integer.class, productor);

        decidir("productores", productor, "revocar", "Incumplimiento grave del reglamento de la Cámara")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventaRevocada").value(true))
                .andExpect(jsonPath("$.decisiones[0].decision").value("REVOCAR_VENTA"))
                .andExpect(jsonPath("$.decisiones[0].alcance").value(aprobadas))
                .andExpect(jsonPath("$.productos[?(@.vendible == true)]").isEmpty());

        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(false));
        // Sus autorizaciones por producto no se tocan y el pedido confirmado tampoco
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacion))
                .isEqualTo("APROBADA");
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido)).isEqualTo("CONFIRMADO");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND cambios->>'decision' = 'REVOCAR_VENTA'""",
                Integer.class, miel)).isEqualTo(1);
        assertThat(correos.ultimoPara(PRODUCTOR).orElseThrow().texto()).contains("Incumplimiento grave del reglamento de la Cámara");
        // La tienda revocada no se habilita ni se revoca dos veces
        decidir("productores", productor, "revocar", "Otra vez")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VENTA_YA_REVOCADA"));

        decidir("productores", productor, "restituir", "Subsanó las observaciones")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventaRevocada").value(false))
                .andExpect(jsonPath("$.decisiones[0].decision").value("RESTITUIR_VENTA"))
                .andExpect(jsonPath("$.decisiones[1].decision").value("REVOCAR_VENTA"));
        mockMvc.perform(get("/api/productos/{id}", miel)).andExpect(jsonPath("$.comprable").value(true));
        decidir("productores", productor, "restituir", "Otra vez")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VENTA_NO_REVOCADA"));
    }

    @Test
    void lasDecisionesSobreElProductorNoSeEditanNiSeBorran() throws Exception {
        long productor = productorId(PRODUCTOR);
        decidir("productores", 999_999, "revocar", "Motivo").andExpect(status().isNotFound());
        decidir("productores", productor, "revocar", "").andExpect(status().isBadRequest());
        decidir("productores", productor, "revocar", "Motivo").andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("UPDATE productor_decision SET motivo = 'otro' WHERE productor_id = ?", productor))
                .hasMessageContaining("solo insercion");
        // Tras el error la transaccion de la prueba queda abortada: el borrado lo cubre el mismo disparador
    }

    // --- CCPIP-291: habilitar la tienda ---

    @Test
    void laTiendaSePublicaSoloCuandoLaCamaraLaHabilita() throws Exception {
        long nuevo = productorId(NUEVO);
        Cookie productorNuevo = loginAs(NUEVO);
        jdbc.update("""
                UPDATE productor SET nombre_tienda = 'Asoc. Kukama Nauta', descripcion_corta = 'Camu camu del río',
                       historia = 'Doce familias kukama', pueblo = 'Kukama-Kukamiria' WHERE id = ?""", nuevo);

        // El productor no vuelve operativa su tienda por su cuenta
        mockMvc.perform(post("/api/productor/tienda/publicar").cookie(productorNuevo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIENDA_NO_HABILITADA"));
        mockMvc.perform(get("/api/productor/tienda").cookie(productorNuevo))
                .andExpect(jsonPath("$.habilitada").value(false));

        mockMvc.perform(get("/api/admin/productores/{id}", nuevo).cookie(subAdmin))
                .andExpect(jsonPath("$.tiendaHabilitada").value(false))
                .andExpect(jsonPath("$.puedeHabilitarTienda").value(true));
        decidir("productores", nuevo, "habilitar-tienda", " ").andExpect(status().isBadRequest());
        decidir("productores", nuevo, "habilitar-tienda", "Comunidad incorporada por la Cámara")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiendaHabilitada").value(true))
                .andExpect(jsonPath("$.puedeHabilitarTienda").value(false))
                .andExpect(jsonPath("$.decisiones[0].decision").value("HABILITAR_TIENDA"));
        assertThat(correos.ultimoPara(NUEVO).orElseThrow().asunto()).startsWith("Tu tienda en Mercado CCPIP está habilitada");
        decidir("productores", nuevo, "habilitar-tienda", "Otra vez")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIENDA_YA_HABILITADA"));

        mockMvc.perform(post("/api/productor/tienda/publicar").cookie(productorNuevo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicada").value(true))
                .andExpect(jsonPath("$.habilitada").value(true));
        // La base tampoco admite una tienda publicada sin habilitar (RNF-05)
        assertThatThrownBy(() -> jdbc.update("UPDATE productor SET tienda_habilitada = false WHERE id = ?", nuevo))
                .hasMessageContaining("productor_tienda_publicada_habilitada");
    }

    @Test
    void unProductorRevocadoNoRecibeSuTiendaYLoVeEnMiTienda() throws Exception {
        long nuevo = productorId(NUEVO);
        decidir("productores", nuevo, "revocar", "Documentación de la comunidad observada").andExpect(status().isOk());

        decidir("productores", nuevo, "habilitar-tienda", "Motivo")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENTA_REVOCADA"));
        mockMvc.perform(get("/api/productor/tienda").cookie(loginAs(NUEVO)))
                .andExpect(jsonPath("$.ventaRevocada").value(true))
                .andExpect(jsonPath("$.motivoRevocacion").value("Documentación de la comunidad observada"));
    }

    // --- Permisos (RNF-04) ---

    @ParameterizedTest
    @ValueSource(strings = {PRODUCTOR, "sudo@ccpip.test", "consumidor@ccpip.test", "admin_acopio@ccpip.test"})
    void revocarYHabilitarSonSoloDelSubadministrador(String correo) throws Exception {
        Cookie cookie = loginAs(correo);
        String motivo = json(Map.of("motivo", "x"));
        mockMvc.perform(get("/api/admin/productores").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/productores/1/revocar").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(motivo)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/productores/1/habilitar-tienda").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(motivo)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/autorizaciones/{id}/revocar", autorizacion).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(motivo)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT estado FROM autorizacion_producto WHERE id = ?", String.class, autorizacion))
                .isEqualTo("APROBADA");
    }

    @Test
    void sinSesionPideIngresar() throws Exception {
        mockMvc.perform(get("/api/admin/productores")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/autorizaciones/{id}/revocar", autorizacion)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", "x")))).andExpect(status().isUnauthorized());
    }

    // --- Apoyo ---

    private ResultActions decidir(String recurso, long id, String accion, String motivo) throws Exception {
        return mockMvc.perform(post("/api/admin/{recurso}/{id}/{accion}", recurso, id, accion).cookie(subAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", motivo))));
    }

    private long productorId(String correo) {
        return jdbc.queryForObject("SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?",
                Long.class, correo);
    }
}
