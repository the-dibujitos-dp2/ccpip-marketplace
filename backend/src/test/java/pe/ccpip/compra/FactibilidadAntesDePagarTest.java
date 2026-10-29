package pe.ccpip.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import jakarta.servlet.http.Cookie;
import pe.ccpip.pago.PasarelaPago;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-60 (RF-41, RNF-45): el comprador sabe antes de pagar si su carrito se puede entregar desde el punto
 * elegido, y la factibilidad se resuelve antes de cobrar, nunca con una aprobacion posterior: si el punto se
 * quedo sin existencias o dejo de cubrir el distrito despues de crear el pedido, no se llama a la pasarela.
 * Como en {@link EntregaDomicilioTest}, comprador propio y productos en Cusco retirados al terminar.
 */
class FactibilidadAntesDePagarTest extends IntegrationTest {

    private static final String CUSCO = "Centro Regional Cusco";
    private static final String LIMA = "Centro Nacional Lima";
    private static final String WANCHAQ = "080108";
    private static final String MIRAFLORES = "150122";
    private static final String COMPRADOR = "comprador.factibilidad@ccpip.test";
    private static final String APROBADA = "4111 1111 1111 1111";

    @MockitoSpyBean
    private PasarelaPago pasarela;

    private Cookie comprador;
    private final List<Long> creados = new ArrayList<>();

