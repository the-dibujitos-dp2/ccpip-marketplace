package pe.ccpip.merma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
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
    private static final String REPORTE_ACOPIO = "/api/acopio/merma";
    private static final String REPORTE_PRODUCTOR = "/api/productor/merma";
    private static final String CATALOGO = "/api/admin/motivos-merma";

    @Autowired
    private MermaPorVencimiento automatica;

    // --- CCPIP-120: revision de una merma registrada por error ---

    private static final String SUB = "sub_admin@ccpip.test";

    /** Una merma manual de {@code cantidad} en un lote nuevo de 10 und del punto de Lima. */
    private long[] mermaEnLima(String nombre, String cantidad) throws Exception {
        long producto = producto(nombre);
        long punto = ubicacion("Centro Nacional Lima");
        long lote = lote(producto, "L-RM-" + UUID.randomUUID().toString().substring(0, 8), punto, "10", HOY.plusDays(60));
        marcar(loginAs(ADMIN), ACOPIO, cuerpo(lote, cantidad, causa("Daño"), null, true)).andExpect(status().isCreated());
        long merma = jdbc.queryForObject("SELECT movimiento_id FROM merma WHERE lote_id = ?", Long.class, lote);
        return new long[] {merma, lote, producto, punto};
    }

    private ResultActions pedirRevision(Cookie quien, long merma, Object cantidad, String motivo) throws Exception {
        Map<String, Object> c = new HashMap<>();
        c.put("cantidad", cantidad);
        c.put("motivo", motivo);
        return mockMvc.perform(post(ACOPIO + "/" + merma + "/revision").cookie(quien)
                .contentType(MediaType.APPLICATION_JSON).content(json(c)));
    }

    private ResultActions resolver(Cookie quien, long revision, String accion, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post("/api/admin/merma/revisiones/" + revision + "/" + accion).cookie(quien)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private long revisionDe(long merma) {
        return contar("SELECT max(id) FROM solicitud_revision_merma WHERE merma_movimiento_id = ?", merma);
    }

    @Test
    void ccpip120PedirLaRevisionNoTocaElInventarioYSoloHayUnaAbierta() throws Exception {
        long[] m = mermaEnLima("Arroz revisión", "4");
        Cookie admin = loginAs(ADMIN);
        var loteAntes = jdbc.queryForList("SELECT * FROM lote WHERE id = ?", m[1]);
        var stockAntes = stock(m[2], m[3]);
        long movimientosAntes = contar("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ?", m[1]);

        pedirRevision(admin, m[0], "3", "Se registraron 4 en vez de 1 por error de digitación")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mermaId").value(m[0]))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.cantidad").value(3))
                .andExpect(jsonPath("$.cantidadMerma").value(4))
                .andExpect(jsonPath("$.solicitante").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.punto").value("Centro Nacional Lima"));

        assertThat(jdbc.queryForList("SELECT * FROM lote WHERE id = ?", m[1])).isEqualTo(loteAntes);
        assertThat(stock(m[2], m[3])).isEqualByComparingTo(stockAntes);
        assertThat(contar("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ?", m[1])).isEqualTo(movimientosAntes);
        mockMvc.perform(get(ACOPIO + "/revisiones").cookie(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.mermaId == " + m[0] + ")].estado").value(hasItem("PENDIENTE")));

        // Una sola abierta por merma; y nunca por mas de lo que se dio de baja ni sin explicar por que
        pedirRevision(admin, m[0], "1", "Otra solicitud sobre la misma merma")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_EXISTENTE"));
        long[] otra = mermaEnLima("Arroz revisión cantidad", "2");
        pedirRevision(admin, otra[0], "3", "Más de lo que se dio de baja")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));
        pedirRevision(admin, otra[0], "0", "Cero unidades no tiene sentido")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));
        pedirRevision(admin, otra[0], "1", "corto")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MOTIVO_REQUERIDO"));
    }

    @Test
    void ccpip120SoloElPuntoDeLaMermaPideRevisionYLaAutomaticaNoSeRevisa() throws Exception {
        long[] m = mermaEnLima("Papa revisión alcance", "2");
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'admin_otro_punto_rm@ccpip.test', contrasena_hash, 'Admin de otro punto', rol_id, true
                FROM usuario WHERE correo = ?""", ADMIN);
        long otro = contar("SELECT id FROM usuario WHERE correo = 'admin_otro_punto_rm@ccpip.test'");
        jdbc.update("INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id) VALUES (?, ?)", otro, ubicacion("Centro Regional Loreto"));
        pedirRevision(loginAs("admin_otro_punto_rm@ccpip.test"), m[0], "1", "No es de mi punto pero lo intento")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MERMA_NO_ENCONTRADA"));
        mockMvc.perform(get(ACOPIO + "/revisiones").cookie(loginAs("admin_otro_punto_rm@ccpip.test"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].mermaId", not(hasItem((int) m[0]))));
        pedirRevision(loginAs(ADMIN), 987654321L, "1", "Una merma que no existe")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MERMA_NO_ENCONTRADA"));

        for (String correo : new String[] {"consumidor@ccpip.test", PRODUCTOR, SUB, SUPER, "distribuidor@ccpip.test"}) {
            pedirRevision(loginAs(correo), m[0], "1", "Un rol que no pide revisiones").andExpect(status().isForbidden());
        }

        long producto = producto("Leche revisión vencida");
        long lote = lote(producto, "L-RMA-" + UUID.randomUUID().toString().substring(0, 8), ubicacion("Centro Nacional Lima"), "3", HOY.minusDays(1));
        automatica.revisar();
        long auto = jdbc.queryForObject("SELECT movimiento_id FROM merma WHERE lote_id = ? AND automatica", Long.class, lote);
        pedirRevision(loginAs(ADMIN), auto, "1", "La merma por vencimiento fue un error")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MERMA_AUTOMATICA"));
    }

    @Test
    void ccpip120AlAprobarLasUnidadesVuelvenAlLoteConUnMovimientoNuevoYLaMermaNoCambia() throws Exception {
        long[] m = mermaEnLima("Café revisión aprobada", "5");
        pedirRevision(loginAs(ADMIN), m[0], "4", "Se marcaron 5 pero solo se dañó 1").andExpect(status().isCreated());
        long revision = revisionDe(m[0]);
        var mermaAntes = jdbc.queryForList("SELECT * FROM merma WHERE movimiento_id = ?", m[0]);
        var movMermaAntes = jdbc.queryForList("SELECT * FROM movimiento_inventario WHERE id = ?", m[0]);
        BigDecimal saldoAntes = saldo(m[1]);
        BigDecimal stockAntes = stock(m[2], m[3]);
        Cookie sub = loginAs(SUB);

        mockMvc.perform(get("/api/admin/merma/revisiones").param("estado", "PENDIENTE").cookie(sub)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + revision + ")].producto").value(hasItem(org.hamcrest.Matchers.startsWith("Café revisión aprobada"))));
        // El punto no resuelve su propia revision
        resolver(loginAs(ADMIN), revision, "aprobar", Map.of()).andExpect(status().isForbidden());
        // Nunca mas de lo pedido
        resolver(sub, revision, "aprobar", Map.of("cantidad", "5")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));

        resolver(sub, revision, "aprobar", Map.of("cantidad", "3", "respuesta", "Se comprobó con el conteo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.cantidadAprobada").value(3))
                .andExpect(jsonPath("$.respuesta").value("Se comprobó con el conteo"))
                .andExpect(jsonPath("$.resueltaPor").isString());

        assertThat(saldo(m[1])).isEqualByComparingTo(saldoAntes.add(new BigDecimal("3")));
        assertThat(stock(m[2], m[3])).isEqualByComparingTo(stockAntes.add(new BigDecimal("3")));
        long subId = contar("SELECT id FROM usuario WHERE correo = ?", SUB);
        assertThat(contar("""
                SELECT count(*) FROM movimiento_inventario
                WHERE lote_id = ? AND tipo = 'ENTRADA' AND motivo = 'CORRECCION_MERMA' AND cantidad = 3 AND usuario_id = ?""",
                m[1], subId)).isOne();
        assertThat(jdbc.queryForList("SELECT * FROM merma WHERE movimiento_id = ?", m[0])).isEqualTo(mermaAntes);
        assertThat(jdbc.queryForList("SELECT * FROM movimiento_inventario WHERE id = ?", m[0])).isEqualTo(movMermaAntes);

        // Ya resuelta: no se aprueba ni se rechaza otra vez, y la merma no admite otra revision
        resolver(sub, revision, "aprobar", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_RESUELTA"));
        resolver(sub, revision, "rechazar", Map.of("respuesta", "Tarde")).andExpect(status().isConflict());
        pedirRevision(loginAs(ADMIN), m[0], "1", "Pido otra revisión de lo mismo")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_EXISTENTE"));
        mockMvc.perform(get(ACOPIO + "/revisiones").cookie(loginAs(ADMIN))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + revision + ")].estado").value(hasItem("APROBADA")));

        // Queda en la auditoria: quien la pidio y quien la resolvio
        mockMvc.perform(get("/api/admin/auditoria").param("entidad", "MOVIMIENTO").cookie(loginAs(SUPER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[?(@.clave == 'SR-" + revision + "')].accion").value(hasItem("REVISION_MERMA_SOLICITADA")))
                .andExpect(jsonPath("$.registros[?(@.clave == 'RR-" + revision + "')].accion").value(hasItem("REVISION_MERMA_APROBADA")));
    }

    @Test
    void ccpip120AlRechazarElInventarioNoCambiaYSePuedeVolverAPedir() throws Exception {
        long[] m = mermaEnLima("Quinua revisión rechazada", "2");
        pedirRevision(loginAs(ADMIN), m[0], "2", "Creo que no se dañaron, revisar").andExpect(status().isCreated());
        long revision = revisionDe(m[0]);
        BigDecimal saldoAntes = saldo(m[1]);
        long movimientosAntes = contar("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ?", m[1]);
        Cookie sub = loginAs(SUB);

        resolver(sub, revision, "rechazar", Map.of("respuesta", " ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESPUESTA_REQUERIDA"));
        resolver(sub, revision, "rechazar", Map.of("respuesta", "Las fotos muestran el daño"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.respuesta").value("Las fotos muestran el daño"));
        assertThat(saldo(m[1])).isEqualByComparingTo(saldoAntes);
        assertThat(contar("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ?", m[1])).isEqualTo(movimientosAntes);

        pedirRevision(loginAs(ADMIN), m[0], "1", "Con nueva evidencia del conteo").andExpect(status().isCreated());
        resolver(sub, 987654321L, "rechazar", Map.of("respuesta", "No existe")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NO_ENCONTRADA"));
    }

    @Test
    void ccpip120NoSeReintegraAUnLoteVencido() throws Exception {
        long[] m = mermaEnLima("Yogur revisión lote vencido", "2");
        pedirRevision(loginAs(ADMIN), m[0], "2", "Se registró en el lote equivocado").andExpect(status().isCreated());
        jdbc.update("UPDATE lote SET fecha_vencimiento = ?, fecha_produccion = ? WHERE id = ?", HOY.minusDays(1), HOY.minusDays(90), m[1]);
        BigDecimal saldoAntes = saldo(m[1]);
        resolver(loginAs(SUB), revisionDe(m[0]), "aprobar", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOTE_VENCIDO"));
        assertThat(saldo(m[1])).isEqualByComparingTo(saldoAntes);
    }

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

    // --- CCPIP-422: filtros de la merma del punto ---

    @Test
    void elPuntoFiltraLaMermaPorCausaProductoProductorYFechas() throws Exception {
        long lima = ubicacion("Centro Nacional Lima");
        long aguaje = productoDe("Aguaje filtro", PRODUCTOR);
        long cacao = productoDe("Cacao filtro", "productor2@ccpip.test");
        long deAguaje = lote(aguaje, "L-FA1", lima, "5", HOY.plusDays(60));
        long deCacao = lote(cacao, "L-FC1", lima, "5", HOY.plusDays(60));
        long dano = causa("Daño");
        long perdida = causa("Pérdida");
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(deAguaje, "1", dano, null, true)).andExpect(status().isCreated());
        marcar(admin, ACOPIO, cuerpo(deCacao, "1", perdida, null, true)).andExpect(status().isCreated());
        marcar(admin, ACOPIO, cuerpo(deCacao, "1", dano, null, true)).andExpect(status().isCreated());
        long dos = productorDe("productor2@ccpip.test");

        // Las opciones salen de toda su merma, sin filtrar
        mockMvc.perform(get(ACOPIO).param("causa", String.valueOf(dano)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].motivo", not(hasItem("Pérdida"))))
                .andExpect(jsonPath("$.opciones.causas[*].id").value(hasItem((int) perdida)))
                .andExpect(jsonPath("$.opciones.causas[?(@.id == " + dano + ")].nombreEn").value(hasItem("Damage")))
                .andExpect(jsonPath("$.opciones.productos[*].id").value(hasItem((int) aguaje)))
                .andExpect(jsonPath("$.opciones.productos[*].id").value(hasItem((int) cacao)))
                .andExpect(jsonPath("$.opciones.productores[*].id").value(hasItem((int) dos)));

        mockMvc.perform(get(ACOPIO).param("producto", String.valueOf(aguaje)).cookie(admin))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].lote").value("L-FA1"));

        // Combinados: productor y causa
        mockMvc.perform(get(ACOPIO).param("productor", String.valueOf(dos)).param("causa", String.valueOf(perdida)).cookie(admin))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].motivo").value("Pérdida"));

        // Rango de fechas: dias de Lima, inclusive en ambos extremos
        mockMvc.perform(get(ACOPIO).param("producto", String.valueOf(cacao))
                        .param("desde", HOY.toString()).param("hasta", HOY.toString()).cookie(admin))
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(get(ACOPIO).param("producto", String.valueOf(cacao)).param("desde", HOY.plusDays(1).toString()).cookie(admin))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get(ACOPIO).param("producto", String.valueOf(cacao)).param("hasta", HOY.minusDays(1).toString()).cookie(admin))
                .andExpect(jsonPath("$.total").value(0));

        // Un rango al reves no se acepta
        mockMvc.perform(get(ACOPIO).param("desde", HOY.toString()).param("hasta", HOY.minusDays(1).toString()).cookie(admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
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

    // --- CCPIP-78: reportes de merma ---

    @Test
    void elReporteFiltraPorPeriodoLoteProductoYUbicacionSinCambiarLaPaginacion() throws Exception {
        long producto = producto("Producto reporte merma");
        long primero = lote(producto, "REP-UNO", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        long segundo = lote(producto, "REP-DOS", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(primero, "1", causa("Daño"), null, true)).andExpect(status().isCreated());
        marcar(admin, ACOPIO, cuerpo(segundo, "1", causa("Daño"), null, true)).andExpect(status().isCreated());

        mockMvc.perform(get(REPORTE_ACOPIO).cookie(admin)
                        .param("desde", HOY.toString()).param("hasta", HOY.toString())
                        .param("lote", "REP-UNO").param("producto", "Producto reporte")
                        .param("ubicacion", "Centro Nacional Lima").param("pagina", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamano").value(20))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalPaginas").value(1))
                .andExpect(jsonPath("$.items[0].lote").value("REP-UNO"));

        mockMvc.perform(get(REPORTE_ACOPIO).cookie(admin).param("lote", "NO-EXISTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get(REPORTE_ACOPIO).cookie(admin).param("desde", HOY.toString()).param("hasta", HOY.minusDays(1).toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
    }

    @Test
    void elPuntoFiltraLaMermaPorElNombreDelProductorComoPorLoteYProducto() throws Exception {
        long lima = ubicacion("Centro Nacional Lima");
        long deUno = lote(productoDe("Aguaje productor uno", PRODUCTOR), "L-PR1", lima, "5", HOY.plusDays(60));
        long deDos = lote(productoDe("Cacao productor dos", "productor2@ccpip.test"), "L-PR2", lima, "5", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(deUno, "1", causa("Daño"), null, true)).andExpect(status().isCreated());
        marcar(admin, ACOPIO, cuerpo(deDos, "1", causa("Daño"), null, true)).andExpect(status().isCreated());
        String dos = jdbc.queryForObject("SELECT coalesce(nombre_tienda, comunidad) FROM productor WHERE id = ?", String.class,
                productorDe("productor2@ccpip.test"));

        // CCPIP-417: parte del nombre, sin distinguir mayusculas, y combinado con los demas textos
        mockMvc.perform(get(ACOPIO).cookie(admin).param("productor", dos.substring(0, 5).toUpperCase()).param("lote", "L-PR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].lote").value("L-PR2"))
                .andExpect(jsonPath("$.items[0].productor").value(dos));
        mockMvc.perform(get(ACOPIO).cookie(admin).param("productor", "Nadie se llama asi"))
                .andExpect(jsonPath("$.total").value(0));
        // Un numero sigue siendo el id del productor (CCPIP-422)
        mockMvc.perform(get(ACOPIO).cookie(admin).param("productor", String.valueOf(productorDe("productor2@ccpip.test")))
                        .param("producto", "Cacao productor dos"))
                .andExpect(jsonPath("$.items[*].lote").value(hasItem("L-PR2")));
        // La descarga respeta el mismo filtro
        byte[] excel = mockMvc.perform(get(REPORTE_ACOPIO + "/xlsx").cookie(admin).param("productor", dos).param("lote", "L-PR"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(descomprimir(excel)).contains("L-PR2").doesNotContain("L-PR1");
    }

    @Test
    void elReporteMantieneElAislamientoPorRolYUbicacionAunqueSeFiltreUnaUbicacionAjena() throws Exception {
        long propio = lote(producto("Producto propio reporte"), "REP-LIMA", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        long ajenoProducto = productoConLote("Producto ajeno reporte " + UUID.randomUUID(), "PUBLICADO", null, null, "productor2@ccpip.test");
        long ajeno = lote(ajenoProducto, "REP-LORETO", ubicacion("Centro Regional Loreto"), "5", HOY.plusDays(60));
        long dano = causa("Daño");
        long adminId = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, ADMIN);
        long loreto = ubicacion("Centro Regional Loreto");
        jdbc.update("INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id) VALUES (?, ?)", adminId, loreto);
        marcar(loginAs(ADMIN), ACOPIO, cuerpo(ajeno, "1", dano, null, true)).andExpect(status().isCreated());
        jdbc.update("DELETE FROM usuario_ubicacion WHERE usuario_id = ? AND ubicacion_id = ?", adminId, loreto);
        marcar(loginAs(ADMIN), ACOPIO, cuerpo(propio, "1", dano, null, true)).andExpect(status().isCreated());

        mockMvc.perform(get(REPORTE_ACOPIO).cookie(loginAs(ADMIN)).param("ubicacion", "Centro Regional Loreto"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get(REPORTE_PRODUCTOR).cookie(loginAs(PRODUCTOR)).param("lote", "REP-LORETO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get(REPORTE_PRODUCTOR).cookie(loginAs("productor2@ccpip.test")).param("ubicacion", "Centro Regional Loreto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].lote").value("REP-LORETO"))
                .andExpect(jsonPath("$.items[0].registradoPor").doesNotExist());
    }

    @Test
    void losPdfYExcelDelReporteConservanUnDetalleQueContieneElDelimitador() throws Exception {
        long producto = producto("Producto exportable merma");
        long incluido = lote(producto, "REP-PDF", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        long excluido = lote(producto, "REP-OMITIR", ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(incluido, "1", causa("Daño"), "golpe | humedad", true)).andExpect(status().isCreated());
        marcar(admin, ACOPIO, cuerpo(excluido, "1", causa("Daño"), null, true)).andExpect(status().isCreated());

        byte[] pdf = mockMvc.perform(get(REPORTE_ACOPIO + "/pdf").cookie(admin).param("lote", "REP-PDF").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Language", "en"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment; filename=\"reporte-merma-acopio.pdf\"")))
                .andReturn().getResponse().getContentAsByteArray();
        assertPdfContains(pdf, "Shrinkage report", "Date | Product | Batch | Location | Quantity | Cause | Detail", "REP-PDF", "Producto exportable merma", "Damage", "golpe | humedad");
        assertPdfDoesNotContain(pdf, "REP-OMITIR");

        byte[] xlsx = mockMvc.perform(get(REPORTE_ACOPIO + "/xlsx").cookie(admin).param("lote", "REP-PDF").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Language", "en"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("reporte-merma-acopio.xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(descomprimir(xlsx)).contains("Shrinkage report", "Date", "Product", "Batch", "Location", "Quantity", "Cause", "Detail", "REP-PDF", "Producto exportable merma", "Damage", "golpe | humedad").doesNotContain("REP-OMITIR");
        assertThat(hoja(xlsx)).contains("r=\"G3\" t=\"inlineStr\"><is><t>golpe | humedad</t>")
                .doesNotContain("r=\"H3\"");
    }

    @Test
    void losPdfYExcelConservanUnDetalleMultilineaConEmojiNoBmp() throws Exception {
        String productoUnicode = "Cacao 🌱 ñandú";
        String loteUnicode = "L-🧺-漢字";
        String detalleUnicode = "uno\ndos 🫚";
        long producto = producto(productoUnicode);
        long lote = lote(producto, loteUnicode, ubicacion("Centro Nacional Lima"), "5", HOY.plusDays(60));
        Cookie admin = loginAs(ADMIN);
        marcar(admin, ACOPIO, cuerpo(lote, "1", causa("Daño"), detalleUnicode, true)).andExpect(status().isCreated());
        mockMvc.perform(get(REPORTE_ACOPIO).cookie(admin).param("lote", loteUnicode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].detalle").value(detalleUnicode));

        byte[] pdf = mockMvc.perform(get(REPORTE_ACOPIO + "/pdf").cookie(admin).param("lote", loteUnicode))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertPdfPreservesAndRenders(pdf, productoUnicode, loteUnicode, detalleUnicode);

        byte[] xlsx = mockMvc.perform(get(REPORTE_ACOPIO + "/xlsx").cookie(admin).param("lote", loteUnicode))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(descomprimir(xlsx)).contains(productoUnicode, loteUnicode, detalleUnicode);
    }

    @Test
    void lasExportacionesVaciasRespetanIdiomaSeguroYLosRolesNoPuedenCambiarElAlcance() throws Exception {
        Cookie admin = loginAs(ADMIN);
        byte[] pdf = mockMvc.perform(get(REPORTE_ACOPIO + "/pdf").cookie(admin).header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Language", "en"))
                .andReturn().getResponse().getContentAsByteArray();
        assertPdfContains(pdf, "Shrinkage report", "No shrinkage for the selected filters.");
        byte[] xlsx = mockMvc.perform(get(REPORTE_ACOPIO + "/xlsx").cookie(admin).param("lang", "fr").header("Accept-Language", "en"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Language", "es"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(descomprimir(xlsx)).contains("Reporte de merma", "Fecha", "Detalle", "No hay mermas para los filtros seleccionados.");
        mockMvc.perform(get(REPORTE_ACOPIO + "/pdf").cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get(REPORTE_PRODUCTOR + "/xlsx").cookie(loginAs(SUPER))).andExpect(status().isForbidden());
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

    private static String descomprimir(byte[] xlsx) throws Exception {
        StringBuilder contenido = new StringBuilder();
        try (ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = entrada.getNextEntry(); e != null; e = entrada.getNextEntry()) {
                contenido.append(new String(entrada.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return contenido.toString();
    }

    private static String hoja(byte[] xlsx) throws Exception {
        try (ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = entrada.getNextEntry(); e != null; e = entrada.getNextEntry()) {
                if ("xl/worksheets/sheet1.xml".equals(e.getName())) {
                    return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException("XLSX sin hoja principal");
    }

    private static void assertPdfContains(byte[] pdf, String... textos) {
        assertThat(pdfTexto(pdf)).contains(textos);
    }

    private static void assertPdfDoesNotContain(byte[] pdf, String texto) {
        assertThat(pdfTexto(pdf)).doesNotContain(texto);
    }

    /** Actual PDF parsing and rasterization catches the invalid Helvetica/Identity-H pseudo-Unicode renderer. */
    private static void assertPdfPreservesAndRenders(byte[] pdf, String... textos) throws Exception {
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            String extraido = new PDFTextStripper().getText(documento);
            assertThat(extraido).contains(textos).doesNotContain("?");
            for (COSName nombre : documento.getPage(0).getResources().getFontNames()) {
                PDFont fuente = documento.getPage(0).getResources().getFont(nombre);
                assertThat(fuente.isEmbedded()).isTrue();
            }
            BufferedImage imagen = new PDFRenderer(documento).renderImageWithDPI(0, 96);
            assertThat(imagen.getWidth()).isPositive();
            assertThat(imagen.getHeight()).isPositive();
            assertThat(tieneTinta(imagen)).isTrue();
        }
    }

    private static boolean tieneTinta(BufferedImage imagen) {
        for (int y = 0; y < imagen.getHeight(); y++) {
            for (int x = 0; x < imagen.getWidth(); x++) {
                if (imagen.getRGB(x, y) != 0xFFFFFFFF) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String pdfTexto(byte[] pdf) {
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(documento);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("PDF no legible", e);
        }
    }
}
