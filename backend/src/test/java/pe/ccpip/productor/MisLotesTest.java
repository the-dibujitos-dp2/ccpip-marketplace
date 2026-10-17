package pe.ccpip.productor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.inventario.DescuentoExistenciasService;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-161 (RF-130): el productor consulta todos sus lotes, de todos sus productos, ordenados por
 * vencimiento proximo, con su unidad, vencimiento, ubicacion y el saldo que dejaron sus movimientos.
 * Usa productor_nuevo, sembrado sin productos ni lotes.
 */
@Transactional
class MisLotesTest extends IntegrationTest {

    static final String LIMA = "Centro Nacional Lima";

    @Autowired
    DescuentoExistenciasService descuento;

    private Cookie nuevo;
    private long acopioLima;
    private long almacenPropio;
    private LocalDate hoy;

    @BeforeEach
    void ingresar() throws Exception {
        nuevo = loginAs("productor_nuevo@ccpip.test");
        acopioLima = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, LIMA);
        almacenPropio = jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN productor p ON p.id = u.productor_id JOIN usuario x ON x.id = p.usuario_id
                WHERE x.correo = 'productor_nuevo@ccpip.test'""", Long.class);
        hoy = LocalDate.now(LotesService.LIMA);
    }

    @Test
    void listaTodosSusLotesPrimeroElQueVenceAntesConUnidadUbicacionYSaldo() throws Exception {
        long camu = crearProducto("Camu camu en polvo", List.of("SUPERFRUTOS"), "BOLSA");
        long canasta = crearProducto("Canasta de chambira", List.of("ARTESANIA"), "UNIDAD");
        registrarLote(camu, "L-A", acopioLima, "40", hoy.plusDays(90));
        registrarLote(camu, "L-B", acopioLima, "20", hoy.plusDays(20));
        registrarLote(camu, "L-C", almacenPropio, "10", hoy.plusDays(10));
        registrarLote(canasta, "L-D", acopioLima, "5", null);
        // Un lote que ya vencio: el servidor no deja registrarlo asi, se arma directo en la base
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_vencimiento)
                VALUES (?, ?, 'L-V', 8, 8, ?)""", camu, acopioLima, hoy.minusDays(1));

        // Una venta de 3 sale del lote vigente del punto que vence antes (L-B)
        long pedido = pedidoConfirmado("MC-T0161", LIMA, camu, 3);
        descuento.descontarPorPedido(pedido, jdbc.queryForObject(
                "SELECT usuario_id FROM pedido WHERE id = ?", Long.class, pedido));

        mockMvc.perform(get("/api/productor/lotes").cookie(nuevo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[*].codigo").value(contains("L-V", "L-C", "L-B", "L-A", "L-D")))
                .andExpect(jsonPath("$[*].estado").value(contains("VENCIDO", "EN_ALMACEN", "VENDE_PRIMERO", "DISPONIBLE", "DISPONIBLE")))
                .andExpect(jsonPath("$[2].producto").value("Camu camu en polvo"))
                .andExpect(jsonPath("$[2].productoId").value(camu))
                .andExpect(jsonPath("$[2].unidad").value("Bolsa"))
                .andExpect(jsonPath("$[2].fechaVencimiento").value(hoy.plusDays(20).toString()))
                .andExpect(jsonPath("$[2].ubicacion").value(LIMA))
                .andExpect(jsonPath("$[2].tipoUbicacion").value("PUNTO_ACOPIO"))
                .andExpect(jsonPath("$[2].cantidadInicial").value(20))
                .andExpect(jsonPath("$[2].cantidadActual").value(17))
                .andExpect(jsonPath("$[1].tipoUbicacion").value("ALMACEN_PRODUCTOR"))
                .andExpect(jsonPath("$[4].producto").value("Canasta de chambira"))
                .andExpect(jsonPath("$[4].unidad").value("Unidad"))
                .andExpect(jsonPath("$[4].fechaVencimiento").doesNotExist());

        // El saldo del lote es el que explican sus movimientos: entrada menos venta (RNF-15)
        BigDecimal porMovimientos = jdbc.queryForObject("""
                SELECT sum(CASE WHEN m.tipo = 'ENTRADA' THEN m.cantidad ELSE -m.cantidad END)
                FROM movimiento_inventario m JOIN lote l ON l.id = m.lote_id
                WHERE l.producto_id = ? AND l.codigo = 'L-B'""", BigDecimal.class, camu);
        assertThat(porMovimientos).isEqualByComparingTo("17");
    }

    @Test
    void soloVeSusPropiosLotes() throws Exception {
        long ajeno = productoConLote("Cacao ajeno de prueba", "PUBLICADO", "PUNTO_ACOPIO", hoy.plusDays(5));

        mockMvc.perform(get("/api/productor/lotes").cookie(nuevo))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Cacao ajeno de prueba"))));
        // Su dueno si lo ve
        mockMvc.perform(get("/api/productor/lotes").cookie(loginAs("productor@ccpip.test")))
                .andExpect(jsonPath("$[?(@.productoId == " + ajeno + ")].codigo").value(contains("L-T" + ajeno)));
    }

    @Test
    void sinLotesDevuelveUnaListaVaciaYSoloEntraElProductor() throws Exception {
        mockMvc.perform(get("/api/productor/lotes").cookie(nuevo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/productor/lotes").cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/productor/lotes")).andExpect(status().isUnauthorized());
    }

    private long crearProducto(String nombre, List<String> lineas, String unidad) throws Exception {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nombre", nombre);
        d.put("lineas", lineas);
        d.put("unidad", unidad);
        d.put("modalidad", "MINORISTA");
        d.put("precio", 20.00);
        String body = mockMvc.perform(post("/api/productor/productos").cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(d)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void registrarLote(long productoId, String codigo, long ubicacion, String cantidad, LocalDate vence)
            throws Exception {
        Map<String, Object> lote = new LinkedHashMap<>();
        lote.put("codigo", codigo);
        lote.put("cantidad", cantidad);
        lote.put("fechaVencimiento", vence == null ? null : vence.toString());
        lote.put("ubicacionId", ubicacion);
        mockMvc.perform(post("/api/productor/productos/{id}/lotes", productoId).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(lote)))
                .andExpect(status().isCreated());
    }
}
