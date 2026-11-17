package pe.ccpip.fidelizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-86 (RF-92): el comprador acumula puntos al completarse la entrega, con la regla base o con la campana que
 * regia al confirmar su pedido. El sub administrador programa las campanas y ve sus metricas.
 *
 * <p>Transaccional: las campanas y los pedidos de la prueba no llegan a las demas pruebas.
 */
@Transactional
class FidelizacionTest extends IntegrationTest {

    private static final String SUB = "sub_admin@ccpip.test";
    private static final String CONSUMIDOR = "consumidor@ccpip.test";
    private static final String LIMA = "Centro Nacional Lima";

    @Autowired
    private PuntosService puntos;

    private Cookie sub;
    private Cookie acopio;
    private LocalDate hoy;
    private int secuencia;

    @BeforeEach
    void sesiones() throws Exception {
        sub = loginAs(SUB);
        acopio = loginAs("admin_acopio@ccpip.test");
        hoy = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
    }

    // --- Acumulacion ---

    @Test
    void alRecogerSuPedidoGanaPuntosConLaReglaBaseYLosVeEnSuPedido() throws Exception {
        long pedido = pedido("85.50");
        entregarEnElPunto(pedido);

        assertThat(movimiento(pedido)).containsEntry("puntos", 8).containsEntry("campana_id", null)
                .containsEntry("regla_puntos", 1);
        mockMvc.perform(get("/api/pedidos/{id}", pedido).cookie(loginAs(CONSUMIDOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.puntos.puntos").value(8))
                .andExpect(jsonPath("$.puntos.campana").doesNotExist());
    }

    @Test
    void alEntregarseADomicilioTambienGanaPuntos() throws Exception {
        long pedido = pedido("120.00");
        String numero = numero(pedido);
        Cookie distribuidor = loginAs("distribuidor@ccpip.test");
        jdbc.update("UPDATE pedido SET modalidad='ENVIO', estado='LISTO_PARA_DESPACHO' WHERE id=?", pedido);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, 'LISTO_PARA_DESPACHO')", pedido);
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular)
                VALUES (?, '150122', 'Calle 1', 'Reservado', '999888777')""", pedido);
        for (String paso : new String[]{"asumir", "retirar", "en-camino", "entregar"}) {
            mockMvc.perform(post("/api/distribuidor/pedidos/{n}/" + paso, numero).cookie(distribuidor)).andExpect(status().isOk());
        }
        // Registrar la entrega otra vez no acredita dos veces
        mockMvc.perform(post("/api/distribuidor/pedidos/{n}/entregar", numero).cookie(distribuidor)).andExpect(status().isOk());

        assertThat(movimiento(pedido)).containsEntry("puntos", 12);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_puntos WHERE pedido_id = ?", Integer.class, pedido)).isOne();
    }

    @Test
    void unPedidoSinEntregarOQueNoLlegaADiezSolesNoDaPuntos() throws Exception {
        long sinEntregar = pedido("200.00");
        puntos.acreditarEntrega(sinEntregar);
        long pequeno = pedido("9.90");
        entregarEnElPunto(pequeno);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_puntos WHERE pedido_id IN (?, ?)", Integer.class,
                sinEntregar, pequeno)).isZero();
        mockMvc.perform(get("/api/pedidos/{id}", pequeno).cookie(loginAs(CONSUMIDOR)))
                .andExpect(jsonPath("$.puntos").doesNotExist());
    }

    @Test
    void losPuntosGanadosNoSeEditanNiSeBorran() throws Exception {
        long pedido = pedido("50.00");
        entregarEnElPunto(pedido);
        puntos.acreditarEntrega(pedido);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_puntos WHERE pedido_id = ?", Integer.class, pedido)).isOne();
        assertRechazaLaBase(() -> jdbc.update("UPDATE movimiento_puntos SET puntos = 1 WHERE pedido_id = ?", pedido), "nunca se quitan");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM movimiento_puntos WHERE pedido_id = ?", pedido), "nunca se quitan");
    }

    @Test
    void conUnaCampanaVigenteAlConfirmarGanaConSuReglaYSuTope() throws Exception {
        long campana = crear(campana(hoy, hoy.plusDays(7), 10, "1", "50", 900)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);
        long pedido = pedido("85.50");
        long conTope = pedido("120.00");
        entregarEnElPunto(pedido);
        entregarEnElPunto(conTope);

        assertThat(movimiento(pedido)).containsEntry("puntos", 850).containsEntry("campana_id", campana)
                .containsEntry("regla_puntos", 10);
        assertThat(movimiento(conTope)).containsEntry("puntos", 900);
        mockMvc.perform(get("/api/pedidos/{id}", pedido).cookie(loginAs(CONSUMIDOR)))
                .andExpect(jsonPath("$.puntos.puntos").value(850))
                .andExpect(jsonPath("$.puntos.campana").value("Campaña de prueba"));
    }

    @Test
    void debajoDelMinimoOConfirmadoAntesDeLaCampanaRigeLaBase() throws Exception {
        crear(campana(hoy, hoy.plusDays(7), 10, "1", "100", null)).andExpect(status().isCreated());
        long bajoMinimo = pedido("85.50");
        long anterior = pedido("150.00");
        jdbc.update("UPDATE pedido SET confirmado_en = now() - interval '3 days' WHERE id = ?", anterior);
        entregarEnElPunto(bajoMinimo);
        entregarEnElPunto(anterior);

        assertThat(movimiento(bajoMinimo)).containsEntry("puntos", 8).containsEntry("campana_id", null);
        assertThat(movimiento(anterior)).containsEntry("puntos", 15).containsEntry("campana_id", null);
    }

    @Test
    void unPedidoConfirmadoDespuesDeFinalizarLaCampanaGanaConLaBase() throws Exception {
        long campana = crear(campana(hoy, hoy.plusDays(7), 10, "1", null, null))
                .andReturn().getResponse().getContentAsString().transform(this::id);
        finalizar(campana).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("FINALIZADA"))
                .andExpect(jsonPath("$.finEfectivo").value(hoy.toString()))
                .andExpect(jsonPath("$.finalizadoPor").value("Sub-Administrador de Prueba"));
        long pedido = pedido("40.00");
        jdbc.update("UPDATE pedido SET confirmado_en = clock_timestamp() + interval '1 second' WHERE id = ?", pedido);
        entregarEnElPunto(pedido);

        assertThat(movimiento(pedido)).containsEntry("puntos", 4).containsEntry("campana_id", null);
        finalizar(campana).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPANA_YA_TERMINO"));
    }

    // --- Campanas ---

    @Test
    void elSubAdministradorProgramaEditaYCancelaUnaCampanaQueAunNoEmpieza() throws Exception {
        String creada = crear(campana(hoy.plusDays(1), hoy.plusDays(5), 2, "10", "30", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PROGRAMADA"))
                .andExpect(jsonPath("$.registradoPor").value("Sub-Administrador de Prueba"))
                .andExpect(jsonPath("$.montoMinimo").value(30))
                .andReturn().getResponse().getContentAsString();
        long id = id(creada);

        Map<String, Object> cambios = campana(hoy.plusDays(2), hoy.plusDays(9), 3, "10", null, 500);
        cambios.put("nombre", "Semana del cacao");
        mockMvc.perform(put("/api/admin/fidelizacion/campanas/{id}", id).cookie(sub)
                        .contentType(MediaType.APPLICATION_JSON).content(json(cambios)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Semana del cacao"))
                .andExpect(jsonPath("$.puntos").value(3))
                .andExpect(jsonPath("$.topePuntos").value(500))
                .andExpect(jsonPath("$.montoMinimo").value(0));

        finalizar(id).andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADA"))
                .andExpect(jsonPath("$.finEfectivo").doesNotExist());
        assertThat(jdbc.queryForList("SELECT accion FROM auditoria_evento WHERE entidad = 'CAMPANA_PUNTOS' AND referencia = ?",
                String.class, String.valueOf(id)))
                .containsExactlyInAnyOrder("CAMPANA_PUNTOS_CREADA", "CAMPANA_PUNTOS_EDITADA", "CAMPANA_PUNTOS_FINALIZADA");
    }

    @Test
    void elSuperadministradorVeLasCampanasEnLaAuditoria() throws Exception {
        long id = id(crear(campana(hoy.plusDays(1), hoy.plusDays(5), 2, "10", null, null)).andReturn().getResponse().getContentAsString());
        finalizar(id).andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/auditoria").param("entidad", "CAMPANA_PUNTOS").cookie(loginAs("sudo@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[?(@.referencia == '%s' && @.accion == 'CAMPANA_PUNTOS_CREADA')].usuario", String.valueOf(id))
                        .value("Sub-Administrador de Prueba"))
                .andExpect(jsonPath("$.registros[?(@.referencia == '%s' && @.accion == 'CAMPANA_PUNTOS_FINALIZADA')].rol", String.valueOf(id))
                        .value("SUB_ADMIN"));
        // Sin filtro tambien: un evento de campana no rompe la consulta general
        mockMvc.perform(get("/api/admin/auditoria").cookie(loginAs("sudo@ccpip.test"))).andExpect(status().isOk());
    }

    @Test
    void elSubAdministradorVeSusCampanasEnMiHistorial() throws Exception {
        long id = id(crear(campana(hoy.plusDays(1), hoy.plusDays(5), 2, "10", null, null)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(put("/api/admin/fidelizacion/campanas/{id}", id).cookie(sub)
                .contentType(MediaType.APPLICATION_JSON).content(json(campana(hoy.plusDays(2), hoy.plusDays(6), 3, "10", null, null))))
                .andExpect(status().isOk());
        finalizar(id).andExpect(status().isOk());

        String campana = "$.acciones[?(@.objeto == 'CAMPANA' && @.objetoId == %d)]";
        mockMvc.perform(get("/api/admin/mi-historial").param("tipo", "FIDELIZACION").cookie(sub))
                .andExpect(status().isOk())
                .andExpect(jsonPath(campana + ".accion", id).value(org.hamcrest.Matchers.containsInAnyOrder(
                        "CAMPANA_PUNTOS_CREADA", "CAMPANA_PUNTOS_EDITADA", "CAMPANA_PUNTOS_FINALIZADA")))
                .andExpect(jsonPath(campana + ".sobre", id).value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("Campaña de prueba"))))
                .andExpect(jsonPath(campana + ".tipo", id).value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("FIDELIZACION"))));
    }

    @Test
    void unaCampanaActivaNoSeEdita() throws Exception {
        long id = id(crear(campana(hoy, hoy.plusDays(3), 2, "10", null, null)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(put("/api/admin/fidelizacion/campanas/{id}", id).cookie(sub)
                        .contentType(MediaType.APPLICATION_JSON).content(json(campana(hoy, hoy.plusDays(9), 5, "10", null, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPANA_NO_EDITABLE"));
    }

    @Test
    void rechazaCampanasInvalidasOQueSeCruzan() throws Exception {
        crear(campana(hoy.minusDays(1), hoy.plusDays(3), 2, "10", null, null))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CAMPANA_INICIO_PASADO"));
        crear(campana(hoy.plusDays(3), hoy.plusDays(1), 2, "10", null, null))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CAMPANA_FIN_ANTES_DE_INICIO"));
        crear(campana(hoy, hoy.plusDays(3), 1, "10", null, null))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CAMPANA_NO_MEJORA_BASE"));
        Map<String, Object> sinNombre = campana(hoy, hoy.plusDays(3), 2, "10", null, null);
        sinNombre.put("nombre", " ");
        crear(sinNombre).andExpect(status().isBadRequest());

        long primera = id(crear(campana(hoy, hoy.plusDays(5), 2, "10", null, null)).andReturn().getResponse().getContentAsString());
        crear(campana(hoy.plusDays(3), hoy.plusDays(8), 2, "10", null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPANA_SE_CRUZA"))
                .andExpect(jsonPath("$.error").value("Se cruza con la campaña «Campaña de prueba». Elige otras fechas."));
        // Finalizada hoy, deja libre desde manana; hoy aun cuenta
        finalizar(primera).andExpect(status().isOk());
        crear(campana(hoy, hoy.plusDays(2), 2, "10", null, null)).andExpect(status().isConflict());
        crear(campana(hoy.plusDays(1), hoy.plusDays(2), 2, "10", null, null)).andExpect(status().isCreated());
    }

    @Test
    void soloElSubAdministradorEntraAFidelizacion() throws Exception {
        for (String correo : new String[]{"sudo@ccpip.test", CONSUMIDOR, "productor@ccpip.test"}) {
            mockMvc.perform(get("/api/admin/fidelizacion").cookie(loginAs(correo))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/admin/fidelizacion")).andExpect(status().isUnauthorized());
    }

    // --- Panel y metricas ---

    @Test
    void elPanelMuestraLaReglaBaseLoAcumuladoYLasCampanas() throws Exception {
        JsonNode antes = panel();
        long id = id(crear(campana(hoy, hoy.plusDays(7), 10, "1", null, null)).andReturn().getResponse().getContentAsString());
        entregarEnElPunto(pedido("30.00"));

        JsonNode despues = panel();
        assertThat(despues.at("/hoy").asText()).isEqualTo(hoy.toString());
        assertThat(despues.at("/reglaBase/puntos").asInt()).isOne();
        assertThat(despues.at("/reglaBase/cadaSoles").decimalValue()).isEqualByComparingTo("10");
        assertThat(despues.at("/totales/puntosOtorgados").asLong() - antes.at("/totales/puntosOtorgados").asLong()).isEqualTo(300);
        assertThat(despues.at("/totales/puntosDeCampanas").asLong() - antes.at("/totales/puntosDeCampanas").asLong()).isEqualTo(300);
        JsonNode campana = despues.at("/campanas/0");
        assertThat(campana.at("/id").asLong()).isEqualTo(id);
        assertThat(campana.at("/estado").asText()).isEqualTo("ACTIVA");
        assertThat(campana.at("/resumen/puntosOtorgados").asLong()).isEqualTo(300);
        assertThat(campana.at("/resumen/pedidos").asInt()).isPositive();
    }

    @Test
    void lasMetricasComparanConElPeriodoAnteriorYProyectanLosPuntosPorAcreditar() throws Exception {
        // Un minimo alto deja fuera los pedidos de otras pruebas
        long id = id(crear(campana(hoy, hoy.plusDays(7), 10, "1", "5000", null)).andReturn().getResponse().getContentAsString());
        long antiguo = pedido("5000.00");
        jdbc.update("UPDATE pedido SET confirmado_en = now() - interval '10 days' WHERE id = ?", antiguo);
        long ayer = pedido("5000.00");
        jdbc.update("UPDATE pedido SET confirmado_en = now() - interval '1 day' WHERE id = ?", ayer);
        long entregado = pedido("5085.50");
        entregarEnElPunto(entregado);
        pedido("5040.00");
        pedido("4000.00");

        mockMvc.perform(get("/api/admin/fidelizacion/campanas/{id}/metricas", id).cookie(sub))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(hoy.toString()))
                .andExpect(jsonPath("$.hasta").value(hoy.toString()))
                .andExpect(jsonPath("$.anteriorDesde").value(hoy.minusDays(1).toString()))
                .andExpect(jsonPath("$.pedidos.valor").value(2))
                .andExpect(jsonPath("$.pedidos.anterior").value(1))
                .andExpect(jsonPath("$.pedidos.variacion").value(100))
                .andExpect(jsonPath("$.ventas.valor").value(10125.50))
                .andExpect(jsonPath("$.ticketPromedio.valor").value(5062.75))
                .andExpect(jsonPath("$.clientes.valor").value(1))
                .andExpect(jsonPath("$.puntosOtorgados").value(50850))
                .andExpect(jsonPath("$.puntosPorAcreditar").value(50400))
                .andExpect(jsonPath("$.pedidosEntregados").value(1))
                .andExpect(jsonPath("$.pedidosPorEntregar").value(1))
                .andExpect(jsonPath("$.clientesNuevos").value(0))
                .andExpect(jsonPath("$.clientesRecurrentes").value(1))
                .andExpect(jsonPath("$.dias.length()").value(1))
                .andExpect(jsonPath("$.dias[0].puntos").value(101250))
                .andExpect(jsonPath("$.productos[0].nombre").value("Producto fidelización 3"));
        JsonNode metricas = objectMapper.readTree(mockMvc.perform(get("/api/admin/fidelizacion/campanas/{id}/metricas", id).cookie(sub))
                .andReturn().getResponse().getContentAsString());
        assertThat(metricas.at("/pedidosBajoMinimo").asInt()).isPositive();
    }

    @Test
    void unaCampanaProgramadaAunNoTieneMetricas() throws Exception {
        long id = id(crear(campana(hoy.plusDays(2), hoy.plusDays(3), 2, "10", null, null)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(get("/api/admin/fidelizacion/campanas/{id}/metricas", id).cookie(sub))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAMPANA_SIN_METRICAS"));
        mockMvc.perform(get("/api/admin/fidelizacion/campanas/{id}/metricas", 999_999).cookie(sub))
                .andExpect(status().isNotFound());
    }

    // --- Apoyo ---

    /** Un pedido confirmado ahora por el consumidor de prueba, con {@code total} soles de productos. */
    private long pedido(String total) {
        secuencia++;
        long producto = productoConLote("Producto fidelización " + secuencia, "PUBLICADO", "PUNTO_ACOPIO", hoy.plusMonths(6));
        long id = pedidoConfirmado("FID-" + secuencia, LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET total = ?::numeric WHERE id = ?", total, id);
        jdbc.update("UPDATE item_pedido SET subtotal = ?::numeric WHERE pedido_id = ?", total, id);
        return id;
    }

    private String numero(long pedido) {
        return jdbc.queryForObject("SELECT numero FROM pedido WHERE id = ?", String.class, pedido);
    }

    /** El punto de acopio lo deja listo y el comprador lo recoge (CCPIP-71). */
    private void entregarEnElPunto(long pedido) throws Exception {
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO' WHERE id = ?", pedido);
        mockMvc.perform(post("/api/acopio/pedidos/{numero}/recojo", numero(pedido)).cookie(acopio)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"persona\":\"Consumidor de Prueba\",\"celular\":\"987654321\"}"))
                .andExpect(status().isOk());
    }

    private Map<String, Object> movimiento(long pedido) {
        return jdbc.queryForMap("SELECT puntos, campana_id, regla_puntos FROM movimiento_puntos WHERE pedido_id = ?", pedido);
    }

    private Map<String, Object> campana(LocalDate desde, LocalDate hasta, int puntosPorTramo, String cadaSoles,
                                        String minimo, Integer tope) {
        Map<String, Object> c = new HashMap<>();
        c.put("nombre", "Campaña de prueba");
        c.put("descripcion", "Para probar la fidelización");
        c.put("desde", desde.toString());
        c.put("hasta", hasta.toString());
        c.put("puntos", puntosPorTramo);
        c.put("cadaSoles", cadaSoles);
        c.put("montoMinimo", minimo);
        c.put("topePuntos", tope);
        return c;
    }

    private ResultActions crear(Map<String, Object> campana) throws Exception {
        return mockMvc.perform(post("/api/admin/fidelizacion/campanas").cookie(sub)
                .contentType(MediaType.APPLICATION_JSON).content(json(campana)));
    }

    private ResultActions finalizar(long id) throws Exception {
        return mockMvc.perform(post("/api/admin/fidelizacion/campanas/{id}/finalizar", id).cookie(sub));
    }

    private JsonNode panel() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/admin/fidelizacion").cookie(sub))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private long id(String json) {
        try {
            return objectMapper.readTree(json).at("/id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
