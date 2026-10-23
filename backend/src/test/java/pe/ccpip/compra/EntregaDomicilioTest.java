package pe.ccpip.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-59 (RF-40): el comprador elige entrega a domicilio desde un punto de acopio habilitado. La
 * entrega depende de la cobertura del punto en el distrito de destino y de las existencias para todo el
 * carrito; el pedido registra su entrega y sale del punto de acopio, no del productor. Como en
 * {@link CompraFlujoTest}, la base es compartida: comprador propio, productos en Cusco y retirados al terminar.
 */
class EntregaDomicilioTest extends IntegrationTest {

    private static final String CUSCO = "Centro Regional Cusco";
    private static final String WANCHAQ = "080108";
    private static final String MIRAFLORES = "150122";
    private static final String COMPRADOR = "comprador.domicilio@ccpip.test";
    private static final String APROBADA = "4111 1111 1111 1111";

    private Cookie comprador;
    private long pedidosAntes;
    private final List<Long> creados = new ArrayList<>();

    @BeforeEach
    void compradorConCarritoVacio() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Comprador a Domicilio', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", COMPRADOR);
        jdbc.update("DELETE FROM item_carrito WHERE carrito_id IN (SELECT c.id FROM carrito c JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = ?)", COMPRADOR);
        comprador = loginAs(COMPRADOR);
        pedidosAntes = pedidosDelComprador();
    }

    @AfterEach
    void limpiar() {
        creados.forEach(id -> jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", id));
        creados.clear();
        jdbc.update("UPDATE cobertura_entrega SET activa = true WHERE ubigeo = ?", WANCHAQ);
    }

    /** Producto de productor@ccpip.test, a S/ 25.00, con {@code unidades} en un lote vigente del punto de Cusco. */
    private long productoEnCusco(int unidades) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado, publicado_en)
                SELECT p.id, ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = 'ARTESANIA'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'UNIDAD'), 25.00, 'PUBLICADO', now()
                FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor@ccpip.test'
                RETURNING id""", Long.class, "Canasta a domicilio " + UUID.randomUUID());
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, linea_id FROM producto WHERE id = ?", id, id);
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion)
                SELECT ?, u.id, 'L-D' || ?, ?, ?, current_date - 10 FROM ubicacion u WHERE u.nombre = ?""",
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

    private long punto(String nombre) {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, nombre);
    }

    private static Map<String, Object> destino(String ubigeo) {
        Map<String, Object> d = new HashMap<>();
        d.put("ubigeo", ubigeo);
        d.put("direccion", "Av. de la Cultura 1234, dpto. 5");
        d.put("referencia", "Frente al grifo");
        d.put("contactoNombre", "María Quispe");
        d.put("contactoCelular", "+51 987-654-321");
        return d;
    }

    private Map<String, Object> aDomicilio(Map<String, Object> destino) {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("puntoId", punto(CUSCO));
        cuerpo.put("modalidad", "ENVIO");
        cuerpo.put("personaAutorizada", "Rosa Quispe");
        cuerpo.put("entrega", destino);
        return cuerpo;
    }

    private ResultActions pedir(Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post("/api/pedidos").cookie(comprador).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private long pedidosDelComprador() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pedido pe JOIN usuario u ON u.id = pe.usuario_id
                WHERE u.correo = ? AND pe.estado <> 'CANCELADO'""", Long.class, COMPRADOR);
    }

    // --- Que se ofrece ---

    @Test
    void ofreceLosDistritosConLosPuntosQueTienenTodoElCarrito() throws Exception {
        alCarrito(productoEnCusco(5), 2);
        // Lima tambien cubre Miraflores, pero solo el punto de Cusco tiene existencias
        mockMvc.perform(get("/api/compra/distritos").cookie(comprador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.distrito.ubigeo == '" + WANCHAQ + "')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.distrito.ubigeo == '" + WANCHAQ + "')].distrito.distrito").value("Wanchaq"))
                .andExpect(jsonPath("$[?(@.distrito.ubigeo == '" + WANCHAQ + "')].distrito.departamento").value("Cusco"))
                .andExpect(jsonPath("$[?(@.distrito.ubigeo == '" + WANCHAQ + "')].puntos[0].nombre").value(CUSCO))
                .andExpect(jsonPath("$[?(@.distrito.ubigeo == '" + MIRAFLORES + "')]", hasSize(0)));
    }

    @Test
    void unaZonaDesactivadaNoSeOfreceNiSeAcepta() throws Exception {
        alCarrito(productoEnCusco(5), 1);
        jdbc.update("UPDATE cobertura_entrega SET activa = false WHERE ubigeo = ? AND ubicacion_id = ?", WANCHAQ, punto(CUSCO));
        mockMvc.perform(get("/api/compra/distritos").cookie(comprador))
                .andExpect(jsonPath("$[?(@.distrito.ubigeo == '" + WANCHAQ + "')]", hasSize(0)));
        pedir(aDomicilio(destino(WANCHAQ)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIN_COBERTURA"));
        assertThat(pedidosDelComprador()).isEqualTo(pedidosAntes);
    }

    @Test
    void conElCarritoVacioNoHayEntrega() throws Exception {
        mockMvc.perform(get("/api/compra/distritos").cookie(comprador))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARRITO_VACIO"));
    }

    // --- Confirmar ---

    @Test
    void confirmaElPedidoADomicilioYRegistraSuEntrega() throws Exception {
        long producto = productoEnCusco(5);
        alCarrito(producto, 2);
        pedir(aDomicilio(destino(WANCHAQ)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modalidad").value("ENVIO"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE_PAGO"))
                // El total son las lineas: el costo del envio esta pendiente del cliente (CCPIP-101)
                .andExpect(jsonPath("$.total").value(50.0))
                .andExpect(jsonPath("$.entrega.ubigeo").value(WANCHAQ))
                .andExpect(jsonPath("$.entrega.departamento").value("Cusco"))
                .andExpect(jsonPath("$.entrega.provincia").value("Cusco"))
                .andExpect(jsonPath("$.entrega.distrito").value("Wanchaq"))
                .andExpect(jsonPath("$.entrega.direccion").value("Av. de la Cultura 1234, dpto. 5"))
                .andExpect(jsonPath("$.entrega.referencia").value("Frente al grifo"))
                .andExpect(jsonPath("$.entrega.contactoNombre").value("María Quispe"))
                .andExpect(jsonPath("$.entrega.contactoCelular").value("987654321"))
                .andExpect(jsonPath("$.entrega.estado").value("DISPONIBLE"))
                // Sale del punto de acopio y no hay persona autorizada: lo recibe quien indica el destino
                .andExpect(jsonPath("$.punto.nombre").value(CUSCO))
                .andExpect(jsonPath("$.personaAutorizada").value(nullValue()))
                .andExpect(jsonPath("$.celularContacto").value(nullValue()));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM entrega e JOIN pedido pe ON pe.id = e.pedido_id JOIN usuario u ON u.id = pe.usuario_id
                WHERE u.correo = ? AND e.distribuidor_id IS NULL""", Long.class, COMPRADOR)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void elPedidoADomicilioSePagaYQuedaConfirmadoConSuEntrega() throws Exception {
        alCarrito(productoEnCusco(5), 2);
        long id = objectMapper.readTree(pedir(aDomicilio(destino(WANCHAQ))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        Map<String, Object> tarjeta = Map.of("numero", APROBADA, "titular", "ANA QUISPE", "mes", 12, "anio", 2030, "cvv", "123");
        mockMvc.perform(post("/api/pedidos/" + id + "/pagos").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("claveIdempotencia", UUID.randomUUID(), "tarjeta", tarjeta))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pedido.modalidad").value("ENVIO"))
                .andExpect(jsonPath("$.pedido.pagos[0].monto").value(50.0))
                .andExpect(jsonPath("$.pedido.entrega.distrito").value("Wanchaq"));
    }

    @Test
    void sinCoberturaDelPuntoEnElDistritoNoConfirma() throws Exception {
        alCarrito(productoEnCusco(5), 1);
        // Miraflores lo cubre Lima, no Cusco
        pedir(aDomicilio(destino(MIRAFLORES)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIN_COBERTURA"));
        pedir(aDomicilio(destino("999999")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIN_COBERTURA"));
        assertThat(pedidosDelComprador()).isEqualTo(pedidosAntes);
    }

    @Test
    void sinExistenciasEnElPuntoNoConfirmaAunqueCubraElDistrito() throws Exception {
        // El stock esta en Cusco; Lima cubre Miraflores pero no tiene el producto
        alCarrito(productoEnCusco(5), 1);
        Map<String, Object> cuerpo = aDomicilio(destino(MIRAFLORES));
        cuerpo.put("puntoId", punto("Centro Nacional Lima"));
        pedir(cuerpo)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUNTO_SIN_EXISTENCIAS"));
        assertThat(pedidosDelComprador()).isEqualTo(pedidosAntes);
    }

    @Test
    void exigeElDestinoCompleto() throws Exception {
        alCarrito(productoEnCusco(5), 1);
        Map<String, Object> incompleto = destino(WANCHAQ);
        incompleto.put("direccion", " ");
        incompleto.remove("contactoNombre");
        pedir(aDomicilio(incompleto))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos['entrega.direccion']").value("Indica la dirección"))
                .andExpect(jsonPath("$.campos['entrega.contactoNombre']").value("Indica quién recibe"));
        Map<String, Object> sinEntrega = aDomicilio(destino(WANCHAQ));
        sinEntrega.remove("entrega");
        pedir(sinEntrega).andExpect(status().isBadRequest());
        assertThat(pedidosDelComprador()).isEqualTo(pedidosAntes);
    }

    @Test
    void exigeUnCelularValidoDeQuienRecibe() throws Exception {
        alCarrito(productoEnCusco(5), 1);
        Map<String, Object> malo = destino(WANCHAQ);
        malo.put("contactoCelular", "123");
        pedir(aDomicilio(malo)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CELULAR_INVALIDO"));
    }

    // --- El recojo sigue igual ---

    @Test
    void elRecojoNoTieneEntregaYSigueExigiendoSuCelular() throws Exception {
        alCarrito(productoEnCusco(5), 2);
        Map<String, Object> recojo = new HashMap<>();
        recojo.put("puntoId", punto(CUSCO));
        recojo.put("personaAutorizada", "Rosa Quispe");
        pedir(recojo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.celularDeRecojoCompleto").value("Indica un celular de contacto"));
        recojo.put("celularContacto", "987654321");
        pedir(recojo)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modalidad").value("RECOJO"))
                .andExpect(jsonPath("$.total").value(50.0))
                .andExpect(jsonPath("$.entrega").value(nullValue()))
                .andExpect(jsonPath("$.personaAutorizada").value("Rosa Quispe"));
    }
}
