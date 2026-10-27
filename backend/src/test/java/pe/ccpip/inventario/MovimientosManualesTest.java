package pe.ccpip.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-121 (RF-59) y CCPIP-287 (RF-208): el administrador registra entradas y salidas en los lotes
 * de su punto. CCPIP-283 (RF-203): el productor, en su almacen virtual unico. Cada movimiento lleva
 * producto, lote, cantidad, motivo, responsable y fecha; ninguna existencia cambia sin el (RNF-15),
 * ningun lote queda negativo (RNF-09) y el reintento con la misma clave no cuenta dos veces.
 */
@Transactional
class MovimientosManualesTest extends IntegrationTest {

    static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    static final String LIMA = "Centro Nacional Lima";
    static final String UCAYALI = "Centro Regional Ucayali";
    static final String ACOPIO = "/api/acopio/movimientos";
    static final String ALMACEN = "/api/productor/almacen/movimientos";

    // ---------- Punto de acopio: entrada (CCPIP-121) ----------

    @Test
    void laEntradaSumaAlLoteDeSuPuntoYQuedaConMotivoResponsableYFecha() throws Exception {
        long producto = producto("Ungurahui de prueba 121", "productor@ccpip.test");
        long lote = lote(producto, "R-121", punto(LIMA), "10", HOY.plusDays(90));
        BigDecimal stockAntes = stock(producto, punto(LIMA));

        registrar(loginAs("admin_acopio@ccpip.test"), ACOPIO, cuerpo("ENTRADA", lote, "5", "RECEPCION", " Guía 0012-4481 "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repetido").value(false))
                .andExpect(jsonPath("$.saldoLote").value(15.0))
                .andExpect(jsonPath("$.movimiento.tipo").value("ENTRADA"))
                .andExpect(jsonPath("$.movimiento.motivo").value("RECEPCION"))
                .andExpect(jsonPath("$.movimiento.producto").value("Ungurahui de prueba 121"))
                .andExpect(jsonPath("$.movimiento.lote").value("R-121"))
                .andExpect(jsonPath("$.movimiento.cantidad").value(5.0))
                .andExpect(jsonPath("$.movimiento.registradoPor").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.movimiento.registradoEn").exists())
                .andExpect(jsonPath("$.movimiento.observacion").value("Guía 0012-4481"));

        assertThat(saldo(lote)).isEqualByComparingTo("15");
        assertThat(stock(producto, punto(LIMA))).isEqualByComparingTo(stockAntes.add(new BigDecimal("5")));
        Map<String, Object> mov = jdbc.queryForMap("""
                SELECT m.tipo, m.motivo, m.cantidad, m.ubicacion_id, u.correo
                FROM movimiento_inventario m JOIN usuario u ON u.id = m.usuario_id
                WHERE m.lote_id = ? AND m.motivo IS NOT NULL""", lote);
        assertThat(mov).containsEntry("tipo", "ENTRADA").containsEntry("motivo", "RECEPCION")
                .containsEntry("correo", "admin_acopio@ccpip.test").containsEntry("ubicacion_id", punto(LIMA));

        // A-03 lo muestra entre las entradas, con su motivo
        mockMvc.perform(get(ACOPIO).param("tipo", "ENTRADAS").cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(jsonPath("$.items[0].lote").value("R-121"))
                .andExpect(jsonPath("$.items[0].motivo").value("RECEPCION"));
    }

    @Test
    void unaEntradaPuedeVolverALlenarUnLoteAgotadoPeroNoUnoVencido() throws Exception {
        long producto = producto("Camu camu de prueba 121", "productor@ccpip.test");
        long agotado = lote(producto, "AG-121", punto(LIMA), "0", HOY.plusDays(90));
        long vencido = lote(producto, "VE-121", punto(LIMA), "3", HOY.minusDays(1));
        Cookie admin = loginAs("admin_acopio@ccpip.test");

        registrar(admin, ACOPIO, cuerpo("ENTRADA", agotado, "2.5", "RECEPCION", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoLote").value(2.5));
        registrar(admin, ACOPIO, cuerpo("ENTRADA", vencido, "1", "RECEPCION", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOTE_VENCIDO"));
        assertThat(saldo(vencido)).isEqualByComparingTo("3");
    }

    // ---------- Punto de acopio: salida (CCPIP-287) ----------

    @Test
    void laSalidaBajaElLoteYElStockConSuMotivo() throws Exception {
        long producto = producto("Aguaje de prueba 287", "productor@ccpip.test");
        long lote = lote(producto, "S-287", punto(LIMA), "10", HOY.plusDays(90));
        BigDecimal stockAntes = stock(producto, punto(LIMA));

        registrar(loginAs("admin_acopio@ccpip.test"), ACOPIO, cuerpo("SALIDA", lote, "4", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoLote").value(6.0))
                .andExpect(jsonPath("$.movimiento.tipo").value("SALIDA"))
                .andExpect(jsonPath("$.movimiento.motivo").value("DEVOLUCION_PRODUCTOR"))
                .andExpect(jsonPath("$.movimiento.registradoPor").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.movimiento.observacion").doesNotExist());

        assertThat(saldo(lote)).isEqualByComparingTo("6");
        assertThat(stock(producto, punto(LIMA))).isEqualByComparingTo(stockAntes.subtract(new BigDecimal("4")));
        // La pestana Salidas de A-03 incluye la salida manual
        mockMvc.perform(get(ACOPIO).param("tipo", "SALIDAS").cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(jsonPath("$.items[?(@.lote == 'S-287')].motivo").value("DEVOLUCION_PRODUCTOR"));
    }

    @Test
    void unaSalidaMayorQueElSaldoSeRechazaSinMoverNada() throws Exception {
        long producto = producto("Cacao de prueba 287", "productor@ccpip.test");
        long lote = lote(producto, "X-287", punto(LIMA), "10", HOY.plusDays(90));
        BigDecimal stockAntes = stock(producto, punto(LIMA));
        int movimientosAntes = movimientos(lote);
        Cookie admin = loginAs("admin_acopio@ccpip.test");

        registrar(admin, ACOPIO, cuerpo("SALIDA", lote, "10.001", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALDO_INSUFICIENTE"))
                .andExpect(jsonPath("$.error").value(containsString("X-287")));

        assertThat(saldo(lote)).isEqualByComparingTo("10");
        assertThat(stock(producto, punto(LIMA))).isEqualByComparingTo(stockAntes);
        assertThat(movimientos(lote)).isEqualTo(movimientosAntes);

        // Todo el saldo si puede salir, y el lote queda en cero, nunca negativo
        registrar(admin, ACOPIO, cuerpo("SALIDA", lote, "10", "DEVOLUCION_PRODUCTOR", null)).andExpect(status().isCreated());
        registrar(admin, ACOPIO, cuerpo("SALIDA", lote, "1", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALDO_INSUFICIENTE"));
        assertThat(saldo(lote)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote WHERE cantidad_actual < 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock WHERE cantidad < 0", Integer.class)).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            // Cada motivo va con su tipo y su lugar; no hay ajuste de conteo (CHANGELOG 30/09) ni merma
            "ENTRADA, DEVOLUCION_PRODUCTOR, MOTIVO_INVALIDO",
            "SALIDA, RECEPCION, MOTIVO_INVALIDO",
            "ENTRADA, PRODUCCION, MOTIVO_INVALIDO",
            "SALIDA, RETIRO_PRODUCTOR, MOTIVO_INVALIDO",
            "SALIDA, AJUSTE_CONTEO, MOTIVO_INVALIDO",
            "SALIDA, MERMA, MOTIVO_INVALIDO",
            "AJUSTE, RECEPCION, TIPO_INVALIDO",
    })
    void elMotivoDebeCorresponderAlTipoYAlPunto(String tipo, String motivo, String codigo) throws Exception {
        long lote = lote(producto("Guayusa de prueba 121", "productor@ccpip.test"), "M-121", punto(LIMA), "10", null);
        registrar(loginAs("admin_acopio@ccpip.test"), ACOPIO, cuerpo(tipo, lote, "1", motivo, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(codigo));
        assertThat(saldo(lote)).isEqualByComparingTo("10");
    }

    @Test
    void faltanDatosOLaCantidadNoEsPositiva() throws Exception {
        long lote = lote(producto("Tamshi de prueba 121", "productor@ccpip.test"), "D-121", punto(LIMA), "10", null);
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        registrar(admin, ACOPIO, cuerpo("ENTRADA", lote, "0", "RECEPCION", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").exists());
        Map<String, Object> sinMotivo = cuerpo("ENTRADA", lote, "1", "RECEPCION", null);
        sinMotivo.remove("motivo");
        sinMotivo.remove("clave");
        registrar(admin, ACOPIO, sinMotivo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.motivo").exists())
                .andExpect(jsonPath("$.campos.clave").exists());
        assertThat(saldo(lote)).isEqualByComparingTo("10");
    }

    // ---------- Aislamiento entre puntos (RNF-05) ----------

    @Test
    void unAdministradorNoMueveNiVeLosLotesDeOtroPunto() throws Exception {
        long producto = producto("Sacha inchi de prueba 121", "productor@ccpip.test");
        long deUcayali = lote(producto, "UC-121", punto(UCAYALI), "8", HOY.plusDays(90));
        adminDe("admin_ucayali@ccpip.test", UCAYALI);

        Cookie lima = loginAs("admin_acopio@ccpip.test");
        registrar(lima, ACOPIO, cuerpo("SALIDA", deUcayali, "1", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOTE_NO_ENCONTRADO"));
        registrar(lima, ACOPIO, cuerpo("ENTRADA", deUcayali, "1", "RECEPCION", null)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/acopio/inventario").param("estado", "TODOS").cookie(lima))
                .andExpect(content().string(not(containsString("UC-121"))));
        mockMvc.perform(get("/api/acopio/inventario/lotes").cookie(lima))
                .andExpect(content().string(not(containsString("UC-121"))));
        assertThat(saldo(deUcayali)).isEqualByComparingTo("8");

        // Su administrador si
        registrar(loginAs("admin_ucayali@ccpip.test"), ACOPIO, cuerpo("SALIDA", deUcayali, "1", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoLote").value(7.0));
    }

    @Test
    void elPuntoNoMueveElAlmacenDelProductor() throws Exception {
        long producto = producto("Cafe de prueba 121", "productor@ccpip.test");
        long enAlmacen = lote(producto, "AL-121", almacenDe("productor@ccpip.test"), "8", null);
        registrar(loginAs("admin_acopio@ccpip.test"), ACOPIO, cuerpo("ENTRADA", enAlmacen, "1", "RECEPCION", null))
                .andExpect(status().isNotFound());
    }

    @Test
    void unAdministradorSinPuntoNoRegistraNada() throws Exception {
        adminDe("admin_sin_punto@ccpip.test", null);
        long lote = lote(producto("Ishpingo de prueba 121", "productor@ccpip.test"), "SP-121", punto(LIMA), "5", null);
        registrar(loginAs("admin_sin_punto@ccpip.test"), ACOPIO, cuerpo("ENTRADA", lote, "1", "RECEPCION", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACOPIO_SIN_ASIGNAR"));
    }

    // ---------- Idempotencia (RNF-15) ----------

    @Test
    void elMismoEnvioDosVecesSeCuentaUnaSola() throws Exception {
        long producto = producto("Copoazu de prueba 121", "productor@ccpip.test");
        long lote = lote(producto, "ID-121", punto(LIMA), "10", HOY.plusDays(90));
        BigDecimal stockAntes = stock(producto, punto(LIMA));
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        Map<String, Object> envio = cuerpo("SALIDA", lote, "3", "DEVOLUCION_PRODUCTOR", "Devuelto");

        String primero = registrar(admin, ACOPIO, envio)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(primero).at("/movimiento/id").asLong();

        registrar(admin, ACOPIO, envio)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repetido").value(true))
                .andExpect(jsonPath("$.movimiento.id").value(id))
                .andExpect(jsonPath("$.saldoLote").value(7.0));

        assertThat(saldo(lote)).isEqualByComparingTo("7");
        assertThat(stock(producto, punto(LIMA))).isEqualByComparingTo(stockAntes.subtract(new BigDecimal("3")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ? AND tipo = 'SALIDA'",
                Integer.class, lote)).isEqualTo(1);

        // La misma clave con otro contenido no se acepta como si fuera el mismo envio
        Map<String, Object> otro = new HashMap<>(envio);
        otro.put("cantidad", new BigDecimal("2"));
        registrar(admin, ACOPIO, otro)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENVIO_REUTILIZADO"));
        assertThat(saldo(lote)).isEqualByComparingTo("7");
    }

    @Test
    void elMovimientoNoSeEditaNiSeBorra() throws Exception {
        long lote = lote(producto("Achiote de prueba 121", "productor@ccpip.test"), "NB-121", punto(LIMA), "5", null);
        registrar(loginAs("admin_acopio@ccpip.test"), ACOPIO, cuerpo("SALIDA", lote, "1", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE movimiento_inventario SET cantidad = 5 WHERE lote_id = ? AND tipo = 'SALIDA'", lote))
                .isInstanceOf(DataAccessException.class);
    }

    // ---------- Almacen del productor (CCPIP-283) ----------

    @Test
    void elProductorRegistraEntradaYSalidaEnSuAlmacen() throws Exception {
        long producto = producto("Bolso de prueba 283", "productor@ccpip.test");
        long almacen = almacenDe("productor@ccpip.test");
        long lote = lote(producto, "PW-283", almacen, "12", null);
        Cookie productor = loginAs("productor@ccpip.test");

        registrar(productor, ALMACEN, cuerpo("ENTRADA", lote, "8", "PRODUCCION", "Tejidos de la semana"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoLote").value(20.0))
                .andExpect(jsonPath("$.movimiento.motivo").value("PRODUCCION"))
                .andExpect(jsonPath("$.movimiento.registradoPor").value("Comunidad Shipibo-Konibo"));
        registrar(productor, ALMACEN, cuerpo("SALIDA", lote, "5", "RETIRO_PRODUCTOR", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoLote").value(15.0));
        registrar(productor, ALMACEN, cuerpo("SALIDA", lote, "16", "RETIRO_PRODUCTOR", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALDO_INSUFICIENTE"));

        assertThat(saldo(lote)).isEqualByComparingTo("15");
        assertThat(stock(producto, almacen)).isEqualByComparingTo("15");
        // El mismo inventario por lote, estado y ubicacion de PW-16
        mockMvc.perform(get("/api/productor/inventario").cookie(productor))
                .andExpect(jsonPath("$.productos[?(@.nombre == 'Bolso de prueba 283')].lotes[0].cantidad").value(15.0))
                .andExpect(jsonPath("$.productos[?(@.nombre == 'Bolso de prueba 283')].lotes[0].tipoUbicacion")
                        .value("ALMACEN_PRODUCTOR"));
    }

    @Test
    void elProductorUsaLosMotivosDeSuAlmacen() throws Exception {
        long lote = lote(producto("Cesta de prueba 283", "productor@ccpip.test"), "MP-283",
                almacenDe("productor@ccpip.test"), "4", null);
        Cookie productor = loginAs("productor@ccpip.test");
        registrar(productor, ALMACEN, cuerpo("ENTRADA", lote, "1", "RECEPCION", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MOTIVO_INVALIDO"));
        registrar(productor, ALMACEN, cuerpo("SALIDA", lote, "1", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void elProductorSoloMueveSuAlmacenNiElDeOtroNiUnPunto() throws Exception {
        long propio = producto("Cacao propio 283", "productor@ccpip.test");
        long enPunto = lote(propio, "PT-283", punto(LIMA), "6", null);
        long ajeno = lote(producto("Cacao ajeno 283", "productor2@ccpip.test"), "AJ-283",
                almacenDe("productor2@ccpip.test"), "6", null);
        Cookie productor = loginAs("productor@ccpip.test");

        registrar(productor, ALMACEN, cuerpo("SALIDA", ajeno, "1", "RETIRO_PRODUCTOR", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOTE_NO_ENCONTRADO"));
        // Un almacen por productor: lo que esta en el punto lo mueve el punto
        registrar(productor, ALMACEN, cuerpo("SALIDA", enPunto, "1", "RETIRO_PRODUCTOR", null))
                .andExpect(status().isNotFound());
        assertThat(saldo(ajeno)).isEqualByComparingTo("6");
        assertThat(saldo(enPunto)).isEqualByComparingTo("6");

        registrar(loginAs("productor2@ccpip.test"), ALMACEN, cuerpo("SALIDA", ajeno, "1", "RETIRO_PRODUCTOR", null))
                .andExpect(status().isCreated());
    }

    // ---------- Roles ----------

    @Test
    void cadaRolRegistraSoloDondeLeToca() throws Exception {
        long enPunto = lote(producto("Pasta de prueba 121", "productor@ccpip.test"), "RO-121", punto(LIMA), "5", null);
        long enAlmacen = lote(producto("Pasta de almacen 283", "productor@ccpip.test"), "RA-283",
                almacenDe("productor@ccpip.test"), "5", null);

        registrar(loginAs("productor@ccpip.test"), ACOPIO, cuerpo("SALIDA", enPunto, "1", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isForbidden());
        registrar(loginAs("consumidor@ccpip.test"), ACOPIO, cuerpo("SALIDA", enPunto, "1", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isForbidden());
        registrar(loginAs("sudo@ccpip.test"), ACOPIO, cuerpo("SALIDA", enPunto, "1", "DEVOLUCION_PRODUCTOR", null))
                .andExpect(status().isForbidden());
        registrar(loginAs("admin_acopio@ccpip.test"), ALMACEN, cuerpo("SALIDA", enAlmacen, "1", "RETIRO_PRODUCTOR", null))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ACOPIO).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cuerpo("SALIDA", enPunto, "1", "DEVOLUCION_PRODUCTOR", null))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(ALMACEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cuerpo("SALIDA", enAlmacen, "1", "RETIRO_PRODUCTOR", null))))
                .andExpect(status().isUnauthorized());
        assertThat(saldo(enPunto)).isEqualByComparingTo("5");
        assertThat(saldo(enAlmacen)).isEqualByComparingTo("5");
    }

    @Test
    void elFormularioOfreceLosLotesDeSuPuntoAunSinSaldo() throws Exception {
        long producto = producto("Huito de prueba 121", "productor2@ccpip.test");
        lote(producto, "OP-1", punto(LIMA), "0", HOY.plusDays(90));
        lote(producto, "OP-2", punto(LIMA), "2", HOY.minusDays(2));
        mockMvc.perform(get("/api/acopio/inventario/lotes").cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].codigo", hasItem("OP-1")))
                .andExpect(jsonPath("$[?(@.codigo == 'OP-1')].stock").value(0.0))
                .andExpect(jsonPath("$[?(@.codigo == 'OP-2')].vencido").value(true))
                .andExpect(jsonPath("$[?(@.codigo == 'OP-2')].productor").exists());
    }

    // ---------- Ayudas ----------

    private ResultActions registrar(Cookie quien, String ruta, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post(ruta).cookie(quien).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    static Map<String, Object> cuerpo(String tipo, long lote, String cantidad, String motivo, String observacion) {
        Map<String, Object> c = new HashMap<>();
        c.put("tipo", tipo);
        c.put("loteId", lote);
        c.put("cantidad", new BigDecimal(cantidad));
        c.put("motivo", motivo);
        c.put("observacion", observacion);
        c.put("clave", UUID.randomUUID().toString());
        return c;
    }

    private long producto(String nombre, String correo) {
        return productoConLote(nombre, "PUBLICADO", null, null, correo);
    }

    /** Un lote con su ENTRADA y su stock, como lo deja el registro del lote (PW-06). */
    long lote(long producto, String codigo, long ubicacion, String cantidad, LocalDate vence) {
        BigDecimal q = new BigDecimal(cantidad);
        Long id = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                  fecha_produccion, fecha_vencimiento)
                VALUES (?, ?, ?, greatest(?, 1), ?, ?, ?) RETURNING id""", Long.class,
                producto, ubicacion, codigo, q, q, HOY.minusDays(60), vence);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion)
                VALUES (?, ?, ?, 'ENTRADA', greatest(?, 1), 'Registro del lote')""", producto, ubicacion, id, q);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, ?)
                ON CONFLICT (producto_id, ubicacion_id) DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad""",
                producto, ubicacion, q);
        return id;
    }

    private BigDecimal saldo(long lote) {
        return jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE id = ?", BigDecimal.class, lote);
    }

    private BigDecimal stock(long producto, long ubicacion) {
        return jdbc.queryForList("SELECT cantidad FROM stock WHERE producto_id = ? AND ubicacion_id = ?",
                BigDecimal.class, producto, ubicacion).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    private int movimientos(long lote) {
        return jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ?", Integer.class, lote);
    }

    long punto(String nombre) {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ? AND tipo = 'PUNTO_ACOPIO'", Long.class, nombre);
    }

    long almacenDe(String correo) {
        return jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN productor p ON p.id = u.productor_id JOIN usuario x ON x.id = p.usuario_id
                WHERE x.correo = ? AND u.tipo = 'ALMACEN_PRODUCTOR'""", Long.class, correo);
    }

    /** Otro administrador de punto, con la clave de demostracion; sin {@code punto}, sin asignar. */
    void adminDe(String correo, String punto) {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Admin de otro punto', rol_id, true
                FROM usuario WHERE correo = 'admin_acopio@ccpip.test'""", correo);
        if (punto != null) {
            jdbc.update("""
                    INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
                    SELECT u.id, l.id FROM usuario u, ubicacion l WHERE u.correo = ? AND l.nombre = ?""", correo, punto);
        }
    }
}
