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

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-111 (RF-76): el superadministrador supervisa con informacion agregada (SA-01) y no puede
 * editar tiendas, productos, pedidos, datos de consumidores ni existencias (RNF-04).
 */
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

        long producto = productoConLote("Aguaje del tablero", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
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

    private static List<String> nombres(JsonNode lista) {
        return StreamSupport.stream(lista.spliterator(), false).map(n -> n.get("nombre").asText()).toList();
    }
}
