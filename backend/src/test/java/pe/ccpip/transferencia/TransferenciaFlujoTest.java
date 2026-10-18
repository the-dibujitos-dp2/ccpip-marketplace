package pe.ccpip.transferencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Transferencias de existencias (RNF-47): CCPIP-73, 146, 164, 362, 277 y 278. Las unidades no se
 * duplican ni se pierden, el lote solo cambia con su movimiento (RNF-15, ADR-10) y cada quien ve solo
 * lo suyo (RNF-05).
 */
@Transactional
class TransferenciaFlujoTest extends IntegrationTest {

    static final String LIMA = "Centro Nacional Lima";
    static final String UCAYALI = "Centro Regional Ucayali";
    static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    static final String BASE = "/api/productor/transferencias";

    private long almacen;
    private long producto;
    private long lote;
    private Cookie productor;

    @BeforeEach
    void preparar() throws Exception {
        almacen = almacenDe("productor_nuevo@ccpip.test");
        producto = productoConLote("Aceite de prueba 73", "PUBLICADO", null, null, "productor_nuevo@ccpip.test");
        lote = lote(producto, "ALM-73", almacen, "10", HOY.plusDays(60));
        productor = loginAs("productor_nuevo@ccpip.test");
    }

    @Test
    void ubicacionesDeSuAlmacenYLosPuntos() throws Exception {
        mockMvc.perform(get(BASE + "/ubicaciones").cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.almacen.id").value(almacen))
                .andExpect(jsonPath("$.almacen.tipo").value("ALMACEN_PRODUCTOR"))
                .andExpect(jsonPath("$.puntos[*].nombre", hasItem(LIMA)))
                .andExpect(jsonPath("$.puntos[*].nombre", hasItem(UCAYALI)))
                .andExpect(jsonPath("$.puntos[*].tipo", not(hasItem("ALMACEN_PRODUCTOR"))))
                // RNF-31: sin coordenadas
                .andExpect(jsonPath("$.puntos[0].latitud").doesNotExist());
    }

    @Test
    void flujoCompletoConservaLasUnidades() throws Exception {
        long id = crear(almacenAPunto(punto(LIMA), "4"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("INICIADA"))
                .andExpect(jsonPath("$.tipo").value("ALMACEN_A_PUNTO"))
                .andExpect(jsonPath("$.origen.id").value(almacen))
                .andExpect(jsonPath("$.destino.nombre").value(LIMA))
                .andExpect(jsonPath("$.items[0].loteOrigen").value("ALM-73"))
                .andExpect(jsonPath("$.items[0].producto").value("Aceite de prueba 73"))
                .andExpect(jsonPath("$.cantidad").value(4.0))
                .andExpect(jsonPath("$.historial.length()").value(1))
                .andReturn().getResponse().getContentAsString().transform(this::id);
        // Iniciar no compromete unidades
        assertThat(saldo(lote)).isEqualByComparingTo("10");
        assertThat(movimientos(id)).isZero();

        // CCPIP-164: preparar descuenta el lote con su movimiento ligado a la linea
        accion(productor, BASE + "/" + id + "/preparar").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PREPARADA"));
        assertThat(saldo(lote)).isEqualByComparingTo("6");
        assertThat(stock(producto, almacen)).isEqualByComparingTo("6");
        assertThat(movimientos(id)).isEqualTo(1);

        // CCPIP-362: despachar no vuelve a descontar
        accion(productor, BASE + "/" + id + "/despachar").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DESPACHADA"));
        assertThat(saldo(lote)).isEqualByComparingTo("6");
        assertThat(movimientos(id)).isEqualTo(1);
        // En transito no se vende: no hay lote en el punto aun
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote WHERE producto_id = ? AND ubicacion_id = ?",
                Integer.class, producto, punto(LIMA))).isZero();

        // CCPIP-278: el punto de destino la ve por recibir y la recibe
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        mockMvc.perform(get("/api/acopio/transferencias").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.porRecibir[?(@.id == " + id + ")].sentido").value("ENTRANTE"))
                .andExpect(jsonPath("$.items[?(@.id == " + id + ")].productor").exists());
        accion(admin, "/api/acopio/transferencias/" + id + "/recibir")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECIBIDA"))
                .andExpect(jsonPath("$.items[0].loteDestino").value("ALM-73-" + TransferenciaService.codigo(id)));
        BigDecimal destino = jdbc.queryForObject(
                "SELECT cantidad_actual FROM lote WHERE producto_id = ? AND ubicacion_id = ?", BigDecimal.class,
                producto, punto(LIMA));
        assertThat(destino).isEqualByComparingTo("4");
        assertThat(saldo(lote).add(destino)).isEqualByComparingTo("10");
        assertThat(stock(producto, punto(LIMA))).isEqualByComparingTo("4");
        assertThat(movimientos(id)).isEqualTo(2);
        // El lote de destino conserva el vencimiento del origen
        assertThat(jdbc.queryForObject("SELECT fecha_vencimiento FROM lote WHERE producto_id = ? AND ubicacion_id = ?",
                LocalDate.class, producto, punto(LIMA))).isEqualTo(HOY.plusDays(60));

        // Recibir dos veces no suma dos veces
        accion(admin, "/api/acopio/transferencias/" + id + "/recibir").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));

