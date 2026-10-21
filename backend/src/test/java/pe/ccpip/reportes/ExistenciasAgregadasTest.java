package pe.ccpip.reportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-354 (RF-244): el superadministrador consulta las existencias agregadas de los puntos de acopio,
 * sin editarlas y sin ver a que productor o producto pertenecen.
 *
 * <p>Otras pruebas tambien dejan lotes en los puntos: se compara lo que cambia al agregar los de esta
 * prueba, que se deshacen al terminar.
 */
@Transactional
class ExistenciasAgregadasTest extends IntegrationTest {

    private static final String SUPER = "sudo@ccpip.test";
    private static final String CUSCO = "Centro Regional Cusco";
    private static final String RUTA = "/api/admin/reportes/existencias";

    @Test
    void sumaLoVigenteYLoVencidoDeCadaPuntoPorUnidad() throws Exception {
        Cookie sesion = loginAs(SUPER);
        JsonNode antes = consultar(sesion);

        String cafe = "Cafe " + UUID.randomUUID();
        lote(producto(cafe, "KG", "CAFE"), CUSCO, "10", false);
        lote(producto("Cacao " + UUID.randomUUID(), "KG", "CACAO"), CUSCO, "4", true);
        lote(producto("Telar " + UUID.randomUUID(), "UNIDAD", "TEXTIL"), CUSCO, "3", false);
        // Un lote agotado no es existencia
        lote(producto("Agotado " + UUID.randomUUID(), "KG", "CAFE"), CUSCO, "0", false);

        JsonNode despues = consultar(sesion);
        JsonNode cusco0 = punto(antes, CUSCO);
        JsonNode cusco = punto(despues, CUSCO);
        assertThat(cusco.get("departamento").asText()).isEqualTo("Cusco");
        assertThat(cusco.get("productos").asInt() - cusco0.get("productos").asInt()).isEqualTo(3);
        assertThat(cusco.get("lotes").asInt() - cusco0.get("lotes").asInt()).isEqualTo(3);
        assertThat(cusco.get("lotesVencidos").asInt() - cusco0.get("lotesVencidos").asInt()).isEqualTo(1);
        assertThat(diferencia(cusco0, cusco, "KG", "vigente")).isEqualByComparingTo("10");
        assertThat(diferencia(cusco0, cusco, "KG", "vencido")).isEqualByComparingTo("4");
        assertThat(diferencia(cusco0, cusco, "UNIDAD", "vigente")).isEqualByComparingTo("3");

        // Por linea y en total, con las mismas cifras
        assertThat(diferencia(linea(antes, "CAFE"), linea(despues, "CAFE"), "KG", "vigente")).isEqualByComparingTo("10");
        assertThat(diferencia(linea(antes, "CACAO"), linea(despues, "CACAO"), "KG", "vencido")).isEqualByComparingTo("4");
        assertThat(diferencia(antes.get("totales"), despues.get("totales"), "KG", "vigente")).isEqualByComparingTo("10");
        assertThat(despues.at("/totales/lotes").asInt() - antes.at("/totales/lotes").asInt()).isEqualTo(3);
        assertThat(despues.at("/totales/puntos").asInt()).isEqualTo(despues.get("puntos").size());
        assertThat(despues.at("/totales/puntosConExistencias").asInt()).isPositive();

        // Agregado: ningun productor ni producto con nombre
        assertThat(despues.toString()).doesNotContain(cafe, "Asoc. Nii Joi");
    }

    @Test
    void listaTodosLosPuntosAunqueNoTenganExistencias() throws Exception {
        long vacios = jdbc.queryForObject("SELECT count(*) FROM ubicacion WHERE tipo = 'PUNTO_ACOPIO' AND activo", Long.class);
        mockMvc.perform(get(RUTA).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fecha").isString())
                .andExpect(jsonPath("$.puntos.length()").value(vacios))
                .andExpect(jsonPath("$..productor").doesNotExist())
                .andExpect(jsonPath("$..lote").doesNotExist());
    }

    @Test
    void esSoloDeLectura() throws Exception {
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(post(RUTA).cookie(sesion)).andExpect(status().is4xxClientError());
        mockMvc.perform(put(RUTA).cookie(sesion)).andExpect(status().is4xxClientError());
        mockMvc.perform(delete(RUTA).cookie(sesion)).andExpect(status().is4xxClientError());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub_admin@ccpip.test", "admin_acopio@ccpip.test", "productor@ccpip.test",
            "consumidor@ccpip.test", "distribuidor@ccpip.test"})
    void lasExistenciasAgregadasSonDelSuperadministrador(String correo) throws Exception {
        mockMvc.perform(get(RUTA).cookie(loginAs(correo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void sinSesionNoHayExistencias() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
    }

    // --- Ayudas ---

    private JsonNode consultar(Cookie sesion) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(RUTA).cookie(sesion))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode punto(JsonNode existencias, String nombre) {
        for (JsonNode p : existencias.get("puntos")) {
            if (p.get("nombre").asText().equals(nombre)) {
                return p;
            }
        }
        throw new AssertionError("Sin el punto " + nombre);
    }

    /** La linea por codigo; si aun no tenia existencias, una vacia. */
    private JsonNode linea(JsonNode existencias, String codigo) {
        for (JsonNode l : existencias.get("lineas")) {
            if (codigo.equals(l.path("codigo").asText(null))) {
                return l;
            }
        }
        return objectMapper.createObjectNode().set("cantidades", objectMapper.createArrayNode());
    }

    /** Cuanto cambio {@code campo} (vigente o vencido) en esa unidad entre dos lecturas. */
    private static BigDecimal diferencia(JsonNode antes, JsonNode despues, String unidad, String campo) {
        return cantidad(despues, unidad, campo).subtract(cantidad(antes, unidad, campo));
    }

    private static BigDecimal cantidad(JsonNode grupo, String unidad, String campo) {
        for (JsonNode c : grupo.get("cantidades")) {
            if (c.get("unidad").asText().equals(unidad)) {
                return c.get(campo).decimalValue();
            }
        }
        return BigDecimal.ZERO;
    }

    private long producto(String nombre, String unidad, String linea) {
        return jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado, publicado_en)
                SELECT p.id, ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = ?),
                       (SELECT id FROM unidad_medida WHERE codigo = ?), 20.00, 'PUBLICADO', now()
                FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor@ccpip.test'
                RETURNING id""", Long.class, nombre, linea, unidad);
    }

    /** Un lote con {@code cantidad} de saldo en el punto; vencido ayer o vigente por un año. */
    private void lote(long productoId, String punto, String cantidad, boolean vencido) {
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                  fecha_produccion, fecha_vencimiento)
                SELECT ?, u.id, 'L-E' || ?, greatest(?, 1), ?, current_date - 90,
                       CASE WHEN ? THEN current_date - 1 ELSE current_date + 365 END
                FROM ubicacion u WHERE u.nombre = ?""",
                productoId, productoId, new BigDecimal(cantidad), new BigDecimal(cantidad), vencido, punto);
    }
}
