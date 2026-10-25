package pe.ccpip.administracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-111 (RF-76): el superadministrador supervisa con informacion agregada (SA-01) y no puede
 * editar tiendas, productos, pedidos, datos de consumidores ni existencias (RNF-04).
 * Transaccional: los pedidos que arma la prueba no deben llegar a las demas pruebas de la suite.
 */
@Transactional
class SuperAdministradorTest extends IntegrationTest {

    private static final String SUPER = "sudo@ccpip.test";

    // --- Tablero SA-01 ---

    @Test
    void elSuperadministradorVeElTableroAgregado() throws Exception {
        mockMvc.perform(get("/api/admin/tablero").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo").isString())
                .andExpect(jsonPath("$.ventas.mes").isNumber())
                .andExpect(jsonPath("$.comision.porcentaje").value(10))
                .andExpect(jsonPath("$.productores.activos").isNumber())
                .andExpect(jsonPath("$.ventasPorLinea").isArray())
                .andExpect(jsonPath("$.productoresTop").isArray())
                .andExpect(jsonPath("$.puntos[0].nombre").isString())
                // Solo agregados: ningun dato personal de consumidores
                .andExpect(jsonPath("$..correo").doesNotExist())
                .andExpect(jsonPath("$..email").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub_admin@ccpip.test", "consumidor@ccpip.test", "productor@ccpip.test",
            "admin_acopio@ccpip.test", "distribuidor@ccpip.test"})
    void elTableroEsSoloDelSuperadministrador(String correo) throws Exception {
        mockMvc.perform(get("/api/admin/tablero").cookie(loginAs(correo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void sinSesionNoHayTablero() throws Exception {
        mockMvc.perform(get("/api/admin/tablero")).andExpect(status().isUnauthorized());
    }

    @Test
    void unaVentaPagadaSumaEnVentasLineaProductorYPunto() throws Exception {
        Cookie sesion = loginAs(SUPER);
        JsonNode antes = tablero(sesion);

        long producto = productoConLote("Aguaje del tablero", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now(java.time.ZoneId.of("America/Lima")).plusMonths(6));
        pedidoConfirmado("T-" + System.nanoTime() % 1_000_000_000L, "Centro Nacional Lima", producto, 3);
        // Un pedido que espera pago todavia no es una venta
        jdbc.update("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 999, 'PENDIENTE_PAGO', NULL
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'""",
                "P-" + System.nanoTime() % 1_000_000_000L);

        JsonNode despues = tablero(sesion);
        BigDecimal venta = new BigDecimal("60.00");
        assertThat(monto(despues.at("/ventas/mes")).subtract(monto(antes.at("/ventas/mes")))).isEqualByComparingTo(venta);
        assertThat(despues.at("/ventas/pedidos").asLong() - antes.at("/ventas/pedidos").asLong()).isOne();
        assertThat(monto(linea(despues, "SUPERFRUTOS")).subtract(monto(linea(antes, "SUPERFRUTOS")))).isEqualByComparingTo(venta);
        assertThat(monto(punto(despues, "Centro Nacional Lima", "ventasDelMes"))
                .subtract(monto(punto(antes, "Centro Nacional Lima", "ventasDelMes")))).isEqualByComparingTo(venta);
        assertThat(monto(despues.at("/comision/estimada")))
                .isEqualByComparingTo(monto(despues.at("/ventas/mes")).multiply(new BigDecimal("0.10"))
                        .setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(nombres(despues.at("/productoresTop"))).isNotEmpty();
    }

    @Test
    void laVariacionSeCalculaSoloConUnMesAnteriorConVentas() {
        assertThat(TableroRepository.variacion(new BigDecimal("122"), new BigDecimal("100"))).isEqualByComparingTo("22.0");
        assertThat(TableroRepository.variacion(new BigDecimal("50"), new BigDecimal("200"))).isEqualByComparingTo("-75.0");
        assertThat(TableroRepository.variacion(new BigDecimal("50"), BigDecimal.ZERO)).isNull();
    }

    // --- CCPIP-430: merma del mes con los umbrales de cada linea ---

    @Test
    void laMermaDelMesSeColoreaConLosUmbralesDeSuLinea() throws Exception {
        Cookie sesion = loginAs(SUPER);
        jdbc.update("""
                INSERT INTO linea_producto (codigo, nombre, orden, merma_umbral_alerta, merma_umbral_critico)
                VALUES ('MERMA_TABLERO', 'Línea de la merma del tablero', 99, 10, 20)""");
        long producto = productoConLote("Camu camu de la merma", "PUBLICADO", null, null);
        jdbc.update("UPDATE producto SET linea_id = (SELECT id FROM linea_producto WHERE codigo = 'MERMA_TABLERO') WHERE id = ?", producto);
        long lote = loteEnCentroNacional(producto, new BigDecimal("90"));
        BigDecimal mermaAntes = monto(punto(tablero(sesion), "Centro Nacional Lima", "merma").get("unidades"));
        assertThat(mermaDeLinea(tablero(sesion), "MERMA_TABLERO")).isEmpty();

        // El punto marca 10 de 90: 10 / (10 + 80) = 11.1 %, entre la alerta (10 %) y el critico (20 %)
        marcarMerma(lote, "10");
        JsonNode tablero = tablero(sesion);
        JsonNode fila = mermaDeLinea(tablero, "MERMA_TABLERO").orElseThrow();
        assertThat(fila.get("punto").asText()).isEqualTo("Centro Nacional Lima");
        assertThat(fila.get("unidades").decimalValue()).isEqualByComparingTo("10");
        assertThat(fila.get("stock").decimalValue()).isEqualByComparingTo("80");
        assertThat(fila.get("porcentaje").decimalValue()).isEqualByComparingTo("11.1");
        assertThat(fila.get("umbralAlerta").decimalValue()).isEqualByComparingTo("10");
        assertThat(fila.get("umbralCritico").decimalValue()).isEqualByComparingTo("20");
        assertThat(fila.get("nivel").asText()).isEqualTo("ALERTA");

        // El punto suma esa merma y avisa al menos con el nivel de su linea mas comprometida
        JsonNode merma = punto(tablero, "Centro Nacional Lima", "merma");
        assertThat(monto(merma.get("unidades")).subtract(mermaAntes)).isEqualByComparingTo("10");
        assertThat(merma.get("porcentaje").isNumber()).isTrue();
        assertThat(merma.get("nivel").asText()).isIn("ALERTA", "CRITICO");

        // Con umbrales mas exigentes, la misma merma pasa a critica: el color sigue a la linea, no a un valor fijo
        jdbc.update("UPDATE linea_producto SET merma_umbral_alerta = 5, merma_umbral_critico = 11 WHERE codigo = 'MERMA_TABLERO'");
        tablero = tablero(sesion);
        assertThat(mermaDeLinea(tablero, "MERMA_TABLERO").orElseThrow().get("nivel").asText()).isEqualTo("CRITICO");
        assertThat(punto(tablero, "Centro Nacional Lima", "merma").get("nivel").asText()).isEqualTo("CRITICO");
    }

    @Test
    void elNivelDeMermaSigueLosUmbrales() {
        BigDecimal alerta = new BigDecimal("5");
        BigDecimal critico = new BigDecimal("10");
        assertThat(TableroModelos.NivelMerma.de(new BigDecimal("4.9"), alerta, critico)).isEqualTo(TableroModelos.NivelMerma.NORMAL);
        assertThat(TableroModelos.NivelMerma.de(new BigDecimal("5.0"), alerta, critico)).isEqualTo(TableroModelos.NivelMerma.ALERTA);
        assertThat(TableroModelos.NivelMerma.de(new BigDecimal("9.9"), alerta, critico)).isEqualTo(TableroModelos.NivelMerma.ALERTA);
        assertThat(TableroModelos.NivelMerma.de(new BigDecimal("10.0"), alerta, critico)).isEqualTo(TableroModelos.NivelMerma.CRITICO);

        assertThat(TableroRepository.porcentajeMerma(new BigDecimal("1"), new BigDecimal("2"))).isEqualByComparingTo("33.3");
        assertThat(TableroRepository.porcentajeMerma(BigDecimal.ZERO, new BigDecimal("40"))).isEqualByComparingTo("0");
        assertThat(TableroRepository.porcentajeMerma(BigDecimal.ZERO, BigDecimal.ZERO)).isNull();

        // Un punto sin stock ni merma no tiene porcentaje ni color; con stock y sin merma, es normal
        assertThat(TableroRepository.mermaDelPunto(1, BigDecimal.ZERO, BigDecimal.ZERO, List.of()).nivel()).isNull();
        assertThat(TableroRepository.mermaDelPunto(1, BigDecimal.ZERO, BigDecimal.TEN, List.of()).nivel())
                .isEqualTo(TableroModelos.NivelMerma.NORMAL);
    }

    // --- Sin edicion directa (criterio de aceptacion) ---

    @Test
    void noEditaTiendasProductosPedidosDatosDeConsumidoresNiExistencias() throws Exception {
        Cookie sesion = loginAs(SUPER);
        List<MockHttpServletRequestBuilder> prohibidas = List.of(
                // Tiendas y productos del productor
                post("/api/productor/productos"),
                put("/api/productor/productos/1"),
                post("/api/productor/productos/1/publicar"),
                post("/api/productor/productos/1/retirar"),
                post("/api/productor/tienda/publicar"),
                // Existencias
                post("/api/productor/productos/1/lotes"),
                get("/api/acopio/movimientos"),
                // Pedidos y datos de consumidores
                get("/api/acopio/pedidos"),
                post("/api/acopio/pedidos/P-1/listo"),
                post("/api/acopio/pedidos/P-1/recojo"),
                get("/api/pedidos/1"),
                post("/api/pedidos"),
                get("/api/carrito"),
                post("/api/carrito/items"),
                delete("/api/carrito/items/1"));
        for (MockHttpServletRequestBuilder peticion : prohibidas) {
            mockMvc.perform(peticion.cookie(sesion).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        }
    }

    // --- Ayudas ---

    private JsonNode tablero(Cookie sesion) throws Exception {
        String cuerpo = mockMvc.perform(get("/api/admin/tablero").cookie(sesion))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(cuerpo);
    }

    private static BigDecimal monto(JsonNode valor) {
        return valor.isMissingNode() || valor.isNull() ? BigDecimal.ZERO : valor.decimalValue();
    }

    private static JsonNode linea(JsonNode tablero, String codigo) {
        return StreamSupport.stream(tablero.get("ventasPorLinea").spliterator(), false)
                .filter(l -> codigo.equals(l.get("codigo").asText()))
                .map(l -> l.get("monto"))
                .findFirst().orElseThrow();
    }

    private static JsonNode punto(JsonNode tablero, String nombre, String campo) {
        return StreamSupport.stream(tablero.get("puntos").spliterator(), false)
                .filter(p -> nombre.equals(p.get("nombre").asText()))
                .map(p -> p.get(campo))
                .findFirst().orElseThrow();
    }

    private static java.util.Optional<JsonNode> mermaDeLinea(JsonNode tablero, String codigo) {
        return StreamSupport.stream(tablero.get("mermaPorLinea").spliterator(), false)
                .filter(m -> codigo.equals(m.get("codigo").asText()))
                .findFirst();
    }

    /** Un lote vigente en Centro Nacional Lima con su ENTRADA y su stock, como lo deja el registro del lote. */
    private long loteEnCentroNacional(long producto, BigDecimal cantidad) {
        Long id = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                  fecha_produccion, fecha_vencimiento)
                SELECT ?, u.id, 'L-M' || ?, ?, ?, current_date - 10, current_date + 180
                FROM ubicacion u WHERE u.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, producto, producto, cantidad, cantidad);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion)
                SELECT producto_id, ubicacion_id, id, 'ENTRADA', cantidad_inicial, 'Registro del lote' FROM lote WHERE id = ?""", id);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad)
                SELECT producto_id, ubicacion_id, cantidad_inicial FROM lote WHERE id = ?
                ON CONFLICT (producto_id, ubicacion_id) DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad""", id);
        return id == null ? 0 : id;
    }

    /** El administrador del punto marca merma del lote, confirmada (CCPIP-76). */
    private void marcarMerma(long lote, String cantidad) throws Exception {
        Long causa = jdbc.queryForObject("SELECT id FROM motivo_merma WHERE nombre = 'Daño'", Long.class);
        mockMvc.perform(post("/api/acopio/merma").cookie(loginAs("admin_acopio@ccpip.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("loteId", lote, "cantidad", cantidad, "motivoId", causa,
                                "clave", java.util.UUID.randomUUID().toString(), "confirmada", true))))
                .andExpect(status().isCreated());
    }

    private static List<String> nombres(JsonNode lista) {
        return StreamSupport.stream(lista.spliterator(), false).map(n -> n.get("nombre").asText()).toList();
    }
}