        // Historial completo, para el punto y para el productor
        mockMvc.perform(get("/api/acopio/transferencias/" + id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historial.length()").value(4))
                .andExpect(jsonPath("$.historial[0].estado").value("INICIADA"))
                .andExpect(jsonPath("$.historial[3].estado").value("RECIBIDA"))
                .andExpect(jsonPath("$.historial[3].usuario").value("Administrador de Acopio de Prueba"));
        mockMvc.perform(get(BASE).param("vista", "HISTORIAL").cookie(productor))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.historial").value(1))
                .andExpect(jsonPath("$.enCurso").value(0));
    }

    @Test
    void noSeComprometeDosVecesElMismoSaldo() throws Exception {
        long a = crear(almacenAPunto(punto(LIMA), "6")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);
        long b = crear(almacenAPunto(punto(UCAYALI), "6")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::id);
        accion(productor, BASE + "/" + a + "/preparar").andExpect(status().isOk());
        accion(productor, BASE + "/" + b + "/preparar").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALDO_INSUFICIENTE"));
        assertThat(saldo(lote)).isEqualByComparingTo("4");
        assertThat(movimientos(b)).isZero();
        // Preparar otra vez la misma tampoco descuenta
        accion(productor, BASE + "/" + a + "/preparar").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));
        assertThat(saldo(lote)).isEqualByComparingTo("4");
    }

    @Test
    void rechazaDatosInvalidos() throws Exception {
        crear(almacenAPunto(punto(LIMA), "11")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALDO_INSUFICIENTE"));
        crear(almacenAPunto(almacen, "1")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESTINO_INVALIDO"));
        crear(almacenAPunto(punto(LIMA), "0")).andExpect(status().isBadRequest());
        crear(Map.of("tipo", "ALMACEN_A_PUNTO", "destinoId", punto(LIMA),
                "items", List.of(item(lote, "1"), item(lote, "2"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOTE_YA_INCLUIDO"));
        long vencido = lote(producto, "ALM-VEN", almacen, "3", HOY.minusDays(1));
        crear(Map.of("tipo", "ALMACEN_A_PUNTO", "destinoId", punto(LIMA), "items", List.of(item(vencido, "1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOTE_VENCIDO"));
        // Un lote ajeno es como uno que no existe
        long ajeno = productoConLote("Cacao ajeno 73", "PUBLICADO", null, null, "productor2@ccpip.test");
        long loteAjeno = lote(ajeno, "AJ-73", almacenDe("productor2@ccpip.test"), "5", HOY.plusDays(30));
        crear(Map.of("tipo", "ALMACEN_A_PUNTO", "destinoId", punto(LIMA), "items", List.of(item(loteAjeno, "1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOTE_NO_ENCONTRADO"));
        // Despachar sin preparar
        long id = crear(almacenAPunto(punto(LIMA), "1")).andReturn().getResponse().getContentAsString()
                .transform(this::id);
        accion(productor, BASE + "/" + id + "/despachar").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));
        mockMvc.perform(get(BASE).param("vista", "OTRA").cookie(productor)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void solicitudEntrePuntosQuedaPendienteDeAprobador() throws Exception {
        long enLima = lote(producto, "LIM-73", punto(LIMA), "8", HOY.plusDays(40));
        long id = crear(Map.of("tipo", "ENTRE_PUNTOS", "origenId", punto(LIMA), "destinoId", punto(UCAYALI),
                "items", List.of(item(enLima, "3")), "observacion", "Reponer Ucayali"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("SOLICITADA"))
                .andExpect(jsonPath("$.observacion").value("Reponer Ucayali"))
                .andReturn().getResponse().getContentAsString().transform(this::id);
        // C8: sin aprobador definido no avanza ni compromete unidades
        accion(productor, BASE + "/" + id + "/preparar").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APROBACION_PENDIENTE"));
        assertThat(saldo(enLima)).isEqualByComparingTo("8");

        // El punto de origen la ve como saliente, pero no la puede recibir
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        mockMvc.perform(get("/api/acopio/transferencias").cookie(admin))
                .andExpect(jsonPath("$.items[?(@.id == " + id + ")].sentido").value("SALIENTE"))
                .andExpect(jsonPath("$.porRecibir[*].id", not(hasItem((int) id))));
        accion(admin, "/api/acopio/transferencias/" + id + "/recibir").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ES_DESTINO"));

        // Origen y destino iguales, u origen que no es punto
        crear(Map.of("tipo", "ENTRE_PUNTOS", "origenId", punto(LIMA), "destinoId", punto(LIMA),
                "items", List.of(item(enLima, "1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISMO_ORIGEN_DESTINO"));
        crear(Map.of("tipo", "ENTRE_PUNTOS", "origenId", punto(LIMA), "destinoId", punto(UCAYALI),
                "items", List.of(item(lote, "1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOTE_FUERA_DE_ORIGEN"));
        mockMvc.perform(get(BASE).param("tipo", "ENTRE_PUNTOS").cookie(productor))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.enCurso").value(1));
    }

    @Test
    void cadaQuienVeSoloLoSuyo() throws Exception {
        long id = crear(almacenAPunto(punto(UCAYALI), "2")).andReturn().getResponse().getContentAsString()
                .transform(this::id);
        // Otro productor no la ve
        Cookie otro = loginAs("productor2@ccpip.test");
        mockMvc.perform(get(BASE + "/" + id).cookie(otro)).andExpect(status().isNotFound());
        accion(otro, BASE + "/" + id + "/preparar").andExpect(status().isNotFound());
        mockMvc.perform(get(BASE).cookie(otro)).andExpect(jsonPath("$.items[*].id", not(hasItem((int) id))));

        accion(productor, BASE + "/" + id + "/preparar").andExpect(status().isOk());
        accion(productor, BASE + "/" + id + "/despachar").andExpect(status().isOk());
        // El administrador de Lima no ve ni recibe lo que va a Ucayali
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        mockMvc.perform(get("/api/acopio/transferencias/" + id).cookie(admin)).andExpect(status().isNotFound());
        accion(admin, "/api/acopio/transferencias/" + id + "/recibir").andExpect(status().isNotFound());
        assertThat(movimientos(id)).isEqualTo(1);

        // Roles
        mockMvc.perform(get(BASE).cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE).cookie(admin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/acopio/transferencias").cookie(productor)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void laBaseImpideMovimientosDeTransferenciaSinLinea() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad)
                VALUES (?, ?, ?, 'TRANSFERENCIA_SALIDA', 1)""", producto, almacen, lote));
    }

    // ---------------------------------------------------------------------------------------

    private Map<String, Object> almacenAPunto(long destino, String cantidad) {
        return Map.of("tipo", "ALMACEN_A_PUNTO", "destinoId", destino, "items", List.of(item(lote, cantidad)));
    }

    private static Map<String, Object> item(long loteId, String cantidad) {
        return Map.of("loteId", loteId, "cantidad", new BigDecimal(cantidad));
    }

    private ResultActions crear(Object cuerpo) throws Exception {
        return mockMvc.perform(post(BASE).cookie(productor).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private ResultActions accion(Cookie quien, String url) throws Exception {
        return mockMvc.perform(post(url).cookie(quien));
    }

    private long id(String cuerpo) {
        try {
            JsonNode n = objectMapper.readTree(cuerpo);
            return n.get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BigDecimal saldo(long loteId) {
        return jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE id = ?", BigDecimal.class, loteId);
    }

    private BigDecimal stock(long productoId, long ubicacionId) {
        return jdbc.queryForObject("SELECT cantidad FROM stock WHERE producto_id = ? AND ubicacion_id = ?",
                BigDecimal.class, productoId, ubicacionId);
    }

    private int movimientos(long transferenciaId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM movimiento_inventario m JOIN transferencia_item ti ON ti.id = m.transferencia_item_id
                WHERE ti.transferencia_id = ?""", Integer.class, transferenciaId);
    }

    private long lote(long productoId, String codigo, long ubicacion, String cantidad, LocalDate vence) {
        Long id = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                  fecha_produccion, fecha_vencimiento)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                productoId, ubicacion, codigo, new BigDecimal(cantidad), new BigDecimal(cantidad),
                HOY.minusDays(30), vence);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, ?)
                ON CONFLICT (producto_id, ubicacion_id) DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad""",
                productoId, ubicacion, new BigDecimal(cantidad));
        return id;
    }

    private long punto(String nombre) {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ? AND tipo = 'PUNTO_ACOPIO'", Long.class, nombre);
    }

    private long almacenDe(String correo) {
        return jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN productor p ON p.id = u.productor_id JOIN usuario x ON x.id = p.usuario_id
                WHERE x.correo = ? AND u.tipo = 'ALMACEN_PRODUCTOR'""", Long.class, correo);
    }
}
