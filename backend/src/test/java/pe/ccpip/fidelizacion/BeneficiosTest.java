package pe.ccpip.fidelizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
 * CCPIP-88: niveles por recurrencia que multiplican los puntos. CCPIP-89: cupones que se canjean con puntos.
 * CCPIP-173/374: saldo y movimientos del comprador. Codigos de descuento de las campanas. Un solo descuento por
 * pedido, que asume la Camara; los puntos se ganan sobre lo pagado por los productos.
 *
 * <p>Transaccional, con un comprador propio: sus puntos y pedidos no llegan a las demas pruebas.
 */
@Transactional
class BeneficiosTest extends IntegrationTest {

    private static final String COMPRADOR = "comprador.beneficios@ccpip.test";

    @Autowired
    private PuntosService puntos;

    private Cookie sub;
    private Cookie comprador;
    private LocalDate hoy;

    @BeforeEach
    void sesiones() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Comprador de Beneficios', rol_id, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", COMPRADOR);
        sub = loginAs("sub_admin@ccpip.test");
        comprador = loginAs(COMPRADOR);
        hoy = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
    }

    // --- CCPIP-88: niveles ---

    @Test
    void elNivelPorPedidosEntregadosMultiplicaLosPuntos() throws Exception {
        mockMvc.perform(post("/api/admin/fidelizacion/niveles").cookie(sub).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Plata", "pedidosMinimos", 2, "multiplicador", "1.5"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].nombre").value("Plata"));
        mockMvc.perform(post("/api/admin/fidelizacion/niveles").cookie(sub).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Otro", "pedidosMinimos", 2, "multiplicador", "2"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NIVEL_REPETIDO"));

        long primero = entregado("100.00");
        long segundo = entregado("100.00");

        assertThat(jdbc.queryForMap("SELECT puntos, nivel FROM movimiento_puntos WHERE pedido_id = ?", primero))
                .containsEntry("puntos", 10).containsEntry("nivel", null);
        assertThat(jdbc.queryForMap("SELECT puntos, nivel, multiplicador FROM movimiento_puntos WHERE pedido_id = ?", segundo))
                .containsEntry("puntos", 15).containsEntry("nivel", "Plata");
        mockMvc.perform(get("/api/puntos").cookie(comprador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(25))
                .andExpect(jsonPath("$.nivel.pedidosEntregados").value(2))
                .andExpect(jsonPath("$.nivel.actual.nombre").value("Plata"))
                .andExpect(jsonPath("$.nivel.siguiente").doesNotExist())
                .andExpect(jsonPath("$.movimientos[0].tipo").value("ACUMULACION"))
                .andExpect(jsonPath("$.movimientos[0].nivel").value("Plata"));
    }

    @Test
    void unNivelRetiradoDejaDeRegir() throws Exception {
        long id = objectMapper.readTree(mockMvc.perform(post("/api/admin/fidelizacion/niveles").cookie(sub)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Oro", "pedidosMinimos", 1, "multiplicador", "2"))))
                .andReturn().getResponse().getContentAsString()).at("/0/id").asLong();
        mockMvc.perform(delete("/api/admin/fidelizacion/niveles/{id}", id).cookie(sub))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        long pedido = entregado("100.00");
        assertThat(jdbc.queryForObject("SELECT puntos FROM movimiento_puntos WHERE pedido_id = ?", Integer.class, pedido)).isEqualTo(10);
    }

    // --- CCPIP-89: cupones ---

    @Test
    void canjeaPuntosPorUnCuponYLoUsaEnUnaCompraQueAsumeLaCamara() throws Exception {
        long cupon = cupon(Map.of("tipo", "MONTO", "valor", 10, "montoMinimo", 30), 20);
        entregado("250.00");

        mockMvc.perform(post("/api/puntos/cupones/{id}/canjear", cupon).cookie(comprador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldo").value(5))
                .andExpect(jsonPath("$.cupones[0].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.cupones[0].codigo").value(org.hamcrest.Matchers.matchesPattern("PTS[A-Z2-9]{7}")))
                .andExpect(jsonPath("$.movimientos[0].tipo").value("CANJE"))
                .andExpect(jsonPath("$.movimientos[0].puntos").value(20));
        mockMvc.perform(post("/api/puntos/cupones/{id}/canjear", cupon).cookie(comprador))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUNTOS_INSUFICIENTES"))
                .andExpect(jsonPath("$.error").value("Te faltan 15 puntos para canjear este cupón."));
        long canjeado = jdbc.queryForObject("SELECT id FROM cupon_cliente WHERE usuario_id = " + usuario(), Long.class);

        // Carrito de S/ 50: el cupon descuenta S/ 10 y se cobran S/ 40
        alCarrito(comprador, productoEnCusco("Canasta", "25.00", "productor@ccpip.test"), 2);
        mockMvc.perform(post("/api/compra/descuento").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("cuponId", canjeado))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origen").value("CUPON"))
                .andExpect(jsonPath("$.monto").value(10.0));
        long pedido = confirmar(Map.of("cuponId", canjeado))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.descuento.origen").value("CUPON"))
                .andExpect(jsonPath("$.descuento.monto").value(10.0))
                .andExpect(jsonPath("$.totalAPagar").value(40.0))
                .andReturn().getResponse().getContentAsString().transform(this::id);
        mockMvc.perform(get("/api/puntos").cookie(comprador)).andExpect(jsonPath("$.cupones[0].estado").value("EN_USO"));
        pagar(pedido);

        assertThat(jdbc.queryForObject("SELECT monto FROM pago WHERE pedido_id = ? AND estado = 'APROBADO'", BigDecimal.class, pedido))
                .isEqualByComparingTo("40.00");
        mockMvc.perform(get("/api/puntos").cookie(comprador)).andExpect(jsonPath("$.cupones[0].estado").value("USADO"));
        // El productor cobra su neto completo; la Camara asume el descuento
        JsonNode resumen = objectMapper.readTree(mockMvc.perform(get("/api/admin/comision/pedidos").cookie(sub))
                .andReturn().getResponse().getContentAsString()).at("/resumen");
        assertThat(resumen.at("/descuentos").decimalValue()).isGreaterThanOrEqualTo(BigDecimal.TEN);
        assertThat(jdbc.queryForObject("SELECT sum(neto) FROM item_pedido WHERE pedido_id = ?", BigDecimal.class, pedido))
                .isEqualByComparingTo(jdbc.queryForObject("SELECT total - comision_monto FROM pedido WHERE id = ?", BigDecimal.class, pedido));

        // Ya usado, no vale para otra compra
        alCarrito(comprador, productoEnCusco("Otra canasta", "25.00", "productor@ccpip.test"), 2);
        confirmar(Map.of("cuponId", canjeado)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUPON_USADO"));
    }

    @Test
    void losPuntosSeGananSobreLoPagadoDespuesDelDescuento() throws Exception {
        long cupon = cupon(Map.of("tipo", "MONTO", "valor", 15), 10);
        entregado("100.00");
        mockMvc.perform(post("/api/puntos/cupones/{id}/canjear", cupon).cookie(comprador)).andExpect(status().isOk());
        long canjeado = jdbc.queryForObject("SELECT id FROM cupon_cliente WHERE usuario_id = " + usuario(), Long.class);
        alCarrito(comprador, productoEnCusco("Tejido", "50.00", "productor@ccpip.test"), 1);
        long pedido = id(confirmar(Map.of("cuponId", canjeado)).andReturn().getResponse().getContentAsString());
        jdbc.update("UPDATE pedido SET estado = 'ENTREGADO', confirmado_en = now() WHERE id = ?", pedido);
        puntos.acreditarEntrega(pedido);
        // S/ 50 - S/ 15 = S/ 35 -> 3 puntos
        assertThat(jdbc.queryForMap("SELECT puntos, monto_base FROM movimiento_puntos WHERE pedido_id = ?", pedido))
                .containsEntry("puntos", 3).containsEntry("monto_base", new BigDecimal("35.00"));
    }

    @Test
    void rechazaCuponesInvalidosYLosQueNoEstanActivos() throws Exception {
        crearCupon(Map.of("tipo", "PORCENTAJE", "valor", 95), 10)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PORCENTAJE_MUY_ALTO"));
        crearCupon(Map.of("tipo", "MONTO", "valor", 10, "topeDescuento", 5), 10)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOPE_SOLO_PORCENTAJE"));
        long cupon = cupon(Map.of("tipo", "PORCENTAJE", "valor", 10, "topeDescuento", 8), 5);
        mockMvc.perform(put("/api/admin/fidelizacion/cupones/{id}/activo", cupon).cookie(sub)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("activo", false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == %d)].activo", cupon).value(false));
        entregado("100.00");
        mockMvc.perform(post("/api/puntos/cupones/{id}/canjear", cupon).cookie(comprador))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUPON_NO_DISPONIBLE"));
        assertThat(jdbc.queryForList("SELECT accion FROM auditoria_evento WHERE entidad = 'CUPON' AND referencia = ?", String.class,
                String.valueOf(cupon))).containsExactlyInAnyOrder("CUPON_CREADO", "CUPON_DESACTIVADO");
    }

    @Test
    void unCuponProgramadoOFinalizadoNoSeCanjeaYSuEstadoSigueSuPeriodo() throws Exception {
        Map<String, Object> programado = new HashMap<>(Map.of("nombre", "Navidad", "costoPuntos", 5, "vigenciaDias", 30,
                "beneficio", Map.of("tipo", "MONTO", "valor", 5), "disponibleDesde", hoy.plusDays(3).toString(),
                "disponibleHasta", hoy.plusDays(10).toString()));
        JsonNode lista = objectMapper.readTree(mockMvc.perform(post("/api/admin/fidelizacion/cupones").cookie(sub)
                        .contentType(MediaType.APPLICATION_JSON).content(json(programado)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode navidad = null;
        for (JsonNode c : lista) {
            if ("Navidad".equals(c.get("nombre").asText())) {
                navidad = c;
            }
        }
        assertThat(navidad.get("estado").asText()).isEqualTo("PROGRAMADO");
        entregado("100.00");
        mockMvc.perform(get("/api/puntos").cookie(comprador)).andExpect(jsonPath("$.catalogo[?(@.nombre == 'Navidad')]").isEmpty());
        mockMvc.perform(post("/api/puntos/cupones/{id}/canjear", navidad.get("id").asLong()).cookie(comprador))
                .andExpect(status().isNotFound());

        long activo = cupon(Map.of("tipo", "MONTO", "valor", 5), 5);
        mockMvc.perform(put("/api/admin/fidelizacion/cupones/{id}/activo", activo).cookie(sub)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("activo", false))))
                .andExpect(jsonPath("$[?(@.id == %d)].estado", activo).value("FINALIZADO"));

        programado.put("disponibleDesde", hoy.minusDays(1).toString());
        mockMvc.perform(post("/api/admin/fidelizacion/cupones").cookie(sub).contentType(MediaType.APPLICATION_JSON).content(json(programado)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUPON_INICIO_PASADO"));
        programado.put("disponibleDesde", hoy.plusDays(5).toString());
        programado.put("disponibleHasta", hoy.plusDays(4).toString());
        mockMvc.perform(post("/api/admin/fidelizacion/cupones").cookie(sub).contentType(MediaType.APPLICATION_JSON).content(json(programado)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CUPON_FIN_ANTES_DE_INICIO"));
    }

    // --- Estimacion de puntos (carrito, pago y pedido por entregar) ---

    @Test
    void estimaLosPuntosDeUnaCompraConLaCampanaYElNivelQueAlcanzaria() throws Exception {
        mockMvc.perform(get("/api/puntos/estimacion").param("monto", "100").cookie(comprador))
                .andExpect(status().isOk()).andExpect(jsonPath("$.puntos").value(10)).andExpect(jsonPath("$.campana").doesNotExist());
        // Con esta compra llegaria a su primer pedido entregado: el nivel desde 1 ya cuenta
        mockMvc.perform(post("/api/admin/fidelizacion/niveles").cookie(sub).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nombre", "Bienvenida", "pedidosMinimos", 1, "multiplicador", "2")))).andExpect(status().isCreated());
        campana(hoy, hoy.plusDays(2));
        mockMvc.perform(get("/api/puntos/estimacion").param("monto", "100").cookie(comprador))
                .andExpect(jsonPath("$.puntos").value(40))
                .andExpect(jsonPath("$.campana").value("Campaña " + hoy));
        mockMvc.perform(get("/api/puntos/estimacion").param("monto", "-1").cookie(comprador))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MONTO_INVALIDO"));
    }

    @Test
    void unPedidoPagadoMuestraLosPuntosQueGanaraAlEntregarse() throws Exception {
        alCarrito(comprador, productoEnCusco("Café", "25.00", "productor@ccpip.test"), 2);
        long pedido = id(confirmar(Map.of()).andExpect(jsonPath("$.puntosPorGanar").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        pagar(pedido);
        mockMvc.perform(get("/api/pedidos/{id}", pedido).cookie(comprador))
                .andExpect(jsonPath("$.puntosPorGanar.puntos").value(5))
                .andExpect(jsonPath("$.puntos").doesNotExist());
    }

    // --- Codigos de campana ---

    @Test
    void unCodigoDeCampanaVigenteDescuentaYSeUsaHastaSuLimitePorCliente() throws Exception {
        long campana = campana(hoy, hoy.plusDays(5));
        codigo(campana, Map.of("codigo", "cacao10", "beneficio", Map.of("tipo", "PORCENTAJE", "valor", 10, "topeDescuento", 4),
                "usosPorCliente", 1)).andExpect(status().isCreated()).andExpect(jsonPath("$[0].codigo").value("CACAO10"));
        codigo(campana, Map.of("codigo", "CACAO10", "beneficio", Map.of("tipo", "MONTO", "valor", 5)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODIGO_REPETIDO"));

        alCarrito(comprador, productoEnCusco("Cacao", "25.00", "productor@ccpip.test"), 2);
        // 10 % de S/ 50 = S/ 5, con tope de S/ 4
        long pedido = id(confirmar(Map.of("codigo", "Cacao10"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.descuento.origen").value("CODIGO"))
                .andExpect(jsonPath("$.descuento.nombre").value("CACAO10"))
                .andExpect(jsonPath("$.descuento.monto").value(4.0))
                .andReturn().getResponse().getContentAsString());
        pagar(pedido);
        alCarrito(comprador, productoEnCusco("Cacao 2", "25.00", "productor@ccpip.test"), 2);
        confirmar(Map.of("codigo", "CACAO10")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODIGO_YA_USADO"));

        mockMvc.perform(get("/api/admin/fidelizacion/campanas/{id}/codigos", campana).cookie(sub))
                .andExpect(jsonPath("$[0].usos").value(1))
                .andExpect(jsonPath("$[0].descuentoOtorgado").value(4.0));
        mockMvc.perform(get("/api/admin/fidelizacion/campanas/{id}/metricas", campana).cookie(sub))
                .andExpect(jsonPath("$.codigos.usos").value(1))
                .andExpect(jsonPath("$.codigos.descuento").value(4.0));
    }

    @Test
    void unCodigoFueraDeSuCampanaODesactivadoNoVale() throws Exception {
        long programada = campana(hoy.plusDays(3), hoy.plusDays(5));
        codigo(programada, Map.of("codigo", "FUTURO1", "beneficio", Map.of("tipo", "MONTO", "valor", 5)))
                .andExpect(status().isCreated());
        alCarrito(comprador, productoEnCusco("Miel", "25.00", "productor@ccpip.test"), 2);
        descuento(Map.of("codigo", "FUTURO1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODIGO_FUERA_DE_CAMPANA"));

        long activa = campana(hoy, hoy.plusDays(1));
        long codigo = objectMapper.readTree(codigo(activa, Map.of("codigo", "HOY5", "beneficio", Map.of("tipo", "MONTO", "valor", 5)))
                .andReturn().getResponse().getContentAsString()).at("/0/id").asLong();
        descuento(Map.of("codigo", "HOY5")).andExpect(status().isOk()).andExpect(jsonPath("$.monto").value(5.0));
        mockMvc.perform(post("/api/admin/fidelizacion/campanas/{id}/codigos/{c}/desactivar", activa, codigo).cookie(sub))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].activo").value(false));
        descuento(Map.of("codigo", "HOY5")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CODIGO_NO_VALIDO"));
        descuento(Map.of("codigo", "HOY5", "cuponId", 1)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UN_SOLO_DESCUENTO"));
    }

    @Test
    void elSubAdministradorVeSusBeneficiosEnMiHistorialYSoloElCompradorVeSusPuntos() throws Exception {
        long cupon = cupon(Map.of("tipo", "MONTO", "valor", 10), 50);
        mockMvc.perform(post("/api/admin/fidelizacion/niveles").cookie(sub).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nombre", "Bronce", "pedidosMinimos", 3, "multiplicador", "1.2")))).andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/mi-historial").param("tipo", "FIDELIZACION").cookie(sub))
                .andExpect(jsonPath("$.acciones[?(@.objeto == 'CUPON' && @.objetoId == %d)].accion", cupon)
                        .value(org.hamcrest.Matchers.contains("CUPON_CREADO")))
                .andExpect(jsonPath("$.acciones[?(@.objeto == 'NIVEL')].sobre").value(org.hamcrest.Matchers.hasItem("Bronce")));
        mockMvc.perform(get("/api/admin/fidelizacion/beneficios").cookie(sub))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.niveles[0].nombre").value("Bronce"))
                .andExpect(jsonPath("$.cupones[?(@.id == %d)].canjes", cupon).value(0));
        mockMvc.perform(get("/api/puntos").cookie(sub)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/fidelizacion/beneficios").cookie(comprador)).andExpect(status().isForbidden());
    }

    // --- Apoyo ---

    private long usuario() {
        return jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, COMPRADOR);
    }

    /** Un pedido del comprador de {@code total} soles, entregado ahora y con sus puntos acreditados. */
    private long entregado(String total) {
        long producto = productoConLote("Producto beneficios " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", hoy.plusMonths(6));
        long pedido = pedidoConfirmado("BEN-" + UUID.randomUUID().toString().substring(0, 8), "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET usuario_id = ?, total = ?::numeric, estado = 'ENTREGADO' WHERE id = ?", usuario(), total, pedido);
        puntos.acreditarEntrega(pedido);
        return pedido;
    }

    private ResultActions crearCupon(Map<String, Object> beneficio, int costo) throws Exception {
        return mockMvc.perform(post("/api/admin/fidelizacion/cupones").cookie(sub).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nombre", "Cupón de prueba", "costoPuntos", costo, "beneficio", beneficio, "vigenciaDias", 30))));
    }

    private long cupon(Map<String, Object> beneficio, int costo) throws Exception {
        JsonNode lista = objectMapper.readTree(crearCupon(beneficio, costo).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        long mayor = 0;
        for (JsonNode c : lista) {
            mayor = Math.max(mayor, c.get("id").asLong());
        }
        return mayor;
    }

    private long campana(LocalDate desde, LocalDate hasta) throws Exception {
        Map<String, Object> c = new HashMap<>(Map.of("nombre", "Campaña " + desde, "desde", desde.toString(), "hasta", hasta.toString(),
                "puntos", 2, "cadaSoles", "10"));
        return id(mockMvc.perform(post("/api/admin/fidelizacion/campanas").cookie(sub).contentType(MediaType.APPLICATION_JSON)
                .content(json(c))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions codigo(long campana, Map<String, Object> codigo) throws Exception {
        return mockMvc.perform(post("/api/admin/fidelizacion/campanas/{id}/codigos", campana).cookie(sub)
                .contentType(MediaType.APPLICATION_JSON).content(json(codigo)));
    }

    private ResultActions descuento(Map<String, Object> solicitud) throws Exception {
        return mockMvc.perform(post("/api/compra/descuento").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                .content(json(solicitud)));
    }

    private ResultActions confirmar(Map<String, Object> descuento) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>(descuento);
        cuerpo.put("puntoId", puntoCusco());
        cuerpo.put("celularContacto", "987654321");
        return mockMvc.perform(post("/api/pedidos").cookie(comprador).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private void pagar(long pedido) throws Exception {
        mockMvc.perform(post("/api/pedidos/" + pedido + "/pagos").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("claveIdempotencia", UUID.randomUUID(), "tarjeta", Map.of("numero",
                                "4111 1111 1111 1111", "titular", "ANA QUISPE", "mes", 12, "anio", 2030, "cvv", "123")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"));
    }

    private long id(String json) {
        try {
            return objectMapper.readTree(json).at("/id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
