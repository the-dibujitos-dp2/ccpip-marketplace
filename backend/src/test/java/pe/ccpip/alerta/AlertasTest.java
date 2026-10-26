package pe.ccpip.alerta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.aviso.Correo;
import pe.ccpip.support.IntegrationTest;

/**
 * Alertas del productor (PW-21) y del punto de acopio (A-08):
 * CCPIP-145 y CCPIP-79 (por vencer, anticipacion configurable), CCPIP-337 (estancados),
 * CCPIP-293 (reposicion), CCPIP-366 (interes) y CCPIP-367 (transferencia recibida y pedido nuevo).
 *
 * <p>Transaccional: lo que crea cada prueba no llega a las demas. La revision periodica no corre
 * durante la suite (ver IntegrationTest); aqui se llama a {@code revisar()}.
 */
@Transactional
class AlertasTest extends IntegrationTest {

    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String OTRO_PRODUCTOR = "productor2@ccpip.test";
    private static final String ADMIN = "admin_acopio@ccpip.test";
    private static final String LIMA = "Centro Nacional Lima";
    private static final String LORETO = "Centro Regional Loreto";

    @Autowired
    private RevisionAlertas revision;

    // --- Configuracion (PW-21, A-08) ---

    @Test
    void sinConfigurarRigenLosProvisionalesYElProductorGuardaLosSuyos() throws Exception {
        Cookie sesion = loginAs(PRODUCTOR);
        mockMvc.perform(get("/api/productor/alertas/preferencias").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisional").value(true))
                .andExpect(jsonPath("$.diasVencimiento").value(15))
                .andExpect(jsonPath("$.diasEstancado").value(30))
                .andExpect(jsonPath("$.correo").value(true))
                .andExpect(jsonPath("$.tipos", hasSize(6)));

        guardar(sesion, "/api/productor/alertas/preferencias", 7, 20, List.of("POR_VENCER", "INTERES"), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisional").value(false))
                .andExpect(jsonPath("$.diasVencimiento").value(7))
                .andExpect(jsonPath("$.diasEstancado").value(20))
                .andExpect(jsonPath("$.correo").value(false))
                .andExpect(jsonPath("$.tipos", containsInAnyOrder("POR_VENCER", "INTERES")))
                .andExpect(jsonPath("$.disponibles", hasSize(6)));

        // La misma anticipacion manda en su almacen (PW-16)
        mockMvc.perform(get("/api/productor/inventario").cookie(sesion))
                .andExpect(jsonPath("$.diasAviso").value(7));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -3, 366})
    void losDiasDebenEstarEntreUnoY365(int dias) throws Exception {
        Cookie sesion = loginAs(PRODUCTOR);
        guardar(sesion, "/api/productor/alertas/preferencias", dias, 30, List.of("POR_VENCER"), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.diasVencimiento").isString());
        guardar(sesion, "/api/productor/alertas/preferencias", 15, dias, List.of("POR_VENCER"), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.diasEstancado").isString());
    }

    @Test
    void elPuntoSoloConfiguraSusPuntosYSusTiposDeAlerta() throws Exception {
        Cookie sesion = loginAs(ADMIN);
        long lima = ubicacion(LIMA);
        mockMvc.perform(get("/api/acopio/alertas/preferencias").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ubicacionId").value(lima))
                .andExpect(jsonPath("$[0].punto").value(LIMA))
                .andExpect(jsonPath("$[0].preferencias.tipos", containsInAnyOrder("POR_VENCER", "ESTANCADO")));

        guardar(sesion, "/api/acopio/alertas/preferencias/" + lima, 10, 45, List.of("POR_VENCER", "ESTANCADO"), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferencias.diasVencimiento").value(10))
                .andExpect(jsonPath("$.preferencias.provisional").value(false));
        // La reposicion es del productor, no del punto
        guardar(sesion, "/api/acopio/alertas/preferencias/" + lima, 10, 45, List.of("REPOSICION"), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIPO_ALERTA_NO_DISPONIBLE"));
        // Un punto que no administra no existe para el (RNF-05)
        guardar(sesion, "/api/acopio/alertas/preferencias/" + ubicacion(LORETO), 10, 45, List.of("POR_VENCER"), true)
                .andExpect(status().isNotFound());
    }

    @Test
    void cadaRolUsaSusRutas() throws Exception {
        mockMvc.perform(get("/api/acopio/alertas").cookie(loginAs(PRODUCTOR))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/productor/alertas").cookie(loginAs(ADMIN))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/productor/alertas").cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/productor/alertas")).andExpect(status().isUnauthorized());
    }

    // --- CCPIP-79 y CCPIP-145: por vencer ---

    @Test
    void unLotePorVencerAlertaAlProductorYAlPuntoUnaSolaVezYPorCorreo() throws Exception {
        long producto = producto("Harina de aguaje alerta");
        long lote = lote(producto, LIMA, 6, HOY.plusDays(5));

        revision.revisar();
        revision.revisar();

        String alerta = conLote(lote);
        mockMvc.perform(get("/api/productor/alertas").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sinLeer").isNumber())
                .andExpect(jsonPath(alerta, hasSize(1)))
                .andExpect(jsonPath(alerta + ".tipo", contains("POR_VENCER")))
                .andExpect(jsonPath(alerta + ".producto", contains("Harina de aguaje alerta")))
                .andExpect(jsonPath(alerta + ".ubicacion", contains(LIMA)))
                .andExpect(jsonPath(alerta + ".fecha", contains(HOY.plusDays(5).toString())))
                .andExpect(jsonPath(alerta + ".cantidad", contains(6.0)))
                .andExpect(jsonPath(alerta + ".porCorreo", contains(true)));
        mockMvc.perform(get("/api/acopio/alertas").cookie(loginAs(ADMIN)))
                .andExpect(jsonPath("$.alertas[*].lote", hasItem("L-A" + lote)));

        // Un correo por persona aunque la revision corra dos veces
        assertThat(correos.enviados().stream().filter(c -> c.para().equals(PRODUCTOR))).hasSize(1);
        Correo correo = correos.ultimoPara(PRODUCTOR).orElseThrow();
        assertThat(correo.texto()).contains("Harina de aguaje alerta", "L-A" + lote, LIMA, "/productor/alertas");
        assertThat(correos.ultimoPara(ADMIN).orElseThrow().texto()).contains("/acopio/alertas");
    }

    @Test
    void laAnticipacionDeCadaUnoDecideQueEsPorVencer() throws Exception {
        long producto = producto("Camu camu anticipacion");
        long lote = lote(producto, LIMA, 6, HOY.plusDays(5));
        revision.revisar();
        assertThat(abiertas("POR_VENCER", lote)).isEqualTo(2);

        // El productor la baja a 3 dias: su alerta se cierra; la del punto sigue con sus 15 provisionales
        guardar(loginAs(PRODUCTOR), "/api/productor/alertas/preferencias", 3, 30,
                List.of("POR_VENCER", "ESTANCADO", "REPOSICION", "INTERES", "TRANSFERENCIA_RECIBIDA", "PEDIDO_NUEVO"), true)
                .andExpect(status().isOk());
        revision.revisar();
        assertThat(abiertas("POR_VENCER", lote)).isEqualTo(1);

        // El punto ve en su inventario lo que esta por vencer segun su anticipacion (CCPIP-145)
        mockMvc.perform(get("/api/acopio/inventario").param("estado", "POR_VENCER").cookie(loginAs(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diasAviso").value(15))
                .andExpect(jsonPath("$.items[*].id", hasItem((int) lote)));
    }

    // --- CCPIP-425: en tiempo real ---

    @Test
    void laBandejaMuestraAlInstanteUnLoteNuevoPorVencerSinEsperarLaRevisionNiEnviarCorreos() throws Exception {
        long lote = lote(producto("Aguaje al instante"), LIMA, 4, HOY.plusDays(3));

        mockMvc.perform(get("/api/productor/alertas").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(conLote(lote) + ".tipo", contains("POR_VENCER")));
        mockMvc.perform(get("/api/acopio/alertas").cookie(loginAs(ADMIN)))
                .andExpect(jsonPath("$.alertas[*].lote", hasItem("L-A" + lote)));
        // El correo sale con la revision periodica, que no corre en la suite
        assertThat(correos.enviados()).noneMatch(c -> c.texto().contains("L-A" + lote));
    }

    @Test
    void cambiarLaAnticipacionActualizaAlInstanteLasAlertasYElInicio() throws Exception {
        Cookie sesion = loginAs(PRODUCTOR);
        long lote = lote(producto("Camu camu al instante"), LIMA, 6, HOY.plusDays(5));
        revision.revisar();
        assertThat(abiertas("POR_VENCER", lote)).isEqualTo(2);

        guardar(sesion, "/api/productor/alertas/preferencias", 3, 30, List.of("POR_VENCER"), false)
                .andExpect(status().isOk());
        assertThat(abiertas("POR_VENCER", lote)).isEqualTo(1);
        // El inicio (PW-01) dice la misma anticipacion y ya no cuenta ese lote
        mockMvc.perform(get("/api/productor/panel").cookie(sesion))
                .andExpect(jsonPath("$.diasAviso").value(3))
                .andExpect(jsonPath("$.lotesPorVencer[*].codigo", not(hasItem("L-A" + lote))));

        // El punto la sube y su alerta, ya cerrada, vuelve a abrirse
        Cookie admin = loginAs(ADMIN);
        guardar(admin, "/api/acopio/alertas/preferencias/" + ubicacion(LIMA), 2, 30, List.of("POR_VENCER", "ESTANCADO"), false)
                .andExpect(status().isOk());
        assertThat(abiertas("POR_VENCER", lote)).isZero();
        guardar(admin, "/api/acopio/alertas/preferencias/" + ubicacion(LIMA), 30, 30, List.of("POR_VENCER", "ESTANCADO"), false)
                .andExpect(status().isOk());
        assertThat(abiertas("POR_VENCER", lote)).isEqualTo(1);
    }

    @Test
    void unPuntoNoVeLasAlertasDeOtroPunto() throws Exception {
        long lote = lote(producto("Aceite de Loreto"), LORETO, 4, HOY.plusDays(2));
        revision.revisar();
        mockMvc.perform(get("/api/acopio/alertas").cookie(loginAs(ADMIN)))
                .andExpect(jsonPath("$.alertas[*].lote", not(hasItem("L-A" + lote))));
        // Al productor si le llega: es su lote
        mockMvc.perform(get("/api/productor/alertas").cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath("$.alertas[*].lote", hasItem("L-A" + lote)));
    }

    // --- CCPIP-337: estancados ---

    @Test
    void unLoteSinSalidasEnLosDiasConfiguradosEstaEstancadoHastaQueSeMueva() throws Exception {
        long producto = producto("Cacao estancado");
        long lote = lote(producto, LIMA, 10, HOY.plusDays(200));
        jdbc.update("UPDATE lote SET registrado_en = now() - interval '40 days' WHERE id = ?", lote);

        revision.revisar();
        mockMvc.perform(get("/api/productor/alertas").param("tipo", "ESTANCADO").cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath("$.alertas[*].tipo", everyItem(is("ESTANCADO"))))
                .andExpect(jsonPath(conLote(lote) + ".fecha", contains(HOY.minusDays(40).toString())));
        assertThat(abiertas("ESTANCADO", lote)).isEqualTo(2);

        // Una venta lo mueve: deja de estar estancado
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, motivo, usuario_id)
                SELECT l.producto_id, l.ubicacion_id, l.id, 'SALIDA', 1, 'RETIRO_PRODUCTOR', u.id
                FROM lote l, usuario u WHERE l.id = ? AND u.correo = ?""", lote, PRODUCTOR);
        jdbc.update("UPDATE lote SET cantidad_actual = cantidad_actual - 1 WHERE id = ?", lote);
        revision.revisar();
        assertThat(abiertas("ESTANCADO", lote)).isZero();
    }

    @Test
    void unLoteRecienIngresadoNoEstaEstancado() {
        long lote = lote(producto("Cacao nuevo"), LIMA, 10, HOY.plusDays(200));
        revision.revisar();
        assertThat(abiertas("ESTANCADO", lote)).isZero();
    }

    // --- CCPIP-293: reposicion ---

    @Test
    void unaOfertaPublicadaQueSeQuedaSinStockPideReposicion() throws Exception {
        long producto = producto("Sacha inchi agotado");
        long lote = lote(producto, LIMA, 5, HOY.plusDays(100));
        revision.revisar();
        assertThat(abiertas("REPOSICION", producto)).isZero();

        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE id = ?", lote);
        revision.revisar();
        mockMvc.perform(get("/api/productor/alertas").param("tipo", "REPOSICION").cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath(conProducto(producto) + ".porCorreo", contains(true)));

        jdbc.update("UPDATE lote SET cantidad_actual = 3 WHERE id = ?", lote);
        revision.revisar();
        assertThat(abiertas("REPOSICION", producto)).isZero();
    }

    // --- CCPIP-366: interes ---

    @Test
    void losInteresadosEnUnaOfertaLlegancomoUnaAlertaConPersonasYCantidad() throws Exception {
        long producto = productoConLote("Interes alerta " + UUID.randomUUID(), "PUBLICADO", null, null);
        jdbc.update("""
                INSERT INTO interes_compra (usuario_id, producto_id, productor_id, cantidad, motivo)
                SELECT u.id, p.id, p.productor_id, 4, 'AGOTADO' FROM usuario u, producto p
                WHERE u.correo = 'consumidor@ccpip.test' AND p.id = ?""", producto);
        revision.revisar();
        mockMvc.perform(get("/api/productor/alertas").param("tipo", "INTERES").cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath(conProducto(producto) + ".personas", contains(1)))
                .andExpect(jsonPath(conProducto(producto) + ".cantidad", contains(4)));
        assertThat(correos.ultimoPara(PRODUCTOR).orElseThrow().texto()).contains("1 persona(s) quieren 4 unidad(es)");
    }

    // --- CCPIP-367: eventos operativos ---

    @Test
    void unPedidoNuevoAvisaSoloAlProductorDeSusProductosYNoPorCorreo() throws Exception {
        long producto = producto("Aguaje para pedido");
        lote(producto, LIMA, 5, HOY.plusDays(100));
        pedidoConfirmado("CCP-A367", LIMA, producto, 1);
        revision.revisar();
        revision.revisar();

        mockMvc.perform(get("/api/productor/alertas").param("tipo", "PEDIDO_NUEVO").cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath(conReferencia("CCP-A367"), hasSize(1)))
                .andExpect(jsonPath(conReferencia("CCP-A367") + ".productos[0]", contains("Aguaje para pedido")))
                .andExpect(jsonPath(conReferencia("CCP-A367") + ".porCorreo", contains(false)));
        mockMvc.perform(get("/api/productor/alertas").param("tipo", "PEDIDO_NUEVO").cookie(loginAs(OTRO_PRODUCTOR)))
                .andExpect(jsonPath("$.alertas[*].referencia", not(hasItem("CCP-A367"))));
        assertThat(correos.enviados()).noneMatch(c -> c.texto().contains("CCP-A367"));
    }

    @Test
    void unaTransferenciaRecibidaAvisaAlProductor() throws Exception {
        long producto = producto("Aceite transferido");
        long origen = lote(producto, LIMA, 5, HOY.plusDays(100));
        Long transferencia = jdbc.queryForObject("""
                INSERT INTO transferencia (tipo, productor_id, origen_id, destino_id, estado)
                SELECT 'ENTRE_PUNTOS', p.productor_id, (SELECT id FROM ubicacion WHERE nombre = ?),
                       (SELECT id FROM ubicacion WHERE nombre = ?), 'RECIBIDA'
                FROM producto p WHERE p.id = ? RETURNING id""", Long.class, LIMA, LORETO, producto);
        jdbc.update("INSERT INTO transferencia_item (transferencia_id, producto_id, lote_origen_id, cantidad) VALUES (?, ?, ?, 2)",
                transferencia, producto, origen);
        revision.revisar();
        mockMvc.perform(get("/api/productor/alertas").param("tipo", "TRANSFERENCIA_RECIBIDA").cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath(conReferencia("TR-%04d".formatted(transferencia)) + ".ubicacion", contains(LORETO)))
                .andExpect(jsonPath(conReferencia("TR-%04d".formatted(transferencia)) + ".cantidad", contains(2.0)));
    }

    @Test
    void unTipoSilenciadoNoGeneraAlertasYSinCorreoNoSeEnvia() throws Exception {
        Cookie sesion = loginAs(PRODUCTOR);
        guardar(sesion, "/api/productor/alertas/preferencias", 15, 30, List.of("POR_VENCER"), false)
                .andExpect(status().isOk());
        long producto = producto("Aguaje silenciado");
        long lote = lote(producto, LIMA, 5, HOY.plusDays(4));
        pedidoConfirmado("CCP-SIL1", LIMA, producto, 1);
        revision.revisar();

        mockMvc.perform(get("/api/productor/alertas").cookie(sesion))
                .andExpect(jsonPath("$.alertas[*].tipo", not(hasItem("PEDIDO_NUEVO"))))
                .andExpect(jsonPath("$.alertas[*].lote", hasItem("L-A" + lote)));
        assertThat(correos.ultimoPara(PRODUCTOR)).isEmpty();
    }

    // --- Leidas ---

    @Test
    void marcaLeidasSusAlertasYNoLasDeOtro() throws Exception {
        lote(producto("Lote leido 1"), LIMA, 5, HOY.plusDays(3));
        lote(producto("Lote leido 2"), LIMA, 5, HOY.plusDays(4));
        revision.revisar();
        Cookie sesion = loginAs(PRODUCTOR);
        long id = idDeAlerta("Lote leido 1");

        mockMvc.perform(post("/api/productor/alertas/" + id + "/leida").cookie(loginAs(OTRO_PRODUCTOR)))
                .andExpect(status().isNotFound());
        int antes = sinLeer(sesion);
        mockMvc.perform(post("/api/productor/alertas/" + id + "/leida").cookie(sesion))
                .andExpect(status().isNoContent());
        assertThat(sinLeer(sesion)).isEqualTo(antes - 1);
        mockMvc.perform(post("/api/productor/alertas/leidas").cookie(sesion)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/productor/alertas").cookie(sesion))
                .andExpect(jsonPath("$.sinLeer").value(0))
                .andExpect(jsonPath("$.alertas[*].leida", everyItem(is(true))));
    }

    @Test
    void unCorreoCaidoQuedaFallidoSinDeshacerLaAlerta() throws Exception {
        long lote = lote(producto("Lote sin correo"), LIMA, 5, HOY.plusDays(3));
        correos.fallar(true);
        try {
            revision.revisar();
        } finally {
            correos.fallar(false);
        }
        assertThat(jdbc.queryForList("SELECT correo FROM alerta WHERE referencia_id = ? AND tipo = 'POR_VENCER'",
                String.class, lote)).containsOnly("FALLIDO");
    }

    // --- Apoyo ---

    private ResultActions guardar(Cookie sesion, String ruta, int diasVencimiento, int diasEstancado, List<String> tipos,
                                  boolean correo) throws Exception {
        return mockMvc.perform(put(ruta).cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("diasVencimiento", diasVencimiento, "diasEstancado", diasEstancado,
                        "tipos", tipos, "correo", correo))));
    }

    private long producto(String nombre) {
        return productoConLote(nombre, "PUBLICADO", null, null);
    }

    /** Un lote con codigo L-A{id}, para encontrar su alerta en la bandeja. */
    private long lote(long producto, String punto, int cantidad, LocalDate vence) {
        Long id = jdbc.queryForObject("""
                INSERT INTO lote (id, producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion,
                                  fecha_vencimiento)
                SELECT s.n, ?, u.id, 'L-A' || s.n, ?, ?, ?::date - 300, ?
                FROM (SELECT nextval('lote_id_seq') AS n) s, ubicacion u WHERE u.nombre = ?
                RETURNING id""", Long.class, producto, cantidad, cantidad, vence, vence, punto);
        return id == null ? 0 : id;
    }

    /** JSONPath de las alertas de la bandeja con ese lote, producto o referencia. */
    private static String conLote(long lote) {
        return "$.alertas[?(@.lote == 'L-A" + lote + "')]";
    }

    private static String conProducto(long producto) {
        return "$.alertas[?(@.productoId == " + producto + ")]";
    }

    private static String conReferencia(String referencia) {
        return "$.alertas[?(@.referencia == '" + referencia + "')]";
    }

    private int sinLeer(Cookie sesion) throws Exception {
        String cuerpo = mockMvc.perform(get("/api/productor/alertas").cookie(sesion))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(cuerpo).get("sinLeer").asInt();
    }

    private long ubicacion(String nombre) {
        Long id = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, nombre);
        return id == null ? 0 : id;
    }

    private int abiertas(String tipo, long referencia) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM alerta WHERE tipo = ? AND referencia_id = ? AND resuelta_en IS NULL",
                Integer.class, tipo, referencia);
        return n == null ? 0 : n;
    }

    private long idDeAlerta(String producto) {
        Long id = jdbc.queryForObject("""
                SELECT a.id FROM alerta a JOIN producto p ON p.id = a.producto_id
                WHERE p.nombre = ? AND a.productor_id IS NOT NULL""", Long.class, producto);
        return id == null ? 0 : id;
    }
}
