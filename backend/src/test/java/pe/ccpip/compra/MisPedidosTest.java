package pe.ccpip.compra;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-112 (RF-47): el comprador consulta sus compras con el precio que pago y su estado; CCPIP-70
 * (RF-52) y CCPIP-340 (RF-230): el estado actual del pedido y su historial cronologico. Solo los
 * pedidos propios. Transaccional: los pedidos de la prueba no quedan para otras pruebas.
 */
@Transactional
class MisPedidosTest extends IntegrationTest {

    private static final String COMPRADORA = "mis.pedidos@ccpip.test";
    private static final String LIMA = "Centro Nacional Lima";

    private Cookie sesion;

    @BeforeEach
    void compradoraSinPedidos() throws Exception {
        crearConsumidor(COMPRADORA);
        sesion = loginAs(COMPRADORA);
    }

    @Test
    void sinComprasLaListaEstaVacia() throws Exception {
        mockMvc.perform(get("/api/pedidos").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listaSusComprasLaMasRecientePrimeroConElPrecioPagadoYSuHistorial() throws Exception {
        long aguaje = productoConLote("Aguaje de mis pedidos", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        long cacao = productoConLote("Cacao de mis pedidos", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        long entregado = pedido("MP-0001", "ENTREGADO", "2026-10-01T10:00:00-05:00", aguaje, 2);
        historial(entregado, "PENDIENTE_PAGO", "2026-10-01T10:00:00-05:00");
        historial(entregado, "CONFIRMADO", "2026-10-01T10:02:00-05:00");
        historial(entregado, "LISTO_PARA_RECOJO", "2026-10-02T09:30:00-05:00");
        historial(entregado, "ENTREGADO", "2026-10-03T16:10:00-05:00");
        long enCurso = pedido("MP-0002", "CONFIRMADO", "2026-10-10T11:00:00-05:00", cacao, 1);
        item(enCurso, aguaje, 3);
        historial(enCurso, "PENDIENTE_PAGO", "2026-10-10T11:00:00-05:00");
        historial(enCurso, "CONFIRMADO", "2026-10-10T11:01:00-05:00");
        // El precio vigente cambia despues de la compra: la lista conserva el que se pago
        jdbc.update("UPDATE producto SET precio = 99.00 WHERE id IN (?, ?)", aguaje, cacao);

        mockMvc.perform(get("/api/pedidos").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].numero").value("MP-0002"))
                .andExpect(jsonPath("$[0].estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$[0].modalidad").value("RECOJO"))
                .andExpect(jsonPath("$[0].total").value(80.0))
                .andExpect(jsonPath("$[0].productos").value(2))
                .andExpect(jsonPath("$[0].comunidades").value(1))
                .andExpect(jsonPath("$[0].punto.nombre").value(LIMA))
                .andExpect(jsonPath("$[1].numero").value("MP-0001"))
                .andExpect(jsonPath("$[1].estado").value("ENTREGADO"))
                .andExpect(jsonPath("$[1].total").value(40.0))
                .andExpect(jsonPath("$[1].historial[*].estado")
                        .value(contains("PENDIENTE_PAGO", "CONFIRMADO", "LISTO_PARA_RECOJO", "ENTREGADO")))
                .andExpect(jsonPath("$[1].historial[3].registradoEn").value("2026-10-03T21:10:00Z"))
                // Sin datos de quien registro cada estado ni del comprador
                .andExpect(jsonPath("$..usuarioId").doesNotExist())
                .andExpect(jsonPath("$..responsable").doesNotExist());
    }

    @Test
    void elDetalleMuestraElEstadoActualYSuHistorialEnOrdenCronologico() throws Exception {
        long aguaje = productoConLote("Aguaje del detalle", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        long id = pedido("MP-0003", "LISTO_PARA_RECOJO", "2026-10-12T08:00:00-05:00", aguaje, 1);
        // Se insertan desordenados: el orden lo da la fecha, no la insercion
        historial(id, "LISTO_PARA_RECOJO", "2026-10-13T09:00:00-05:00");
        historial(id, "PENDIENTE_PAGO", "2026-10-12T08:00:00-05:00");
        historial(id, "CONFIRMADO", "2026-10-12T08:01:00-05:00");

        mockMvc.perform(get("/api/pedidos/" + id).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("LISTO_PARA_RECOJO"))
                .andExpect(jsonPath("$.historial[*].estado")
                        .value(contains("PENDIENTE_PAGO", "CONFIRMADO", "LISTO_PARA_RECOJO")))
                .andExpect(jsonPath("$.historial[2].registradoEn").value("2026-10-13T14:00:00Z"));
    }

    @Test
    void soloVeSusPropiasCompras() throws Exception {
        long aguaje = productoConLote("Aguaje ajeno", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        long ajeno = pedidoConfirmado("MP-AJENO", LIMA, aguaje, 1); // de consumidor@ccpip.test

        mockMvc.perform(get("/api/pedidos").cookie(sesion))
                .andExpect(jsonPath("$[?(@.numero == 'MP-AJENO')]").isEmpty());
        mockMvc.perform(get("/api/pedidos/" + ajeno).cookie(sesion)).andExpect(status().isNotFound());
    }

    @Test
    void otrosRolesNoTienenListaDeComprasYSinSesionNoHayNada() throws Exception {
        mockMvc.perform(get("/api/pedidos").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        mockMvc.perform(get("/api/pedidos")).andExpect(status().isUnauthorized());
    }

    // --- Ayudas ---

    private void crearConsumidor(String correo) {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Compradora de Prueba', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", correo);
    }

    /** Pedido de la compradora con una linea al precio de hoy (20.00); {@code creado} fija su fecha. */
    private long pedido(String numero, String estado, String creado, long productoId, int cantidad) {
        boolean pagado = !estado.equals("PENDIENTE_PAGO") && !estado.equals("CANCELADO");
        Long id = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, creado_en, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 0, ?, ?::timestamptz, CASE WHEN ? THEN ?::timestamptz END
                FROM usuario u, ubicacion l WHERE u.correo = ? AND l.nombre = ?
                RETURNING id""", Long.class, numero, estado, creado, pagado, creado, COMPRADORA, LIMA);
        item(id, productoId, cantidad);
        return id;
    }

    /** Agrega una linea con el precio vigente y actualiza el total del pedido. */
    private void item(long pedidoId, long productoId, int cantidad) {
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
                SELECT ?, id, productor_id, ?, precio, precio * ? FROM producto WHERE id = ?""",
                pedidoId, cantidad, cantidad, productoId);
        jdbc.update("UPDATE pedido SET total = (SELECT sum(subtotal) FROM item_pedido WHERE pedido_id = ?) WHERE id = ?",
                pedidoId, pedidoId);
    }

    private void historial(long pedidoId, String estado, String cuando) {
        jdbc.update("""
                INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id, registrado_en)
                VALUES (?, ?, NULL, ?::timestamptz)""", pedidoId, estado, cuando);
    }
}
