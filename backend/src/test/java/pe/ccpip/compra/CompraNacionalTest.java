package pe.ccpip.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.pago.PasarelaPago.Estado;
import pe.ccpip.pago.PasarelaSimulada;
import pe.ccpip.pago.Tarjeta;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-178 (RF-153) y CCPIP-370 (RF-260): confirmar una compra minorista o mayorista nacional conforme a
 * las reglas aprobadas por el cliente el 05/11/2026 (CCPIP-141): solo en Peru y en soles; mayorista es
 * la tarifa de la oferta desde su cantidad minima (CCPIP-134), sin clasificar al comprador; toda entrega
 * es nacional, en recojo o a domicilio a un distrito del catalogo que el punto cubre.
 *
 * <p>Como {@link CompraFlujoTest}, la base es compartida: comprador propio, productos en Cusco y
 * retirados al terminar.
 */
class CompraNacionalTest extends IntegrationTest {

    private static final String CUSCO = "Centro Regional Cusco";
    private static final String WANCHAQ = "080108";
    private static final String APROBADA = "4111 1111 1111 1111";
    private static final String COMPRADOR = "comprador.nacional@ccpip.test";
    private static final String OTRO = "comprador.nacional.otro@ccpip.test";

    private Cookie comprador;
    private final List<Long> creados = new ArrayList<>();

