package pe.ccpip.compra;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-295 (RF-155): al elegir de donde sale su pedido, el comprador ve las advertencias de perecibilidad
 * de su carrito: hasta cuando se consume lo que saldria de ese punto, segun el lote mas proximo a vencer
 * (el primero que se despacha). Solo datos registrados: sin tiempos de transito ni bloqueos nuevos.
 * Transaccional: el comprador y sus productos no llegan a las demas pruebas.
 */
@Transactional
class PerecibilidadTest extends IntegrationTest {

    private static final String COMPRADOR = "comprador.perecible@ccpip.test";
    private static final String CUSCO = "Centro Regional Cusco";
    private static final String LIMA = "Centro Nacional Lima";
    private static final String ADVERTENCIAS = "/api/compra/advertencias";
    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));

    private Cookie comprador;
    private long miel;

    @BeforeEach
    void compradorConCarrito() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Comprador de Prueba', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", COMPRADOR);
        comprador = loginAs(COMPRADOR);

        miel = producto("PR Miel de abeja nativa", "SUPERFRUTOS", "G");
        lote(miel, CUSCO, "A", 2, HOY.plusDays(20));
        lote(miel, CUSCO, "B", 5, HOY.plusDays(60));
        // No cuentan: un lote vencido y uno de otro punto, aunque venzan antes
        lote(miel, CUSCO, "C", 3, HOY.minusDays(1));
        lote(miel, LIMA, "D", 3, HOY.plusDays(5));
        long canasta = producto("PR Canasta de tamshi", "ARTESANIA", "UNIDAD");
        lote(canasta, CUSCO, "E", 4, null);

        alCarrito(miel, 3);
        alCarrito(canasta, 1);
    }

    @Test
    void advierteHastaCuandoSeConsumeLoPerecibleQueSaldriaDelPunto() throws Exception {
        mockMvc.perform(get(ADVERTENCIAS).param("punto", String.valueOf(punto(CUSCO))).cookie(comprador))
                .andExpect(status().isOk())
                // La canasta no vence: no se advierte
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productoId").value(miel))
                .andExpect(jsonPath("$[0].nombre").value("PR Miel de abeja nativa"))
                // El lote que vence primero es el primero que se despacha
                .andExpect(jsonPath("$[0].consumirAntesDe").value(HOY.plusDays(20).toString()))
                .andExpect(jsonPath("$[0].diasRestantes").value(20));
    }

    @Test
    void unPuntoSinTodoElCarritoSeRechazaComoAlConfirmar() throws Exception {
        mockMvc.perform(get(ADVERTENCIAS).param("punto", String.valueOf(punto(LIMA))).cookie(comprador))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUNTO_SIN_EXISTENCIAS"));
        mockMvc.perform(get(ADVERTENCIAS).cookie(comprador)).andExpect(status().isBadRequest());
    }

    @Test
    void conElCarritoVacioNoHayNadaQueAdvertir() throws Exception {
        jdbc.update("""
                DELETE FROM item_carrito WHERE carrito_id IN (
                    SELECT c.id FROM carrito c JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = ?)""", COMPRADOR);
        mockMvc.perform(get(ADVERTENCIAS).param("punto", String.valueOf(punto(CUSCO))).cookie(comprador))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARRITO_VACIO"));
    }

    @Test
    void soloElComprador() throws Exception {
        mockMvc.perform(get(ADVERTENCIAS).param("punto", String.valueOf(punto(CUSCO))).cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(ADVERTENCIAS).param("punto", String.valueOf(punto(CUSCO)))).andExpect(status().isUnauthorized());
    }

    // --- Apoyo ---

    /** Producto publicado y autorizado de productor@ccpip.test, sin lotes. */
    private long producto(String nombre, String linea, String unidad) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado, publicado_en)
                SELECT p.id, ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = ?),
                       (SELECT id FROM unidad_medida WHERE codigo = ?), 25.00, 'PUBLICADO', now()
                FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor@ccpip.test'
                RETURNING id""", Long.class, nombre, linea, unidad);
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, linea_id FROM producto WHERE id = ?", id, id);
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        return id;
    }

    private void lote(long producto, String punto, String codigo, int unidades, LocalDate vence) {
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion,
                                  fecha_vencimiento)
                SELECT ?, u.id, 'PR-' || ? || ?, ?, ?, ?::date - 90, ? FROM ubicacion u WHERE u.nombre = ?""",
                producto, producto, codigo, unidades, unidades, HOY, vence, punto);
    }

    private void alCarrito(long productoId, int cantidad) throws Exception {
        mockMvc.perform(post("/api/carrito/items").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productoId", productoId, "cantidad", cantidad))))
                .andExpect(status().isOk());
    }

    private long punto(String nombre) {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, nombre);
    }
}
