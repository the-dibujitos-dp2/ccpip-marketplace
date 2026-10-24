package pe.ccpip.comision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-90 (RF-68): el superadministrador configura la comision con vigencia, motivo y auditoria.
 * CCPIP-91 (RF-69): la Camara ve la comision y el neto de cada pedido confirmado.
 * CCPIP-431: esa consulta se pagina y se filtra por productor y estado.
 * CCPIP-167 (RF-136): el comprador ve la comision que regia al comprar, aunque despues cambie.
 *
 * <p>Transaccional: los cambios de comision y los pedidos de la prueba no llegan a las demas pruebas,
 * que siguen viendo el 10 % de la semilla de demostracion.
 */
@Transactional
class ComisionTest extends IntegrationTest {

    private static final String SUPER = "sudo@ccpip.test";
    private static final String SUB = "sub_admin@ccpip.test";
    private static final String COMPRADOR = "comprador.comision@ccpip.test";
    private static final String CUSCO = "Centro Regional Cusco";
    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    @Autowired
    private ComisionService service;

    // --- CCPIP-90: configuracion (SA-02) ---

    @Test
    void elSuperadministradorVeLaComisionVigenteYSuHistorial() throws Exception {
        mockMvc.perform(get("/api/admin/comision").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hoy").value(hoy().toString()))
                .andExpect(jsonPath("$.base").value("PRECIO_SIN_ENVIO"))
                .andExpect(jsonPath("$.vigente.porcentaje").value(10))
                .andExpect(jsonPath("$.vigente.estado").value("VIGENTE"))
                .andExpect(jsonPath("$.vigente.registradoPor").value("Super Administrador de Prueba"))
                .andExpect(jsonPath("$.vigente.motivo").isString())
                .andExpect(jsonPath("$.programado").doesNotExist())
                .andExpect(jsonPath("$.historial[0].estado").value("VIGENTE"));
    }

    @Test
    void unCambioDesdeHoyRigeDeInmediatoYQuedaAuditado() throws Exception {
        Cookie sesion = loginAs(SUPER);
        programar(sesion, "12.5", hoy(), "Ajuste aprobado en junta directiva")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vigente.porcentaje").value(12.5))
                .andExpect(jsonPath("$.vigente.vigenteDesde").value(hoy().toString()))
                .andExpect(jsonPath("$.vigente.motivo").value("Ajuste aprobado en junta directiva"))
                .andExpect(jsonPath("$.vigente.registradoPor").value("Super Administrador de Prueba"))
                .andExpect(jsonPath("$.vigente.registradoEn").isNotEmpty())
                .andExpect(jsonPath("$.historial[1].porcentaje").value(10))
                .andExpect(jsonPath("$.historial[1].estado").value("ANTERIOR"));

        // La vista previa del productor (PW-04) y el tablero (SA-01) usan el nuevo porcentaje
        mockMvc.perform(get("/api/catalogos")).andExpect(jsonPath("$.comisionPorcentaje").value(12.5));
        mockMvc.perform(get("/api/admin/tablero").cookie(sesion)).andExpect(jsonPath("$.comision.porcentaje").value(12.5));
    }

    @Test
    void unCambioFuturoQuedaProgramadoYSePuedeCancelar() throws Exception {
        Cookie sesion = loginAs(SUPER);
        LocalDate diciembre = hoy().plusDays(45);
        JsonNode estado = leer(programar(sesion, "12", diciembre, "Ajuste para diciembre")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vigente.porcentaje").value(10))
                .andExpect(jsonPath("$.programado.porcentaje").value(12))
                .andExpect(jsonPath("$.programado.estado").value("PROGRAMADA")));

        // Uno pendiente a la vez
        programar(sesion, "11", diciembre.plusDays(1), "Otro ajuste")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMBIO_YA_PROGRAMADO"));

        long programado = estado.at("/programado/id").asLong();
        mockMvc.perform(post("/api/admin/comision/" + programado + "/cancelar").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programado").doesNotExist())
                .andExpect(jsonPath("$.vigente.porcentaje").value(10))
                .andExpect(jsonPath("$.historial[0].estado").value("CANCELADA"))
                .andExpect(jsonPath("$.historial[0].canceladoPor").value("Super Administrador de Prueba"));

        // El vigente no se cancela; uno inexistente no existe
        long vigente = estado.at("/vigente/id").asLong();
        mockMvc.perform(post("/api/admin/comision/" + vigente + "/cancelar").cookie(sesion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMBIO_NO_CANCELABLE"));
        mockMvc.perform(post("/api/admin/comision/999999999/cancelar").cookie(sesion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAMBIO_NO_ENCONTRADO"));

        // Cancelado, la fecha queda libre para programar de nuevo
        programar(sesion, "11", diciembre, "Ajuste corregido").andExpect(status().isCreated());
    }

    @Test
    void reglasDeUnCambio() throws Exception {
        Cookie sesion = loginAs(SUPER);
        programar(sesion, "12", hoy().minusDays(1), "Con fecha pasada")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIGENCIA_PASADA"));
        programar(sesion, "10", hoy().plusDays(3), "Sin cambio")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISMO_PORCENTAJE"));
        programar(sesion, "12", hoy(), "Primer ajuste del dia").andExpect(status().isCreated());
        programar(sesion, "13", hoy(), "Segundo ajuste del dia")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIGENCIA_OCUPADA"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "-1", "10.555", "150"})
    void elPorcentajeDebeSerValido(String porcentaje) throws Exception {
        programar(loginAs(SUPER), porcentaje, hoy().plusDays(1), "Motivo")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.porcentaje").isString());
    }

    @Test
    void elMotivoEsObligatorio() throws Exception {
        programar(loginAs(SUPER), "12", hoy().plusDays(1), "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.motivo").isString());
    }

    @ParameterizedTest
    @ValueSource(strings = {SUB, "consumidor@ccpip.test", "productor@ccpip.test", "admin_acopio@ccpip.test",
            "distribuidor@ccpip.test"})
    void soloElSuperadministradorConfiguraLaComision(String correo) throws Exception {
        Cookie sesion = loginAs(correo);
        mockMvc.perform(get("/api/admin/comision").cookie(sesion))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        programar(sesion, "12", hoy().plusDays(1), "Motivo")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void sinSesionNoHayComision() throws Exception {
        mockMvc.perform(get("/api/admin/comision")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/comision/pedidos")).andExpect(status().isUnauthorized());
    }

    @Test
    void sinComisionConfiguradaNoSeInventaUnPorcentaje() throws Exception {
        sinComisionConfigurada();
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(get("/api/admin/comision").cookie(sesion))
                .andExpect(jsonPath("$.vigente").doesNotExist())
                .andExpect(jsonPath("$.historial[0].estado").value("CANCELADA"));
        mockMvc.perform(get("/api/admin/tablero").cookie(sesion))
                .andExpect(jsonPath("$.comision.porcentaje").doesNotExist())
                .andExpect(jsonPath("$.comision.estimada").doesNotExist());
        mockMvc.perform(get("/api/catalogos")).andExpect(jsonPath("$.comisionPorcentaje").doesNotExist());

        // El pedido se confirma igual, sin comision copiada
        long pedido = pedidoConfirmadoEnBase(25.00);
        service.copiarAlPedido(pedido);
        assertThat(jdbc.queryForObject("SELECT comision_porcentaje FROM pedido WHERE id = ?", BigDecimal.class, pedido)).isNull();
    }

    // --- CCPIP-91 y CCPIP-167: copia al confirmar ---

    @Test
    void alPagarSeCopiaLaComisionVigenteYUnCambioPosteriorNoLaAltera() throws Exception {
        comisionVigente("12.5", HOY + " - 1");
        Cookie comprador = comprador();
        long canasta = productoEnCusco("Canasta", "25.00", "productor@ccpip.test");
        long aguaje = productoEnCusco("Aguaje", "19.90", "productor2@ccpip.test");
        alCarrito(comprador, canasta, 1);
        alCarrito(comprador, aguaje, 3);
        long pedido = leer(mockMvc.perform(post("/api/pedidos").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("puntoId", puntoCusco(), "celularContacto", "987654321"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comision").doesNotExist())).get("id").asLong();

        mockMvc.perform(post("/api/pedidos/" + pedido + "/pagos").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("claveIdempotencia", UUID.randomUUID(), "tarjeta", Map.of("numero",
                                "4111 1111 1111 1111", "titular", "ANA QUISPE", "mes", 12, "anio", 2030, "cvv", "123")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"))
                // 25.00 x 12.5 % = 3.125 -> 3.13; 59.70 x 12.5 % = 7.4625 -> 7.46. Un redondeo por linea.
                .andExpect(jsonPath("$.pedido.comision.porcentaje").value(12.5))
                .andExpect(jsonPath("$.pedido.comision.base").value("PRECIO_SIN_ENVIO"))
                .andExpect(jsonPath("$.pedido.comision.monto").value(10.59))
                .andExpect(jsonPath("$.pedido.total").value(84.70));

        // La Camara cambia la comision despues: el pedido conserva la suya
        comisionVigente("20", HOY);
        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(comprador))
                .andExpect(jsonPath("$.modalidad").value("RECOJO"))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(25.00))
                .andExpect(jsonPath("$.comision.porcentaje").value(12.5))
                .andExpect(jsonPath("$.comision.monto").value(10.59));

        JsonNode fila = pedidoEnConsulta(loginAs(SUB), numero(pedido));
        assertThat(fila.get("venta").decimalValue()).isEqualByComparingTo("84.70");
        assertThat(fila.get("porcentaje").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(fila.get("comision").decimalValue()).isEqualByComparingTo("10.59");
        assertThat(fila.get("neto").decimalValue()).isEqualByComparingTo("74.11");
        assertThat(parte(fila, "productor@ccpip.test").get("comision").decimalValue()).isEqualByComparingTo("3.13");
        assertThat(parte(fila, "productor@ccpip.test").get("neto").decimalValue()).isEqualByComparingTo("21.87");
        assertThat(parte(fila, "productor2@ccpip.test").get("comision").decimalValue()).isEqualByComparingTo("7.46");
        assertThat(parte(fila, "productor2@ccpip.test").get("neto").decimalValue()).isEqualByComparingTo("52.24");
    }

    @Test
    void laConsultaPorPedidoTotalizaYMarcaLosPedidosSinComision() throws Exception {
        long sinComision = pedidoConfirmadoEnBase(40.00);
        long conComision = pedidoConfirmadoEnBase(30.00);
        service.copiarAlPedido(conComision);
        // Un pedido que espera pago no es una venta confirmada
        long pendiente = pedidoConfirmadoEnBase(99.00);
        jdbc.update("UPDATE pedido SET estado = 'PENDIENTE_PAGO', confirmado_en = NULL WHERE id = ?", pendiente);

        JsonNode respuesta = leer(mockMvc.perform(get("/api/admin/comision/pedidos").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(hoy().withDayOfMonth(1).toString()))
                .andExpect(jsonPath("$.hasta").value(hoy().toString()))
                // Sin datos del comprador
                .andExpect(jsonPath("$..correo").doesNotExist())
                .andExpect(jsonPath("$..personaAutorizada").doesNotExist())
                .andExpect(jsonPath("$..celularContacto").doesNotExist()));

        JsonNode sin = buscar(respuesta, numero(sinComision));
        assertThat(sin.get("porcentaje").isNull()).isTrue();
        assertThat(sin.get("comision").isNull()).isTrue();
        assertThat(sin.get("neto").isNull()).isTrue();
        JsonNode con = buscar(respuesta, numero(conComision));
        assertThat(con.get("comision").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(con.get("neto").decimalValue()).isEqualByComparingTo("27.00");
        assertThat(StreamSupport.stream(respuesta.get("pedidos").spliterator(), false)
                .noneMatch(p -> p.get("numero").asText().equals(numero(pendiente)))).isTrue();

        // CCPIP-431: los totales son de todo el rango, aunque la lista venga paginada
        JsonNode resumen = respuesta.get("resumen");
        assertThat(resumen.get("pedidos").asLong()).isEqualTo(respuesta.get("total").asLong());
        assertThat(resumen.get("sinComision").asInt()).isPositive();
        assertThat(resumen.get("ventas").decimalValue())
                .isEqualByComparingTo(resumen.get("comision").decimalValue().add(resumen.get("neto").decimalValue())
                        .add(ventasSinComisionDelMes()));
    }

    // --- CCPIP-431: paginado y filtros ---

    @Test
    void laConsultaPorPedidoSePaginaDeVeinteEnVeinte() throws Exception {
        Cookie sesion = loginAs(SUPER);
        long yaHay = leer(mockMvc.perform(get("/api/admin/comision/pedidos").cookie(sesion))).get("total").asLong();
        for (long i = yaHay; i < 21; i++) {
            pedidoConfirmadoEnBase(10.00);
        }

        JsonNode primera = leer(mockMvc.perform(get("/api/admin/comision/pedidos").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamano").value(20)));
        long total = primera.get("total").asLong();
        assertThat(total).isGreaterThan(20);
        assertThat(primera.get("totalPaginas").asInt()).isEqualTo((int) ((total + 19) / 20));
        assertThat(primera.get("pedidos").size()).isEqualTo(20);
        assertThat(primera.get("resumen").get("pedidos").asLong()).isEqualTo(total);

        JsonNode segunda = leer(mockMvc.perform(get("/api/admin/comision/pedidos?pagina=1").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagina").value(1)));
        assertThat(segunda.get("pedidos").size()).isEqualTo((int) Math.min(20, total - 20));
        // Ningun pedido se repite entre paginas, y el resumen no cambia al pasar de pagina
        assertThat(numeros(segunda)).doesNotContainAnyElementsOf(numeros(primera));
        assertThat(segunda.get("resumen")).isEqualTo(primera.get("resumen"));

        mockMvc.perform(get("/api/admin/comision/pedidos?pagina=-1").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAGINA_INVALIDA"));
    }

    @Test
    void seFiltraPorProductor() throws Exception {
        long delPrimero = pedidoConfirmadoEnBase(25.00);
        long delSegundo = pedidoConfirmadoEnBase(35.00, "productor2@ccpip.test");
        long productor = idProductor("productor@ccpip.test");
        Cookie sesion = loginAs(SUB);

        JsonNode respuesta = leer(mockMvc.perform(get("/api/admin/comision/pedidos?productor=" + productor).cookie(sesion))
                .andExpect(status().isOk()));
        assertThat(respuesta.get("productor").asLong()).isEqualTo(productor);
        assertThat(numeros(respuesta)).contains(numero(delPrimero)).doesNotContain(numero(delSegundo));
        // Cada pedido listado incluye algo del productor elegido
        assertThat(respuesta.get("pedidos")).allSatisfy(p -> assertThat(p.get("productores"))
                .anySatisfy(pr -> assertThat(pr.get("productorId").asLong()).isEqualTo(productor)));
        // Las opciones del filtro no dependen del filtro aplicado
        assertThat(respuesta.get("productores")).anySatisfy(o -> assertThat(o.get("id").asLong()).isEqualTo(productor))
                .anySatisfy(o -> assertThat(o.get("id").asLong()).isEqualTo(idProductor("productor2@ccpip.test")));
    }

    @Test
    void seFiltraPorEstado() throws Exception {
        long entregado = pedidoConfirmadoEnBase(18.00);
        long confirmado = pedidoConfirmadoEnBase(19.00);
        jdbc.update("UPDATE pedido SET estado = 'ENTREGADO' WHERE id = ?", entregado);
        Cookie sesion = loginAs(SUPER);

        JsonNode respuesta = leer(mockMvc.perform(get("/api/admin/comision/pedidos?estado=entregado").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADO")));
        assertThat(numeros(respuesta)).contains(numero(entregado)).doesNotContain(numero(confirmado));
        assertThat(respuesta.get("pedidos")).allSatisfy(p -> assertThat(p.get("estado").asText()).isEqualTo("ENTREGADO"));

        mockMvc.perform(get("/api/admin/comision/pedidos?estado=CANCELADO").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ESTADO_INVALIDO"));
    }

    @Test
    void seFiltraPorUnPedidoADomicilioListoParaDespacho() throws Exception {
        long despacho = pedidoConfirmadoEnBase(18.00);
        long confirmado = pedidoConfirmadoEnBase(19.00);
        jdbc.update("UPDATE pedido SET modalidad = 'ENVIO', estado = 'LISTO_PARA_DESPACHO' WHERE id = ?", despacho);

        JsonNode respuesta = leer(mockMvc.perform(get("/api/admin/comision/pedidos?estado=LISTO_PARA_DESPACHO").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("LISTO_PARA_DESPACHO")));
        assertThat(numeros(respuesta)).contains(numero(despacho)).doesNotContain(numero(confirmado));
    }

    @Test
    void elRangoDeFechasSeValida() throws Exception {
        Cookie sesion = loginAs(SUB);
        mockMvc.perform(get("/api/admin/comision/pedidos?desde=2026-10-10&hasta=2026-10-01").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        mockMvc.perform(get("/api/admin/comision/pedidos?desde=2024-01-01&hasta=2026-10-01").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_MUY_LARGO"));
        mockMvc.perform(get("/api/admin/comision/pedidos?desde=2026-10-01&hasta=2026-10-31").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value("2026-10-01"))
                .andExpect(jsonPath("$.hasta").value("2026-10-31"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"consumidor@ccpip.test", "productor@ccpip.test", "admin_acopio@ccpip.test",
            "distribuidor@ccpip.test"})
    void laConsultaPorPedidoEsDeLaAdministracionDeLaCamara(String correo) throws Exception {
        mockMvc.perform(get("/api/admin/comision/pedidos").cookie(loginAs(correo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void elRedondeoEsUnicoYHalfUp() {
        assertThat(ComisionService.comision(new BigDecimal("25.00"), new BigDecimal("12.5"))).isEqualByComparingTo("3.13");
        assertThat(ComisionService.comision(new BigDecimal("59.70"), new BigDecimal("12.5"))).isEqualByComparingTo("7.46");
        assertThat(ComisionService.comision(new BigDecimal("0.05"), new BigDecimal("10"))).isEqualByComparingTo("0.01");
        assertThat(ComisionService.comision(new BigDecimal("20.00"), BigDecimal.ZERO)).isEqualByComparingTo("0.00");
    }

    // --- Inmutabilidad en la base (RNF-13 y auditoria) ---
    // Cada una termina con el error de la base: despues de el, la transaccion ya no admite consultas.

    @Test
    void laComisionCopiadaEnElPedidoNoSeEdita() {
        long pedido = pedidoConfirmadoEnBase(30.00);
        service.copiarAlPedido(pedido);
        assertThatThrownBy(() -> jdbc.update("UPDATE pedido SET comision_monto = 0 WHERE id = ?", pedido))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void lasLineasDeUnPedidoConComisionNoSeEditan() {
        long pedido = pedidoConfirmadoEnBase(30.00);
        service.copiarAlPedido(pedido);
        assertThatThrownBy(() -> jdbc.update("UPDATE item_pedido SET precio_unitario = 1, subtotal = 1 WHERE pedido_id = ?", pedido))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void elHistorialDeComisionNoSeEdita() {
        assertThatThrownBy(() -> jdbc.update("UPDATE comision_configuracion SET porcentaje = 1"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void elHistorialDeComisionNoSeBorra() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM comision_configuracion"))
                .isInstanceOf(DataAccessException.class);
    }

    // --- Ayudas ---

    private ResultActions programar(Cookie sesion, String porcentaje, LocalDate desde, String motivo) throws Exception {
        return mockMvc.perform(post("/api/admin/comision").cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("porcentaje", new BigDecimal(porcentaje), "vigenteDesde", desde.toString(),
                        "motivo", motivo))));
    }

    private LocalDate hoy() {
        return jdbc.queryForObject("SELECT " + HOY, LocalDate.class);
    }

    /** Inserta directo en la base una comision que rige desde {@code desde} (SQL), sin la regla de la API. */
    private void comisionVigente(String porcentaje, String desde) {
        jdbc.update("""
                INSERT INTO comision_configuracion (porcentaje, vigente_desde, motivo, registrado_por)
                SELECT ?, %s, 'Prueba', id FROM usuario WHERE correo = ?""".formatted(desde),
                new BigDecimal(porcentaje), SUPER);
    }

    /** Sin ninguna comision vigente: la semilla de demo queda cancelada solo dentro de esta prueba. */
    private void sinComisionConfigurada() {
        jdbc.execute("ALTER TABLE comision_configuracion DISABLE TRIGGER comision_configuracion_sin_update");
        jdbc.update("""
                UPDATE comision_configuracion SET cancelado_en = now(),
                       cancelado_por = (SELECT id FROM usuario WHERE correo = ?)
                WHERE cancelado_en IS NULL""", SUPER);
        jdbc.execute("ALTER TABLE comision_configuracion ENABLE TRIGGER comision_configuracion_sin_update");
    }

    /** Pedido confirmado hoy armado en la base, con una linea de productor@ al precio indicado. */
    private long pedidoConfirmadoEnBase(double venta) {
        return pedidoConfirmadoEnBase(venta, "productor@ccpip.test");
    }

    /** Igual, con una linea del productor indicado. */
    private long pedidoConfirmadoEnBase(double venta, String correoProductor) {
        long producto = productoConLote("Producto comision " + UUID.randomUUID(), "PUBLICADO", null, null, correoProductor);
        Long id = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, ?, 'CONFIRMADO', now()
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, "C-" + System.nanoTime() % 1_000_000_000L, venta);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
                SELECT ?, id, productor_id, 1, ?, ? FROM producto WHERE id = ?""", id, venta, venta, producto);
        return id;
    }

    private Cookie comprador() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Comprador de Prueba', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", COMPRADOR);
        return loginAs(COMPRADOR);
    }

    private long productoEnCusco(String nombre, String precio, String correoProductor) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado, publicado_en)
                SELECT p.id, ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = 'ARTESANIA'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'UNIDAD'), ?, 'PUBLICADO', now()
                FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?
                RETURNING id""", Long.class, nombre + " " + UUID.randomUUID(), new BigDecimal(precio), correoProductor);
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, linea_id FROM producto WHERE id = ?", id, id);
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion)
                SELECT ?, u.id, 'L-K' || ?, 5, 5, current_date - 10 FROM ubicacion u WHERE u.nombre = ?""", id, id, CUSCO);
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) SELECT ?, id, 5 FROM ubicacion WHERE nombre = ?",
                id, CUSCO);
        return id;
    }

    private void alCarrito(Cookie comprador, long productoId, int cantidad) throws Exception {
        mockMvc.perform(post("/api/carrito/items").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productoId", productoId, "cantidad", cantidad))))
                .andExpect(status().isOk());
    }

    private long puntoCusco() {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, CUSCO);
    }

    private String numero(long pedido) {
        return jdbc.queryForObject("SELECT numero FROM pedido WHERE id = ?", String.class, pedido);
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private JsonNode pedidoEnConsulta(Cookie sesion, String numero) throws Exception {
        return buscar(leer(mockMvc.perform(get("/api/admin/comision/pedidos").cookie(sesion)).andExpect(status().isOk())), numero);
    }

    private static JsonNode buscar(JsonNode respuesta, String numero) {
        return StreamSupport.stream(respuesta.get("pedidos").spliterator(), false)
                .filter(p -> numero.equals(p.get("numero").asText())).findFirst().orElseThrow();
    }

    private JsonNode parte(JsonNode fila, String correoProductor) {
        long productor = jdbc.queryForObject(
                "SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?", Long.class, correoProductor);
        return StreamSupport.stream(fila.get("productores").spliterator(), false)
                .filter(p -> p.get("productorId").asLong() == productor).findFirst().orElseThrow();
    }

    /** Ventas del mes en curso confirmadas sin comision, contadas en la base y no en una pagina. */
    private BigDecimal ventasSinComisionDelMes() {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(total), 0) FROM pedido
                WHERE confirmado_en IS NOT NULL AND estado <> 'CANCELADO' AND comision_monto IS NULL
                  AND (confirmado_en AT TIME ZONE 'America/Lima')::date BETWEEN date_trunc('month', %1$s)::date AND %1$s"""
                .formatted(HOY), BigDecimal.class);
    }

    private static List<String> numeros(JsonNode respuesta) {
        return StreamSupport.stream(respuesta.get("pedidos").spliterator(), false).map(p -> p.get("numero").asText()).toList();
    }

    private long idProductor(String correo) {
        return jdbc.queryForObject(
                "SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?", Long.class, correo);
    }
}