    @BeforeEach
    void compradorConCarritoVacio() throws Exception {
        for (String correo : List.of(COMPRADOR, OTRO)) {
            jdbc.update("""
                    INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                    SELECT ?, contrasena_hash, 'Comprador Nacional', 1, true FROM usuario
                    WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", correo);
        }
        jdbc.update("DELETE FROM item_carrito WHERE carrito_id IN (SELECT c.id FROM carrito c JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = ?)", COMPRADOR);
        jdbc.update("INSERT INTO tarifa_envio (ubigeo, monto) VALUES (?, 12.00) ON CONFLICT (ubigeo) DO UPDATE SET monto = 12.00", WANCHAQ);
        jdbc.update("UPDATE cobertura_entrega SET activa = true WHERE ubigeo = ?", WANCHAQ);
        comprador = loginAs(COMPRADOR);
    }

    @AfterEach
    void retirarProductosDePrueba() {
        creados.forEach(id -> jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", id));
        creados.clear();
    }

    /**
     * Oferta de productor@ccpip.test con {@code unidades} en un lote vigente de Cusco: minorista a S/ 25.00
     * y, si {@code modalidad} no es MINORISTA, mayorista a S/ 20.00 desde 10 unidades.
     */
    private long oferta(String modalidad, int unidades) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado,
                                      publicado_en, modalidad, precio_mayorista, cantidad_minima_mayorista)
                SELECT p.id, ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = 'ARTESANIA'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'UNIDAD'), 25.00, 'PUBLICADO', now(), ?,
                       CASE WHEN ? = 'MINORISTA' THEN NULL ELSE 20.00 END, CASE WHEN ? = 'MINORISTA' THEN NULL ELSE 10 END
                FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor@ccpip.test'
                RETURNING id""", Long.class, "Canasta " + modalidad + " " + UUID.randomUUID(), modalidad, modalidad, modalidad);
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, linea_id FROM producto WHERE id = ?", id, id);
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion)
                SELECT ?, u.id, 'L-N' || ?, ?, ?, current_date - 10 FROM ubicacion u WHERE u.nombre = ?""",
                id, id, unidades, unidades, CUSCO);
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) SELECT ?, id, ? FROM ubicacion WHERE nombre = ?",
                id, unidades, CUSCO);
        creados.add(id);
        return id;
    }

    private ResultActions alCarrito(long productoId, int cantidad) throws Exception {
        return mockMvc.perform(post("/api/carrito/items").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("productoId", productoId, "cantidad", cantidad))));
    }

    private long cusco() {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, CUSCO);
    }

    private ResultActions recojo(Cookie quien) throws Exception {
        return mockMvc.perform(post("/api/pedidos").cookie(quien).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("puntoId", cusco(), "celularContacto", "987654321"))));
    }

    private ResultActions aDomicilio(String ubigeo) throws Exception {
        Map<String, Object> destino = new HashMap<>();
        destino.put("ubigeo", ubigeo);
        destino.put("direccion", "Av. de la Cultura 1234, dpto. 5");
        destino.put("referencia", "Frente al grifo");
        destino.put("contactoNombre", "María Quispe");
        destino.put("contactoCelular", "987654321");
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("puntoId", cusco());
        cuerpo.put("modalidad", "ENVIO");
        cuerpo.put("entrega", destino);
        return mockMvc.perform(post("/api/pedidos").cookie(comprador).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private JsonNode creado(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions pagar(long pedidoId) throws Exception {
        Map<String, Object> tarjeta = Map.of("numero", APROBADA, "titular", "ANA QUISPE", "mes", 12, "anio", 2030, "cvv", "123");
        return mockMvc.perform(post("/api/pedidos/" + pedidoId + "/pagos").cookie(comprador)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("claveIdempotencia", UUID.randomUUID(), "tarjeta", tarjeta))));
    }

    private long pedidosDe(String correo) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pedido pe JOIN usuario u ON u.id = pe.usuario_id
                WHERE u.correo = ? AND pe.estado <> 'CANCELADO'""", Long.class, correo);
    }

    // --- Las combinaciones nacionales: minorista y mayorista, en recojo y a domicilio ---

    @Test
    void minorista_con_recojo_se_confirma_en_soles_a_precio_minorista() throws Exception {
        long producto = oferta("MINORISTA", 5);
        alCarrito(producto, 2).andExpect(status().isOk());

        JsonNode pedido = creado(recojo(comprador)
                .andExpect(jsonPath("$.modalidad").value("RECOJO"))
                .andExpect(jsonPath("$.moneda").value("PEN"))
                .andExpect(jsonPath("$.lineas[0].tarifa").value("MINORISTA"))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(25.00))
                .andExpect(jsonPath("$.totalAPagar").value(50.00)));
        long id = pedido.get("id").asLong();

        pagar(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADO"))
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pedido.pagos[0].monto").value(50.00));
        assertThat(jdbc.queryForObject("SELECT moneda FROM pago WHERE pedido_id = ?", String.class, id)).isEqualTo("PEN");
        assertThat(jdbc.queryForObject("SELECT tarifa FROM item_pedido WHERE pedido_id = ?", String.class, id)).isEqualTo("MINORISTA");
    }

    @Test
    void mayorista_a_domicilio_cobra_el_precio_mayorista_mas_el_envio_del_distrito() throws Exception {
        long producto = oferta("MAYORISTA", 30);
        alCarrito(producto, 10).andExpect(status().isOk());

        long id = creado(aDomicilio(WANCHAQ)
                .andExpect(jsonPath("$.modalidad").value("ENVIO"))
                .andExpect(jsonPath("$.moneda").value("PEN"))
                .andExpect(jsonPath("$.entrega.ubigeo").value(WANCHAQ))
                .andExpect(jsonPath("$.lineas[0].tarifa").value("MAYORISTA"))
                .andExpect(jsonPath("$.lineas[0].precioUnitario").value(20.00))
                .andExpect(jsonPath("$.total").value(200.00))
                .andExpect(jsonPath("$.costoEnvio").value(12.00))
                .andExpect(jsonPath("$.totalAPagar").value(212.00))).get("id").asLong();

        pagar(id).andExpect(jsonPath("$.estado").value("APROBADO"))
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pedido.pagos[0].monto").value(212.00));
        assertThat(jdbc.queryForObject("SELECT cantidad FROM stock WHERE producto_id = ?", BigDecimal.class, producto))
                .isEqualByComparingTo("20");
    }

    @Test
    void minorista_a_domicilio_y_mayorista_con_recojo_tambien_se_confirman() throws Exception {
        long minorista = oferta("MINORISTA", 5);
        alCarrito(minorista, 1).andExpect(status().isOk());
        long id = creado(aDomicilio(WANCHAQ)
                .andExpect(jsonPath("$.lineas[0].tarifa").value("MINORISTA"))
                .andExpect(jsonPath("$.totalAPagar").value(37.00))).get("id").asLong();
        pagar(id).andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"));

        long mayorista = oferta("MAYORISTA", 30);
        alCarrito(mayorista, 12).andExpect(status().isOk());
        long otro = creado(recojo(comprador)
                .andExpect(jsonPath("$.lineas[0].tarifa").value("MAYORISTA"))
                .andExpect(jsonPath("$.totalAPagar").value(240.00))).get("id").asLong();
        pagar(otro).andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"));
    }

    @Test
    void un_mismo_pedido_mezcla_lineas_minoristas_y_mayoristas_y_el_productor_ve_cada_modalidad() throws Exception {
        long minorista = oferta("MINORISTA", 5);
        long ambas = oferta("AMBAS", 30);
        long ambasBajoMinimo = oferta("AMBAS", 30);
        alCarrito(minorista, 2).andExpect(status().isOk());
        alCarrito(ambas, 10).andExpect(status().isOk());
        // Con ambas modalidades, bajo el minimo se compra al precio minorista (CCPIP-134)
        alCarrito(ambasBajoMinimo, 3).andExpect(status().isOk());

        JsonNode pedido = creado(recojo(comprador)
                .andExpect(jsonPath("$.lineas.length()").value(3))
                .andExpect(jsonPath(linea(minorista, "tarifa")).value("MINORISTA"))
                .andExpect(jsonPath(linea(ambas, "tarifa")).value("MAYORISTA"))
                .andExpect(jsonPath(linea(ambas, "precioUnitario")).value(20.00))
                .andExpect(jsonPath(linea(ambasBajoMinimo, "tarifa")).value("MINORISTA"))
                .andExpect(jsonPath(linea(ambasBajoMinimo, "precioUnitario")).value(25.00))
                .andExpect(jsonPath("$.total").value(325.00)));
        long id = pedido.get("id").asLong();
        String numero = pedido.get("numero").asText();
        pagar(id).andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"));

        String delPedido = "$.items[?(@.numero == '" + numero + "')]";
        mockMvc.perform(get("/api/productor/pedidos").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath(delPedido + linea(minorista, "tarifa").substring(1)).value("MINORISTA"))
                .andExpect(jsonPath(delPedido + linea(ambas, "tarifa").substring(1)).value("MAYORISTA"));
    }

    /** Un campo de la linea de ese producto, sin depender del orden de las lineas. */
    private static String linea(long productoId, String campo) {
        return "$.lineas[?(@.productoId == " + productoId + ")]." + campo;
    }

    // --- Rechazos ---

    @Test
    void una_oferta_solo_mayorista_bajo_su_minimo_no_se_confirma() throws Exception {
        long producto = oferta("MAYORISTA", 30);
        alCarrito(producto, 5).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MINIMO_MAYORISTA"));

        // Si el minimo sube despues de agregarla, el carrito la marca y la compra no se confirma
        alCarrito(producto, 10).andExpect(status().isOk());
        jdbc.update("UPDATE producto SET cantidad_minima_mayorista = 15 WHERE id = ?", producto);
        long antes = pedidosDe(COMPRADOR);
        recojo(comprador).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARRITO_CON_PROBLEMAS"));
        assertThat(pedidosDe(COMPRADOR)).isEqualTo(antes);
    }

    @Test
    void solo_se_entrega_a_un_distrito_nacional_que_el_punto_cubre() throws Exception {
        long producto = oferta("MINORISTA", 5);
        alCarrito(producto, 1).andExpect(status().isOk());
        long antes = pedidosDe(COMPRADOR);

        // Un codigo que no esta en el catalogo territorial del INEI (p. ej. un destino en el extranjero)
        aDomicilio("999999").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIN_COBERTURA"));
        aDomicilio("US-NY").andExpect(status().is4xxClientError());
        assertThat(pedidosDe(COMPRADOR)).isEqualTo(antes);
    }

    @Test
    void solo_el_comprador_confirma_y_ve_su_pedido() throws Exception {
        long producto = oferta("MINORISTA", 5);
        alCarrito(producto, 1).andExpect(status().isOk());
        long id = creado(recojo(comprador)).get("id").asLong();

        mockMvc.perform(post("/api/pedidos").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("puntoId", cusco())))).andExpect(status().isUnauthorized());
        recojo(loginAs("productor@ccpip.test")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pedidos/" + id).cookie(loginAs(OTRO))).andExpect(status().isNotFound());
    }

    @Test
    void la_base_y_la_pasarela_solo_admiten_soles() throws Exception {
        long producto = oferta("MINORISTA", 5);
        alCarrito(producto, 1).andExpect(status().isOk());
        long id = creado(recojo(comprador)).get("id").asLong();

        assertThatThrownBy(() -> jdbc.update("UPDATE pedido SET moneda = 'USD' WHERE id = ?", id))
                .hasMessageContaining("pedido_moneda_soles");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO pago (pedido_id, clave_idempotencia, monto, pasarela, marca_tarjeta, moneda)
                VALUES (?, ?, 25.00, 'SIMULADA', 'VISA', 'USD')""", id, UUID.randomUUID()))
                .hasMessageContaining("pago_moneda_soles");

        Tarjeta tarjeta = new Tarjeta(APROBADA, "ANA QUISPE", 12, 2030, "123");
        PasarelaSimulada pasarela = new PasarelaSimulada();
        assertThat(pasarela.cobrar("CCP-X", new BigDecimal("25.00"), "USD", tarjeta).estado()).isEqualTo(Estado.RECHAZADO);
        assertThat(pasarela.cobrar("CCP-X", new BigDecimal("25.00"), "PEN", tarjeta).estado()).isEqualTo(Estado.APROBADO);
    }
}
