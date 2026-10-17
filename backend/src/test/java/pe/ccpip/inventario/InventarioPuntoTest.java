package pe.ccpip.inventario;

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
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-162 (RF-131), Figma A-02 "Inventario por producto, productor y lote": el administrador del
 * punto consulta las existencias de sus puntos por lote y estado. El total no duplica unidades, cada
 * punto ve solo lo suyo (RNF-05) y lo vencido no es vendible. Usa un administrador del Centro
 * Regional Junin, sin existencias sembradas, para que los totales sean exactos.
 */
@Transactional
class InventarioPuntoTest extends IntegrationTest {

    static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    static final String JUNIN = "Centro Regional Junin";
    static final String RUTA = "/api/acopio/inventario";

    private long aceite;
    private long cacao;

    @BeforeEach
    void preparar() {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'admin_junin@ccpip.test', contrasena_hash, 'Marta Chavez', rol_id, true
                FROM usuario WHERE correo = 'admin_acopio@ccpip.test'""");
        jdbc.update("""
                INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
                SELECT u.id, l.id FROM usuario u, ubicacion l WHERE u.correo = 'admin_junin@ccpip.test' AND l.nombre = ?""",
                JUNIN);
        aceite = productoConLote("Aceite de sacha inchi A-02", "PUBLICADO", null, null, "productor@ccpip.test");
        cacao = productoConLote("Chocolate de cacao A-02", "PUBLICADO", null, null, "productor2@ccpip.test");
    }

    @Test
    void desglosaPorProductoProductorLoteYEstadoSinDuplicarUnidades() throws Exception {
        long a1 = lote(aceite, "A-1", JUNIN, "30", HOY.plusDays(200));
        lote(aceite, "A-2", JUNIN, "4", HOY.plusDays(5));
        lote(aceite, "A-3", JUNIN, "6", HOY.minusDays(1));
        lote(cacao, "B-1", JUNIN, "0", HOY.plusDays(90));
        Cookie admin = loginAs("admin_junin@ccpip.test");
        // Varios movimientos sobre el mismo lote: el saldo se cuenta una sola vez
        movimiento(admin, "SALIDA", a1, "5", "DEVOLUCION_PRODUCTOR");
        movimiento(admin, "ENTRADA", a1, "10", "RECEPCION");

        inventario(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ubicacion").value(JUNIN))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.tamano").value(20))
                // El que vence antes primero: el vencido, luego el por vencer
                .andExpect(jsonPath("$.items[0].codigo").value("A-3"))
                .andExpect(jsonPath("$.items[0].estado").value("VENCIDO"))
                .andExpect(jsonPath("$.items[0].vendible").value(false))
                .andExpect(jsonPath("$.items[1].codigo").value("A-2"))
                .andExpect(jsonPath("$.items[1].estado").value("POR_VENCER"))
                .andExpect(jsonPath("$.items[1].vendible").value(true))
                .andExpect(jsonPath("$.items[2].codigo").value("A-1"))
                .andExpect(jsonPath("$.items[2].producto").value("Aceite de sacha inchi A-02"))
                .andExpect(jsonPath("$.items[2].productor").exists())
                .andExpect(jsonPath("$.items[2].ubicacion").value(JUNIN))
                .andExpect(jsonPath("$.items[2].entradas").value(40.0))
                .andExpect(jsonPath("$.items[2].salidas").value(5.0))
                .andExpect(jsonPath("$.items[2].stock").value(35.0))
                .andExpect(jsonPath("$.items[2].estado").value("DISPONIBLE"))
                // El agotado no esta entre los activos
                .andExpect(content().string(not(containsString("\"B-1\""))))
                .andExpect(jsonPath("$.resumen.stock").value(45.0))
                .andExpect(jsonPath("$.resumen.vendible").value(39.0))
                .andExpect(jsonPath("$.resumen.noVendible").value(6.0))
                .andExpect(jsonPath("$.resumen.lotes").value(3))
                .andExpect(jsonPath("$.diasAviso").value(15))
                // El filtro de productor ofrece a quienes tienen lotes en el punto, aunque esten agotados
                .andExpect(jsonPath("$.productores.length()").value(2));
    }

    @Test
    void filtraPorBusquedaProductorYEstado() throws Exception {
        lote(aceite, "A-1", JUNIN, "30", HOY.plusDays(200));
        lote(cacao, "B-1", JUNIN, "0", HOY.plusDays(90));
        lote(cacao, "B-2", JUNIN, "7", null);
        Cookie admin = loginAs("admin_junin@ccpip.test");
        long productor2 = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, cacao);

        inventario(admin, "estado", "AGOTADOS")
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].codigo").value("B-1"))
                .andExpect(jsonPath("$.items[0].estado").value("AGOTADO"))
                .andExpect(jsonPath("$.items[0].vendible").value(false));
        inventario(admin, "estado", "TODOS").andExpect(jsonPath("$.total").value(3));
        mockMvc.perform(get(RUTA).cookie(admin).param("estado", "TODOS").param("productor", String.valueOf(productor2)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.resumen.stock").value(7.0))
                .andExpect(jsonPath("$.items[*].productoId", not(hasItem((int) aceite))));
        // Por producto, sin tildes ni mayusculas, o por codigo de lote
        inventario(admin, "q", "SACHA INCHÍ").andExpect(jsonPath("$.total").value(1));
        inventario(admin, "q", "b-2")
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].fechaVencimiento").doesNotExist())
                .andExpect(jsonPath("$.items[0].estado").value("DISPONIBLE"));
        inventario(admin, "q", "100%").andExpect(jsonPath("$.total").value(0));
        inventario(admin, "estado", "VENDIBLES").andExpect(status().isBadRequest());
    }

    @Test
    void cadaAdministradorVeSoloLosLotesDeSusPuntos() throws Exception {
        lote(aceite, "J-1", JUNIN, "9", null);
        lote(aceite, "L-1", "Centro Nacional Lima", "11", null);

        inventario(loginAs("admin_junin@ccpip.test"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.resumen.stock").value(9.0))
                .andExpect(content().string(not(containsString("\"L-1\""))));
        inventario(loginAs("admin_acopio@ccpip.test"), "q", "A-02")
                .andExpect(jsonPath("$.ubicacion").value("Centro Nacional Lima"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].codigo").value("L-1"))
                .andExpect(content().string(not(containsString("\"J-1\""))));
    }

    @Test
    void elAlmacenDelProductorNoEsParteDelPunto() throws Exception {
        jdbc.update("""
                INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
                SELECT u.id, a.id FROM usuario u, ubicacion a JOIN productor p ON p.id = a.productor_id
                JOIN usuario x ON x.id = p.usuario_id
                WHERE u.correo = 'admin_junin@ccpip.test' AND x.correo = 'productor@ccpip.test'""");
        lote(aceite, "ALM-1", null, "9", null);
        inventario(loginAs("admin_junin@ccpip.test"), "estado", "TODOS")
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void soloElAdministradorDePuntoConsulta() throws Exception {
        inventario(loginAs("productor@ccpip.test")).andExpect(status().isForbidden());
        inventario(loginAs("consumidor@ccpip.test")).andExpect(status().isForbidden());
        inventario(loginAs("sudo@ccpip.test")).andExpect(status().isForbidden());
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(RUTA + "/lotes")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(RUTA + "/lotes").cookie(loginAs("productor@ccpip.test"))).andExpect(status().isForbidden());
    }

    private ResultActions inventario(Cookie quien, String... filtros) throws Exception {
        var pedido = get(RUTA).cookie(quien);
        for (int i = 0; i < filtros.length; i += 2) {
            pedido.param(filtros[i], filtros[i + 1]);
        }
        return mockMvc.perform(pedido);
    }

    private void movimiento(Cookie quien, String tipo, long lote, String cantidad, String motivo) throws Exception {
        mockMvc.perform(post("/api/acopio/movimientos").cookie(quien).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tipo", tipo, "loteId", lote, "cantidad", new BigDecimal(cantidad),
                                "motivo", motivo, "clave", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated());
    }

    /** Un lote con su ENTRADA y su stock; sin {@code punto}, en el almacen de su productor. */
    private long lote(long producto, String codigo, String punto, String cantidad, LocalDate vence) {
        long ubicacion = punto == null
                ? jdbc.queryForObject("""
                        SELECT u.id FROM ubicacion u JOIN producto p ON p.productor_id = u.productor_id WHERE p.id = ?""",
                        Long.class, producto)
                : jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, punto);
        BigDecimal q = new BigDecimal(cantidad);
        Long id = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                  fecha_produccion, fecha_vencimiento)
                VALUES (?, ?, ?, greatest(?, 1), ?, ?, ?) RETURNING id""", Long.class,
                producto, ubicacion, codigo, q, q, HOY.minusDays(60), vence);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion)
                VALUES (?, ?, ?, 'ENTRADA', ?, 'Registro del lote')""", producto, ubicacion, id, q.max(BigDecimal.ONE));
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, ?)
                ON CONFLICT (producto_id, ubicacion_id) DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad""",
                producto, ubicacion, q);
        return id;
    }
}
