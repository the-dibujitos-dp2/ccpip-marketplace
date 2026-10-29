package pe.ccpip.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Flujo de compra de punta a punta (CCPIP-157, CCPIP-62, CCPIP-163, CCPIP-64): carrito, recojo,
 * pago con la pasarela simulada y confirmacion. La base es compartida por toda la suite, asi que no
 * deja rastro que otras pruebas cuenten: un comprador propio (su carrito no es el de
 * consumidor@ccpip.test), productos con stock en Cusco (no en el punto de Lima que revisan las
 * pruebas del acopio) y retirados al terminar (no aparecen en el catalogo).
 */
class CompraFlujoTest extends IntegrationTest {

    private static final String CUSCO = "Centro Regional Cusco";
    private static final String APROBADA = "4111 1111 1111 1111";
    private static final String FONDOS_INSUFICIENTES = "4000000000000002";

    private static final String COMPRADOR = "comprador.flujo@ccpip.test";

    private Cookie comprador;
    private final List<Long> creados = new ArrayList<>();

    @BeforeEach
    void compradorConCarritoVacio() throws Exception {
        crearConsumidor(COMPRADOR);
        jdbc.update("DELETE FROM item_carrito WHERE carrito_id IN (SELECT c.id FROM carrito c JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = ?)", COMPRADOR);
        comprador = loginAs(COMPRADOR);
    }

