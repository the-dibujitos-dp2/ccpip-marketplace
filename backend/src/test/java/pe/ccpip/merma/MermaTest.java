package pe.ccpip.merma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Merma (Figma A-04 y A-07) y su catalogo de causas:
 * CCPIP-76 marcar merma con confirmacion, CCPIP-75 merma automatica por vencimiento,
 * CCPIP-77, 298, 299 y 300 consultar, crear, modificar y desactivar causas.
 *
 * <p>Transaccional: lo que crea cada prueba no llega a las demas. La merma automatica no corre sola
 * durante la suite (ver IntegrationTest); aqui se llama a {@code revisar()}.
 */
@Transactional
class MermaTest extends IntegrationTest {

    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    private static final String ADMIN = "admin_acopio@ccpip.test";
    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String SUPER = "sudo@ccpip.test";
    private static final String ACOPIO = "/api/acopio/merma";
    private static final String ALMACEN = "/api/productor/merma";
    private static final String CATALOGO = "/api/admin/motivos-merma";

    @Autowired
    private MermaPorVencimiento automatica;

    // --- CCPIP-76: marcar merma ---

    @Test
    void elPuntoMarcaMermaConConfirmacionYQuedaConResponsableYFecha() throws Exception {
        long producto = producto("Harina de aguaje merma");
        long lote = lote(producto, "L-0240", ubicacion("Centro Nacional Lima"), "9", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        long dano = causa("Daño");

        // Sin la confirmacion expresa no se marca nada
        marcar(admin, ACOPIO, cuerpo(lote, "5", dano, null, false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIRMACION_REQUERIDA"));
        assertThat(saldo(lote)).isEqualByComparingTo("9");

        Map<String, Object> envio = cuerpo(lote, "5", dano, "Humedad en el empaque", true);
        marcar(admin, ACOPIO, envio)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldo").value(4.0))
                .andExpect(jsonPath("$.repetida").value(false))
                .andExpect(jsonPath("$.merma.lote").value("L-0240"))
                .andExpect(jsonPath("$.merma.cantidad").value(5.0))
                .andExpect(jsonPath("$.merma.motivo").value("Daño"))
                .andExpect(jsonPath("$.merma.motivoEn").value("Damage"))
                .andExpect(jsonPath("$.merma.detalle").value("Humedad en el empaque"))
                .andExpect(jsonPath("$.merma.automatica").value(false))
                .andExpect(jsonPath("$.merma.registradoPor").isString())
                .andExpect(jsonPath("$.merma.registradoEn").isString());
        assertThat(saldo(lote)).isEqualByComparingTo("4");
        assertThat(stock(producto, ubicacion("Centro Nacional Lima"))).isEqualByComparingTo("4");
        assertThat(contar("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ? AND tipo = 'MERMA'", lote)).isOne();

        // El mismo envio otra vez no vuelve a descontar
        marcar(admin, ACOPIO, envio).andExpect(status().isOk()).andExpect(jsonPath("$.repetida").value(true));
        assertThat(saldo(lote)).isEqualByComparingTo("4");

        assertThat(contar("SELECT count(*) FROM merma WHERE lote_id = ?", lote)).isOne();
    }

    @Test
    void laCantidadNoExcedeLoDisponible() throws Exception {
        long lote = lote(producto("Cacao merma"), "L-0198", ubicacion("Centro Nacional Lima"), "3", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(lote, "3.5", causa("Pérdida"), null, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALDO_INSUFICIENTE"));
        marcar(admin, ACOPIO, cuerpo(lote, "0", causa("Pérdida"), null, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").isString());
        assertThat(saldo(lote)).isEqualByComparingTo("3");
    }

    @Test
    void otraExigeDescribirLaCausaYUnaCausaInactivaNoSeAcepta() throws Exception {
        long lote = lote(producto("Aceite merma"), "L-0231", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(lote, "1", causa("Otra"), " ", true))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DETALLE_REQUERIDO"));
        marcar(admin, ACOPIO, cuerpo(lote, "1", causa("Otra"), "Roedores en el almacén", true))
                .andExpect(status().isCreated());

        jdbc.update("UPDATE motivo_merma SET activo = false WHERE nombre = 'Derrame'");
        marcar(admin, ACOPIO, cuerpo(lote, "1", causa("Derrame"), null, true))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MOTIVO_INVALIDO"));
    }

    @Test
    void cadaUnoMarcaSoloLoQueTieneACargo() throws Exception {
        long producto = producto("Camu camu merma");
        long enLoreto = lote(producto, "L-LOR", ubicacion("Centro Regional Loreto"), "5", HOY.plusDays(60));
        long enPunto = lote(producto, "L-LIM", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        long enAlmacen = lote(producto, "L-ALM", almacenDe(PRODUCTOR), "5", HOY.plusDays(60));
        long dano = causa("Daño");

        // El punto: solo sus puntos (RNF-05)
        marcar(loginAs(ADMIN), ACOPIO, cuerpo(enLoreto, "1", dano, null, true)).andExpect(status().isNotFound());
        marcar(loginAs(ADMIN), ACOPIO, cuerpo(enAlmacen, "1", dano, null, true)).andExpect(status().isNotFound());
        // El productor: solo su almacen; lo que esta en un punto lo marca el punto
        Cookie productor = loginAs(PRODUCTOR);
        marcar(productor, ALMACEN, cuerpo(enPunto, "1", dano, null, true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOTE_NO_ENCONTRADO"));
        marcar(productor, ALMACEN, cuerpo(enAlmacen, "2", dano, null, true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldo").value(3.0));
        marcar(loginAs("productor2@ccpip.test"), ALMACEN, cuerpo(enAlmacen, "1", dano, null, true))
                .andExpect(status().isNotFound());
        marcar(loginAs("consumidor@ccpip.test"), ALMACEN, cuerpo(enAlmacen, "1", dano, null, true))
                .andExpect(status().isForbidden());
    }

    @Test
    void elProductorVeLaMermaDeSusLotesSinElNombreDeQuienLaMarcoEnElPunto() throws Exception {
        long producto = producto("Chocolate merma");
        long enPunto = lote(producto, "L-PUN", ubicacion("Centro Nacional Lima"), "6", HOY.plusDays(60));
        marcar(loginAs(ADMIN), ACOPIO, cuerpo(enPunto, "2", causa("Accidente"), null, true)).andExpect(status().isCreated());

        mockMvc.perform(get(ALMACEN).cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.lote == 'L-PUN')].motivo").value(hasItem("Accidente")))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-PUN')].ubicacion").value(hasItem("Centro Nacional Lima")))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-PUN')].registradoPor").isEmpty());
        mockMvc.perform(get(ACOPIO).cookie(loginAs(ADMIN)))
                .andExpect(jsonPath("$.ubicacion").value("Centro Nacional Lima"))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-PUN')].registradoPor").value(hasItem("Administrador de Acopio de Prueba")));
        mockMvc.perform(get(ALMACEN).cookie(loginAs("productor2@ccpip.test")))
                .andExpect(jsonPath("$.items[*].lote", not(hasItem("L-PUN"))));
    }

    // --- CCPIP-423: el productor del lote en la merma del punto ---

    @Test
    void elPuntoVeElProductorDeCadaLoteYSoloLaMermaDeSusPuntos() throws Exception {
        long lima = ubicacion("Centro Nacional Lima");
        long deUno = lote(productoDe("Aguaje productor uno", PRODUCTOR), "L-PR1", lima, "5", HOY.plusDays(60));
        long deDos = lote(productoDe("Cacao productor dos", "productor2@ccpip.test"), "L-PR2", lima, "5", HOY.plusDays(60));
        long enLoreto = lote(productoDe("Camu camu Loreto", "productor2@ccpip.test"), "L-PRL",
                ubicacion("Centro Regional Loreto"), "5", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(deUno, "1", causa("Daño"), null, true)).andExpect(status().isCreated());
        marcar(admin, ACOPIO, cuerpo(deDos, "2", causa("Pérdida"), null, true)).andExpect(status().isCreated());
        // Merma en un punto que no es suyo: la registra el sistema por vencimiento
        jdbc.update("UPDATE lote SET fecha_vencimiento = ? WHERE id = ?", HOY.minusDays(1), enLoreto);
        automatica.revisar();
        assertThat(contar("SELECT count(*) FROM merma WHERE lote_id = ?", enLoreto)).isOne();

        // El nombre publico: la tienda o, sin ella, la comunidad
        String uno = nombrePublico(PRODUCTOR);
        String dos = nombrePublico("productor2@ccpip.test");
        assertThat(uno).isNotBlank().isNotEqualTo(dos);

        mockMvc.perform(get(ACOPIO).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.lote == 'L-PR1')].productor").value(hasItem(uno)))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-PR1')].productorId").value(hasItem((int) productorDe(PRODUCTOR))))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-PR2')].productor").value(hasItem(dos)))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-PR2')].productorId")
                        .value(hasItem((int) productorDe("productor2@ccpip.test"))))
                // Sin datos de contacto del productor
                .andExpect(jsonPath("$.items[0].correo").doesNotExist())
                .andExpect(jsonPath("$.items[0].telefono").doesNotExist())
                // RNF-05: la merma de otro punto no aparece
                .andExpect(jsonPath("$.items[*].lote", not(hasItem("L-PRL"))));
    }

    @Test
    void laMermaEsUnaSalidaEnLosMovimientosDelPunto() throws Exception {
        long lote = lote(producto("Infusion merma"), "L-MOV", ubicacion("Centro Nacional Lima"), "4", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(lote, "1", causa("Daño"), null, true)).andExpect(status().isCreated());
        mockMvc.perform(get("/api/acopio/movimientos").param("tipo", "SALIDAS").cookie(admin))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-MOV')].tipo").value(hasItem("MERMA")));
    }

    // --- CCPIP-75: merma automatica por vencimiento ---

    @Test
    void unLoteVencidoPasaAMermaAutomaticaUnaSolaVez() throws Exception {
        long producto = producto("Aguaje vencido");
        long lima = ubicacion("Centro Nacional Lima");
        long vencido = lote(producto, "L-0236", lima, "6", HOY.plusDays(30));
        long vigente = lote(producto, "L-0237", lima, "6", HOY);
        jdbc.update("UPDATE lote SET fecha_vencimiento = ? WHERE id = ?", HOY.minusDays(1), vencido);

        assertThat(automatica.revisar()).isGreaterThanOrEqualTo(1);
        assertThat(automatica.revisar()).isZero();

        assertThat(saldo(vencido)).isEqualByComparingTo("0");
        // Vence hoy: todavia no es merma
        assertThat(saldo(vigente)).isEqualByComparingTo("6");
        assertThat(stock(producto, lima)).isEqualByComparingTo("6");
        assertThat(contar("SELECT count(*) FROM merma WHERE lote_id = ? AND automatica", vencido)).isOne();

        String filtro = "$.items[?(@.lote == 'L-0236')]";
        mockMvc.perform(get(ACOPIO).cookie(loginAs(ADMIN)))
                .andExpect(jsonPath(filtro + ".automatica").value(hasItem(true)))
                .andExpect(jsonPath(filtro + ".motivo").value(hasItem("Vencimiento")))
                .andExpect(jsonPath(filtro + ".cantidad").value(hasItem(6.0)))
                .andExpect(jsonPath(filtro + ".registradoPor").isEmpty());
        mockMvc.perform(get(ALMACEN).cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath(filtro + ".automatica").value(hasItem(true)));
    }

    // --- CCPIP-77, 298, 299 y 300: catalogo de causas ---

    @Test
    void elSuperadministradorConsultaYCreaCausas() throws Exception {
        Cookie sudo = loginAs(SUPER);
        mockMvc.perform(get(CATALOGO).cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Vencimiento"))
                .andExpect(jsonPath("$[0].sistema").value(true))
                .andExpect(jsonPath("$[*].nombre", hasItem("Otra")));

        guardar(sudo, post(CATALOGO), "Plaga", "Pest", false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Plaga"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.usos").value(0));
        guardar(sudo, post(CATALOGO), " plaga ", "Pests", false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOTIVO_REPETIDO"));
        guardar(sudo, post(CATALOGO), "", "Pest", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nombre").isString());

        mockMvc.perform(get("/api/merma/causas").cookie(loginAs(ADMIN)))
                .andExpect(jsonPath("$[*].nombre", hasItem("Plaga")));
        assertThat(contar("SELECT count(*) FROM motivo_merma_cambio c JOIN motivo_merma m ON m.id = c.motivo_id WHERE m.nombre = 'Plaga'"))
                .isOne();
    }

    @Test
    void modificarUnaCausaNoAlteraLaMermaYaMarcada() throws Exception {
        long lote = lote(producto("Sacha inchi merma"), "L-HIS", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        long dano = causa("Daño");
        marcar(loginAs(ADMIN), ACOPIO, cuerpo(lote, "1", dano, null, true)).andExpect(status().isCreated());

        Cookie sudo = loginAs(SUPER);
        guardar(sudo, put(CATALOGO + "/" + dano), "Daño físico", "Physical damage", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Daño físico"))
                .andExpect(jsonPath("$.usos").value(1));

        mockMvc.perform(get(ACOPIO).cookie(loginAs(ADMIN)))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-HIS')].motivo").value(hasItem("Daño")));
        assertThat(contar("SELECT count(*) FROM motivo_merma_cambio WHERE motivo_id = ? AND accion = 'MODIFICADO'", dano)).isOne();
    }

    @Test
    void desactivarUnaCausaLaQuitaDelFormularioYConservaSuHistorial() throws Exception {
        long lote = lote(producto("Cafe merma"), "L-DES", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        long derrame = causa("Derrame");
        marcar(loginAs(ADMIN), ACOPIO, cuerpo(lote, "1", derrame, null, true)).andExpect(status().isCreated());

        Cookie sudo = loginAs(SUPER);
        mockMvc.perform(post(CATALOGO + "/" + derrame + "/desactivar").cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false))
                .andExpect(jsonPath("$.usos").value(1));
        mockMvc.perform(get("/api/merma/causas").cookie(loginAs(ADMIN)))
                .andExpect(jsonPath("$[*].nombre", not(hasItem("Derrame"))));
        mockMvc.perform(get(ACOPIO).cookie(loginAs(ADMIN)))
                .andExpect(jsonPath("$.items[?(@.lote == 'L-DES')].motivo").value(hasItem("Derrame")));

        // La causa de la merma automatica no se desactiva
        mockMvc.perform(post(CATALOGO + "/" + causa("Vencimiento") + "/desactivar").cookie(sudo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOTIVO_DEL_SISTEMA"));

        mockMvc.perform(post(CATALOGO + "/" + derrame + "/activar").cookie(sudo))
                .andExpect(jsonPath("$.activo").value(true));
        mockMvc.perform(post(CATALOGO + "/999999/desactivar").cookie(sudo)).andExpect(status().isNotFound());
    }

    @Test
    void soloElSuperadministradorAdministraElCatalogo() throws Exception {
        mockMvc.perform(get(CATALOGO).cookie(loginAs("sub_admin@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get(CATALOGO).cookie(loginAs(ADMIN))).andExpect(status().isForbidden());
        guardar(loginAs(PRODUCTOR), post(CATALOGO), "Robo", "Theft", false).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/merma/causas").cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
    }

    // --- Apoyo ---

    private ResultActions marcar(Cookie quien, String ruta, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post(ruta).cookie(quien).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private ResultActions guardar(Cookie quien, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder peticion,
                                  String nombre, String nombreEn, boolean requiereDetalle) throws Exception {
        return mockMvc.perform(peticion.cookie(quien).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nombre", nombre, "nombreEn", nombreEn, "requiereDetalle", requiereDetalle))));
    }

    private static Map<String, Object> cuerpo(long lote, String cantidad, long motivo, String detalle, boolean confirmada) {
        Map<String, Object> c = new HashMap<>();
        c.put("loteId", lote);
        c.put("cantidad", cantidad);
        c.put("motivoId", motivo);
        c.put("detalle", detalle);
        c.put("clave", UUID.randomUUID().toString());
        c.put("confirmada", confirmada);
        return c;
    }

    private long producto(String nombre) {
        return productoDe(nombre, PRODUCTOR);
    }

    private long productoDe(String nombre, String correo) {
        return productoConLote(nombre + " " + UUID.randomUUID(), "PUBLICADO", null, null, correo);
    }

    private long productorDe(String correo) {
        Long id = jdbc.queryForObject("SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?",
                Long.class, correo);
        return id == null ? 0 : id;
    }

    private String nombrePublico(String correo) {
        return jdbc.queryForObject("""
                SELECT coalesce(p.nombre_tienda, p.comunidad) FROM productor p JOIN usuario u ON u.id = p.usuario_id
                WHERE u.correo = ?""", String.class, correo);
    }

    /** Un lote con su ENTRADA y su stock, como lo deja el registro del lote (PW-06). */
    private long lote(long producto, String codigo, long ubicacion, String cantidad, LocalDate vence) {
        BigDecimal q = new BigDecimal(cantidad);
        Long id = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                  fecha_produccion, fecha_vencimiento)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                producto, ubicacion, codigo, q, q, HOY.minusDays(60), vence);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion)
                VALUES (?, ?, ?, 'ENTRADA', ?, 'Registro del lote')""", producto, ubicacion, id, q);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, ?)
                ON CONFLICT (producto_id, ubicacion_id) DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad""",
                producto, ubicacion, q);
        return id == null ? 0 : id;
    }

    private long ubicacion(String nombre) {
        Long id = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, nombre);
        return id == null ? 0 : id;
    }

    private long almacenDe(String correo) {
        Long id = jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN productor p ON p.id = u.productor_id JOIN usuario x ON x.id = p.usuario_id
                WHERE x.correo = ? AND u.tipo = 'ALMACEN_PRODUCTOR'""", Long.class, correo);
        return id == null ? 0 : id;
    }

    private long causa(String nombre) {
        Long id = jdbc.queryForObject("SELECT id FROM motivo_merma WHERE nombre = ?", Long.class, nombre);
        return id == null ? 0 : id;
    }

    private BigDecimal saldo(long lote) {
        return jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE id = ?", BigDecimal.class, lote);
    }

    private BigDecimal stock(long producto, long ubicacion) {
        return jdbc.queryForList("SELECT cantidad FROM stock WHERE producto_id = ? AND ubicacion_id = ?",
                BigDecimal.class, producto, ubicacion).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    private long contar(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