    @BeforeEach
    void compradorConCarritoVacio() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Comprador Factibilidad', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", COMPRADOR);
        jdbc.update("DELETE FROM item_carrito WHERE carrito_id IN (SELECT c.id FROM carrito c JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = ?)", COMPRADOR);
        comprador = loginAs(COMPRADOR);
    }

    @AfterEach
    void limpiar() {
        creados.forEach(id -> jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", id));
        creados.clear();
        jdbc.update("UPDATE cobertura_entrega SET activa = true WHERE ubigeo = ?", WANCHAQ);
        Mockito.reset(pasarela);
    }

    /** Producto de productor@ccpip.test, a S/ 25.00, con {@code unidades} en un lote vigente del punto de Cusco. */
    private long productoEnCusco(int unidades) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado, publicado_en)
                SELECT p.id, ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = 'ARTESANIA'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'UNIDAD'), 25.00, 'PUBLICADO', now()
                FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor@ccpip.test'
                RETURNING id""", Long.class, "Canasta factible " + UUID.randomUUID());
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, linea_id FROM producto WHERE id = ?", id, id);
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion)
                SELECT ?, u.id, 'L-F' || ?, ?, ?, current_date - 10 FROM ubicacion u WHERE u.nombre = ?""",
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

    private ResultActions factibilidad(String punto, String ubigeo) throws Exception {
        var pedido = get("/api/compra/factibilidad").cookie(comprador).param("punto", String.valueOf(punto(punto)));
        return mockMvc.perform(ubigeo == null ? pedido : pedido.param("ubigeo", ubigeo));
    }

    private long pedidoADomicilio(String ubigeo) throws Exception {
        Map<String, Object> destino = new HashMap<>();
        destino.put("ubigeo", ubigeo);
        destino.put("direccion", "Av. de la Cultura 1234");
        destino.put("contactoNombre", "María Quispe");
        destino.put("contactoCelular", "987654321");
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("puntoId", punto(CUSCO));
        cuerpo.put("modalidad", "ENVIO");
        cuerpo.put("entrega", destino);
        return objectMapper.readTree(mockMvc.perform(post("/api/pedidos").cookie(comprador)
                        .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private long pedidoParaRecojo() throws Exception {
        Map<String, Object> cuerpo = Map.of("puntoId", punto(CUSCO), "celularContacto", "987654321");
        return objectMapper.readTree(mockMvc.perform(post("/api/pedidos").cookie(comprador)
                        .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private ResultActions pagar(long pedidoId) throws Exception {
        Map<String, Object> tarjeta = Map.of("numero", APROBADA, "titular", "ANA QUISPE", "mes", 12, "anio", 2030, "cvv", "123");
        return mockMvc.perform(post("/api/pedidos/" + pedidoId + "/pagos").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("claveIdempotencia", UUID.randomUUID(), "tarjeta", tarjeta))));
    }

    private int intentosDePago(long pedidoId) {
        return jdbc.queryForObject("SELECT count(*) FROM pago WHERE pedido_id = ?", Integer.class, pedidoId);
    }

    private String estado(long pedidoId) {
        return jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedidoId);
    }

    private BigDecimal stock(long productoId) {
        return jdbc.queryForObject("SELECT cantidad FROM stock WHERE producto_id = ?", BigDecimal.class, productoId);
    }

    // --- Consultar antes de pagar ---

    @Test
    void elRecojoEsFactibleDondeHayExistenciasParaTodoElCarrito() throws Exception {
        alCarrito(productoEnCusco(5), 2);
        factibilidad(CUSCO, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.factible").value(true))
                .andExpect(jsonPath("$.existencias").value(true))
                .andExpect(jsonPath("$.cobertura").value(true))
                .andExpect(jsonPath("$.motivos", empty()));
    }

    @Test
    void unPuntoSinExistenciasNoEsFactible() throws Exception {
        alCarrito(productoEnCusco(5), 2);
        factibilidad(LIMA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.factible").value(false))
                .andExpect(jsonPath("$.existencias").value(false))
                .andExpect(jsonPath("$.motivos", contains("PUNTO_SIN_EXISTENCIAS")));
    }

    @Test
    void aDomicilioElPuntoDebeCubrirElDistritoDeDestino() throws Exception {
        alCarrito(productoEnCusco(5), 1);
        factibilidad(CUSCO, WANCHAQ).andExpect(jsonPath("$.factible").value(true));
        // Miraflores lo cubre Lima, no Cusco; y un ubigeo que no existe no lo cubre nadie
        factibilidad(CUSCO, MIRAFLORES)
                .andExpect(jsonPath("$.factible").value(false))
                .andExpect(jsonPath("$.existencias").value(true))
                .andExpect(jsonPath("$.cobertura").value(false))
                .andExpect(jsonPath("$.motivos", contains("SIN_COBERTURA")));
        factibilidad(CUSCO, "999999").andExpect(jsonPath("$.motivos", contains("SIN_COBERTURA")));
        // Lima cubre Miraflores, pero no tiene el producto: se informan ambos motivos por separado
        factibilidad(LIMA, MIRAFLORES)
                .andExpect(jsonPath("$.existencias").value(false))
                .andExpect(jsonPath("$.cobertura").value(true))
                .andExpect(jsonPath("$.motivos", contains("PUNTO_SIN_EXISTENCIAS")));
    }

    @Test
    void unaZonaDesactivadaDejaDeSerFactible() throws Exception {
        alCarrito(productoEnCusco(5), 1);
        jdbc.update("UPDATE cobertura_entrega SET activa = false WHERE ubigeo = ? AND ubicacion_id = ?", WANCHAQ, punto(CUSCO));
        factibilidad(CUSCO, WANCHAQ).andExpect(jsonPath("$.factible").value(false))
                .andExpect(jsonPath("$.motivos", contains("SIN_COBERTURA")));
    }

    @Test
    void conElCarritoVacioNoHayNadaQueValidar() throws Exception {
        factibilidad(CUSCO, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARRITO_VACIO"));
    }

    @Test
    void soloElCompradorConsultaLaFactibilidad() throws Exception {
        mockMvc.perform(get("/api/compra/factibilidad").param("punto", "1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/compra/factibilidad").param("punto", "1").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isForbidden());
    }

    // --- Revalidar al pagar, antes de cobrar ---

    @Test
    void siElPuntoDejaDeCubrirElDistritoNoSeCobraYElPedidoSeCancela() throws Exception {
        long producto = productoEnCusco(5);
        alCarrito(producto, 2);
        long pedido = pedidoADomicilio(WANCHAQ);
        jdbc.update("UPDATE cobertura_entrega SET activa = false WHERE ubigeo = ? AND ubicacion_id = ?", WANCHAQ, punto(CUSCO));

        pagar(pedido).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIN_COBERTURA"));
        verify(pasarela, never()).cobrar(any(), any(), any());
        assertThat(intentosDePago(pedido)).isZero();
        assertThat(estado(pedido)).isEqualTo("CANCELADO");
        assertThat(stock(producto)).isEqualByComparingTo("5");
        // El carrito sigue intacto para elegir otro punto o el recojo
        mockMvc.perform(get("/api/carrito").cookie(comprador)).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void siElPuntoSeQuedoSinExistenciasNoSeCobraYElPedidoSeCancela() throws Exception {
        long producto = productoEnCusco(5);
        alCarrito(producto, 2);
        long pedido = pedidoParaRecojo();
        jdbc.update("UPDATE lote SET cantidad_actual = 1 WHERE producto_id = ?", producto);
        jdbc.update("UPDATE stock SET cantidad = 1 WHERE producto_id = ?", producto);

        pagar(pedido).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUNTO_SIN_EXISTENCIAS"));
        verify(pasarela, never()).cobrar(any(), any(), any());
        assertThat(intentosDePago(pedido)).isZero();
        assertThat(estado(pedido)).isEqualTo("CANCELADO");
    }

    @Test
    void unPedidoADomicilioFactibleSePagaSinAprobacionPosterior() throws Exception {
        alCarrito(productoEnCusco(5), 2);
        long pedido = pedidoADomicilio(WANCHAQ);
        // Confirmado en el mismo pago: no queda ninguna solicitud pendiente de aprobacion (RF-41)
        pagar(pedido).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADO"))
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pedido.entrega.ubigeo").value(WANCHAQ));
    }

    @Test
    void siOtroCompradorSeLlevaLasUnidadesMientrasSeCobraElPagoSeAnulaYSeDevuelve() throws Exception {
        long producto = productoEnCusco(5);
        alCarrito(producto, 2);
        long pedido = pedidoParaRecojo();
        // La revalidacion pasa; las unidades se van durante el cobro, que ocurre fuera de toda transaccion
        doAnswer(invocacion -> {
            jdbc.update("UPDATE lote SET cantidad_actual = 1 WHERE producto_id = ?", producto);
            jdbc.update("UPDATE stock SET cantidad = 1 WHERE producto_id = ?", producto);
            return invocacion.callRealMethod();
        }).when(pasarela).cobrar(any(), any(), any());

        pagar(pedido).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ANULADO"))
                .andExpect(jsonPath("$.pedido.estado").value("CANCELADO"));
        verify(pasarela).devolver(any(), any());
        assertThat(stock(producto)).isEqualByComparingTo("1");
    }
}