    @AfterEach
    void retirarProductosDePrueba() {
        creados.forEach(id -> jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", id));
        creados.clear();
    }

    /** Consumidor con la clave de demostracion, para no tocar el carrito de las cuentas sembradas. */
    private void crearConsumidor(String correo) {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Comprador de Prueba', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", correo);
    }

    /** Producto publicado y autorizado de productor@ccpip.test con {@code unidades} en un lote vigente de Cusco. */
    private long productoEnCusco(String nombre, int unidades) {
        return productoEnCusco(nombre + " " + UUID.randomUUID(), unidades, "productor@ccpip.test");
    }

    private long productoEnCusco(String nombre, int unidades, String correoProductor) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado, publicado_en)
                SELECT p.id, ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = 'ARTESANIA'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'UNIDAD'), 25.00, 'PUBLICADO', now()
                FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?
                RETURNING id""", Long.class, nombre, correoProductor);
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, linea_id FROM producto WHERE id = ?", id, id);
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion)
                SELECT ?, u.id, 'L-C' || ?, ?, ?, current_date - 10 FROM ubicacion u WHERE u.nombre = ?""",
                id, id, unidades, unidades, CUSCO);
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) SELECT ?, id, ? FROM ubicacion WHERE nombre = ?",
                id, unidades, CUSCO);
        creados.add(id);
        return id;
    }

    private void alCarrito(long productoId, int cantidad) throws Exception {
        mockMvc.perform(post("/api/carrito/items").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productoId", productoId, "cantidad", cantidad))))
                .andExpect(status().isOk());
    }

    private long puntoCusco() {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, CUSCO);
    }

    private ResultActions confirmar(long puntoId, String celular) throws Exception {
        return mockMvc.perform(post("/api/pedidos").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("puntoId", puntoId, "personaAutorizada", "Rosa Quispe", "celularContacto", celular))));
    }

    private long pedidoPendiente(long productoId, int cantidad) throws Exception {
        alCarrito(productoId, cantidad);
        JsonNode pedido = objectMapper.readTree(confirmar(puntoCusco(), "987 654 321")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return pedido.get("id").asLong();
    }

    private ResultActions pagar(long pedidoId, UUID clave, String numero, String cvv) throws Exception {
        Map<String, Object> tarjeta = Map.of("numero", numero, "titular", "ANA QUISPE", "mes", 12, "anio", 2030, "cvv", cvv);
        return mockMvc.perform(post("/api/pedidos/" + pedidoId + "/pagos").cookie(comprador)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("claveIdempotencia", clave, "tarjeta", tarjeta))));
    }

    private BigDecimal stock(long productoId) {
        return jdbc.queryForObject("SELECT cantidad FROM stock WHERE producto_id = ?", BigDecimal.class, productoId);
    }

    @Test
    void ofertas_del_mismo_nombre_conservan_su_productor_hasta_pago_e_inventario() throws Exception {
        String nombre = "Canasta " + UUID.randomUUID();
        long primera = productoEnCusco(nombre, 5, "productor@ccpip.test");
        long segunda = productoEnCusco(nombre, 8, "productor2@ccpip.test");
        long productorPrimero = productorDe(primera);
        long productorSegundo = productorDe(segunda);
        alCarrito(primera, 2);
        alCarrito(segunda, 3);
        mockMvc.perform(get("/api/carrito").cookie(comprador))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].productoId").value(primera))
                .andExpect(jsonPath("$.items[0].tienda.id").value(productorPrimero))
                .andExpect(jsonPath("$.items[1].productoId").value(segunda))
                .andExpect(jsonPath("$.items[1].tienda.id").value(productorSegundo));

        JsonNode pedido = objectMapper.readTree(confirmar(puntoCusco(), "987654321")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lineas[0].productorId").value(productorPrimero))
                .andExpect(jsonPath("$.lineas[1].productorId").value(productorSegundo))
                .andReturn().getResponse().getContentAsString());
        long id = pedido.get("id").asLong();
        pagar(id, UUID.randomUUID(), APROBADA, "123")
                .andExpect(jsonPath("$.pedido.lineas[0].productoId").value(primera))
                .andExpect(jsonPath("$.pedido.lineas[0].productorId").value(productorPrimero))
                .andExpect(jsonPath("$.pedido.lineas[1].productoId").value(segunda))
                .andExpect(jsonPath("$.pedido.lineas[1].productorId").value(productorSegundo));
        assertThat(stock(primera)).isEqualByComparingTo("3");
        assertThat(stock(segunda)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM movimiento_inventario m
                JOIN item_pedido i ON i.id = m.item_pedido_id JOIN lote l ON l.id = m.lote_id
                WHERE m.pedido_id = ? AND m.tipo = 'SALIDA_VENTA'
                  AND m.producto_id = i.producto_id AND l.producto_id = i.producto_id""", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    void no_sustituye_una_oferta_agotada_por_otro_productor() throws Exception {
        String nombre = "Canasta " + UUID.randomUUID();
        long elegida = productoEnCusco(nombre, 5, "productor@ccpip.test");
        long alternativa = productoEnCusco(nombre, 10, "productor2@ccpip.test");
        long pedido = pedidoPendiente(elegida, 2);
        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE producto_id = ?", elegida);
        jdbc.update("UPDATE stock SET cantidad = 0 WHERE producto_id = ?", elegida);
        // CCPIP-60: se detecta antes de cobrar y no se completa con la oferta de otro productor
        pagar(pedido, UUID.randomUUID(), APROBADA, "123")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUNTO_SIN_EXISTENCIAS"));
        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(comprador))
                .andExpect(jsonPath("$.lineas[0].productoId").value(elegida))
                .andExpect(jsonPath("$.lineas[0].productorId").value(productorDe(elegida)))
                .andExpect(jsonPath("$.pagos", hasSize(0)));
        assertThat(stock(alternativa)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE pedido_id = ?",
                Integer.class, pedido)).isZero();
    }

    @Test
    void la_base_impide_cambiar_el_productor_de_la_oferta_o_del_pedido() throws Exception {
        long elegida = productoEnCusco("Canasta", 5);
        long otra = productoEnCusco("Canasta", 5, "productor2@ccpip.test");
        long pedido = pedidoPendiente(elegida, 1);
        long otroProductor = productorDe(otra);
        assertThatThrownBy(() -> jdbc.update("UPDATE producto SET productor_id = ? WHERE id = ?", otroProductor, elegida))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE item_pedido SET productor_id = ? WHERE pedido_id = ?", otroProductor, pedido))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long productorDe(long producto) {
        return jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, producto);
    }

    @Test
    void solo_se_ofrecen_los_puntos_con_existencias_para_todo_el_carrito() throws Exception {
        alCarrito(productoEnCusco("Canasta", 5), 2);

        mockMvc.perform(get("/api/compra/puntos").cookie(comprador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].nombre").value(CUSCO));
    }

    @Test
    void confirmar_crea_el_pedido_en_espera_de_pago_sin_tocar_el_stock() throws Exception {
        long producto = productoEnCusco("Canasta", 5);
        alCarrito(producto, 2);

        confirmar(puntoCusco(), "+51 987-654-321")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").value(org.hamcrest.Matchers.startsWith("CCP-")))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_PAGO"))
                .andExpect(jsonPath("$.total").value(50.00))
                .andExpect(jsonPath("$.celularContacto").value("987654321"))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(25.00));

        assertThat(stock(producto)).isEqualByComparingTo("5");
    }

    /**
     * CCPIP-134 (RF-108): desde el minimo, el pedido copia el precio mayorista que resolvio el carrito, y
     * editar la oferta despues no altera el pedido confirmado (RNF-13).
     */
    @Test
    void el_pedido_confirmado_copia_el_precio_mayorista() throws Exception {
        long producto = productoEnCusco("Canasta", 30);
        jdbc.update("""
                UPDATE producto SET modalidad = 'AMBAS', precio_mayorista = 20.00, cantidad_minima_mayorista = 12
                WHERE id = ?""", producto);
        alCarrito(producto, 12);

        JsonNode pedido = objectMapper.readTree(confirmar(puntoCusco(), "987654321")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(240.00))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(20.00))
                .andExpect(jsonPath("$.lineas[0].subtotal").value(240.00))
                .andReturn().getResponse().getContentAsString());
        long id = pedido.get("id").asLong();
        pagar(id, UUID.randomUUID(), APROBADA, "123")
                .andExpect(jsonPath("$.estado").value("APROBADO"))
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pedido.pagos[0].monto").value(240.00));

        jdbc.update("UPDATE producto SET precio_mayorista = 22.00 WHERE id = ?", producto);
        mockMvc.perform(get("/api/pedidos/" + id).cookie(comprador))
                .andExpect(jsonPath("$.total").value(240.00))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(20.00));
    }

    @Test
    void un_pago_aprobado_confirma_descuenta_y_vacia_el_carrito() throws Exception {
        long producto = productoEnCusco("Canasta", 5);
        long pedido = pedidoPendiente(producto, 2);

        pagar(pedido, UUID.randomUUID(), APROBADA, "123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADO"))
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pedido.pagos[0].marca").value("VISA"));

        assertThat(stock(producto)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE pedido_id = ? AND tipo = 'SALIDA_VENTA'",
                Long.class, pedido)).isEqualTo(1);
        mockMvc.perform(get("/api/carrito").cookie(comprador)).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(comprador))
                .andExpect(jsonPath("$.confirmadoEn").isNotEmpty())
                .andExpect(jsonPath("$.punto.nombre").value(CUSCO));
    }

    @Test
    void un_rechazo_no_confirma_ni_descuenta_y_se_puede_reintentar() throws Exception {
        long producto = productoEnCusco("Canasta", 5);
        long pedido = pedidoPendiente(producto, 1);

        pagar(pedido, UUID.randomUUID(), FONDOS_INSUFICIENTES, "123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADO"))
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("fondos insuficientes")))
                .andExpect(jsonPath("$.pedido.estado").value("PENDIENTE_PAGO"));
        assertThat(stock(producto)).isEqualByComparingTo("5");

        pagar(pedido, UUID.randomUUID(), APROBADA, "123").andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"));

        mockMvc.perform(get("/api/pedidos/" + pedido + "/pagos").cookie(comprador))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].estado").value("RECHAZADO"))
                .andExpect(jsonPath("$[1].estado").value("APROBADO"));
    }

    @Test
    void si_la_autorizacion_se_revoca_antes_de_pagar_no_se_cobra_y_el_pedido_se_cancela() throws Exception {
        long producto = productoEnCusco("Canasta", 5);
        long pedido = pedidoPendiente(producto, 1);
        // CCPIP-270: entre crear el pedido y pagarlo, la Camara revoca la autorizacion del producto
        jdbc.update("UPDATE autorizacion_producto SET estado = 'REVOCADA', motivo = 'Evidencia vencida' WHERE producto_id = ?",
                producto);

        pagar(pedido, UUID.randomUUID(), APROBADA, "123")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENTA_PAUSADA"));

        assertThat(stock(producto)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pago WHERE pedido_id = ?", Integer.class, pedido)).isZero();
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido)).isEqualTo("CANCELADO");
        // El producto sigue en el carrito, marcado como venta pausada
        mockMvc.perform(get("/api/carrito").cookie(comprador))
                .andExpect(jsonPath("$.items[0].estado").value("VENTA_PAUSADA"));
        // Y no se puede volver a confirmar mientras siga pausado
        confirmar(puntoCusco(), "987 654 321")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARRITO_CON_PROBLEMAS"));
    }

    @Test
    void el_mismo_intento_enviado_dos_veces_no_cobra_dos_veces() throws Exception {
        long producto = productoEnCusco("Canasta", 5);
        long pedido = pedidoPendiente(producto, 1);
        UUID clave = UUID.randomUUID();

        pagar(pedido, clave, APROBADA, "123").andExpect(jsonPath("$.estado").value("APROBADO"));
        pagar(pedido, clave, APROBADA, "123").andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("APROBADO"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pago WHERE pedido_id = ?", Long.class, pedido)).isEqualTo(1);
        assertThat(stock(producto)).isEqualByComparingTo("4");
    }

    @Test
    void un_pedido_pagado_no_se_vuelve_a_pagar() throws Exception {
        long pedido = pedidoPendiente(productoEnCusco("Canasta", 5), 1);
        pagar(pedido, UUID.randomUUID(), APROBADA, "123");

        pagar(pedido, UUID.randomUUID(), APROBADA, "123")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_PAGABLE"));
    }

    @Test
    void si_el_stock_se_agoto_antes_de_pagar_no_se_cobra_y_se_cancela() throws Exception {
        long producto = productoEnCusco("Canasta", 5);
        long pedido = pedidoPendiente(producto, 2);
        // Otro comprador se llevo casi todo entre la confirmacion y el pago. CCPIP-60: se ve antes de cobrar;
        // la carrera durante el cobro (pago ANULADO y devuelto) esta en FactibilidadAntesDePagarTest
        jdbc.update("UPDATE lote SET cantidad_actual = 1 WHERE producto_id = ?", producto);
        jdbc.update("UPDATE stock SET cantidad = 1 WHERE producto_id = ?", producto);

        pagar(pedido, UUID.randomUUID(), APROBADA, "123")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUNTO_SIN_EXISTENCIAS"));
        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(comprador))
                .andExpect(jsonPath("$.estado").value("CANCELADO"))
                .andExpect(jsonPath("$.pagos", hasSize(0)));
        assertThat(stock(producto)).isEqualByComparingTo("1");
    }

    @Test
    void una_tarjeta_invalida_se_rechaza_sin_registrar_intento() throws Exception {
        long pedido = pedidoPendiente(productoEnCusco("Canasta", 5), 1);

        pagar(pedido, UUID.randomUUID(), "4111 1111 1111 1112", "12")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TARJETA_INVALIDA"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pago WHERE pedido_id = ?", Long.class, pedido)).isZero();
    }

    @Test
    void un_celular_invalido_no_crea_el_pedido() throws Exception {
        alCarrito(productoEnCusco("Canasta", 5), 1);

        confirmar(puntoCusco(), "12345").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CELULAR_INVALIDO"));
    }

    @Test
    void un_punto_sin_existencias_no_se_puede_elegir() throws Exception {
        alCarrito(productoEnCusco("Canasta", 5), 1);
        long lima = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = 'Centro Nacional Lima'", Long.class);

        confirmar(lima, "987654321").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUNTO_SIN_EXISTENCIAS"));
    }

    @Test
    void un_carrito_vacio_no_se_confirma() throws Exception {
        confirmar(puntoCusco(), "987654321").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARRITO_VACIO"));
    }

    @Test
    void el_pedido_de_otro_comprador_no_existe() throws Exception {
        long pedido = pedidoPendiente(productoEnCusco("Canasta", 5), 1);
        Cookie otro = loginAs("productor@ccpip.test");

        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(otro)).andExpect(status().isForbidden());
        crearConsumidor("otro.comprador@ccpip.test");
        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(loginAs("otro.comprador@ccpip.test")))
                .andExpect(status().isNotFound());
    }
}
