package pe.ccpip.valoracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-81 (RF-88): compra verificada y una calificacion de 1 a 5 estrellas por linea de un pedido
 * recibido. CCPIP-82 (RF-89): el promedio publico solo con el minimo configurado (3 por defecto), y sin
 * calificaciones no hay promedio cero. Transaccional: lo que arma la prueba no llega a las demas.
 */
@Transactional
class CalificacionesTest extends IntegrationTest {

    private static final String COMPRADORA = "califica@ccpip.test";
    private static final String NOMBRE_COMPRADORA = "Compradora Calificadora";
    private static final String LIMA = "Centro Nacional Lima";

    private Cookie sesion;
    private long aguaje;
    private int pedidos;

    @BeforeEach
    void preparar() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, ?, 1, true FROM usuario WHERE correo = 'consumidor@ccpip.test'
                ON CONFLICT (correo) DO NOTHING""", COMPRADORA, NOMBRE_COMPRADORA);
        sesion = loginAs(COMPRADORA);
        aguaje = productoConLote("Aguaje para calificar", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
    }

    @Test
    void unPedidoQueAunNoSeRecibeNoSeCalifica() throws Exception {
        long id = pedido("CONFIRMADO", aguaje);
        long item = item(id);

        mockMvc.perform(get("/api/pedidos/" + id + "/calificaciones").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calificable").value(false))
                .andExpect(jsonPath("$.pendientes").value(0))
                .andExpect(jsonPath("$.lineas[0].itemId").value(item))
                .andExpect(jsonPath("$.lineas[0].calificacion").doesNotExist());
        calificar(id, item, 5, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_RECIBIDO"));
    }

    @Test
    void calificaUnaLineaRecibidaUnaSolaVez() throws Exception {
        long id = pedido("ENTREGADO", aguaje);
        long item = item(id);

        mockMvc.perform(get("/api/pedidos/" + id + "/calificaciones").cookie(sesion))
                .andExpect(jsonPath("$.calificable").value(true))
                .andExpect(jsonPath("$.pendientes").value(1));
        calificar(id, item, 4, "  Muy buen aguaje, llegó fresco.  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendientes").value(0))
                .andExpect(jsonPath("$.lineas[0].calificacion.estrellas").value(4))
                .andExpect(jsonPath("$.lineas[0].calificacion.comentario").value("Muy buen aguaje, llegó fresco."))
                .andExpect(jsonPath("$.lineas[0].calificacion.calificadoEn").exists());
        // Una vez por linea: no se vuelve a calificar ni se edita
        calificar(id, item, 1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINEA_YA_CALIFICADA"));
        assertThat(jdbc.queryForObject("SELECT estrellas FROM calificacion WHERE item_pedido_id = ?", Integer.class, item))
                .isEqualTo(4);
    }

    @Test
    void soloLineasDelPedidoPropio() throws Exception {
        long propio = pedido("ENTREGADO", aguaje);
        long otro = pedido("ENTREGADO", aguaje);
        long ajeno = pedidoConfirmado("CAL-AJENO", LIMA, aguaje, 1); // de consumidor@ccpip.test
        jdbc.update("UPDATE pedido SET estado = 'ENTREGADO' WHERE id = ?", ajeno);

        calificar(propio, item(otro), 5, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINEA_NO_ENCONTRADA"));
        calificar(ajeno, item(ajeno), 5, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
        mockMvc.perform(get("/api/pedidos/" + ajeno + "/calificaciones").cookie(sesion))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    void lasEstrellasVanDeUnoACinco(int estrellas) throws Exception {
        long id = pedido("ENTREGADO", aguaje);
        calificar(id, item(id), estrellas, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.estrellas").exists());
    }

    @Test
    void sinCalificacionesNoHayPromedioNiCero() throws Exception {
        mockMvc.perform(get("/api/productos/" + aguaje + "/calificaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.minimo").value(3))
                .andExpect(jsonPath("$.suficiente").value(false))
                .andExpect(jsonPath("$.promedio").doesNotExist())
                .andExpect(jsonPath("$.resenas").doesNotExist());
    }

    @Test
    void elPromedioSePublicaRecienConElMinimoDeCalificaciones() throws Exception {
        calificarEntregado(5, "Excelente");
        calificarEntregado(4, null);
        // Con dos calificaciones aun no hay suficiente evidencia
        mockMvc.perform(get("/api/productos/" + aguaje + "/calificaciones"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.suficiente").value(false))
                .andExpect(jsonPath("$.promedio").doesNotExist())
                .andExpect(jsonPath("$.distribucion").doesNotExist());

        calificarEntregado(4, "Buen sabor");
        String cuerpo = mockMvc.perform(get("/api/productos/" + aguaje + "/calificaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.suficiente").value(true))
                .andExpect(jsonPath("$.promedio").value(4.3))
                .andExpect(jsonPath("$.distribucion.cinco").value(1))
                .andExpect(jsonPath("$.distribucion.cuatro").value(2))
                .andExpect(jsonPath("$.distribucion.uno").value(0))
                // Solo las que tienen comentario, la mas reciente primero, sin datos del comprador
                .andExpect(jsonPath("$.resenas", hasSize(2)))
                .andExpect(jsonPath("$.resenas[*].estrellas").exists())
                .andExpect(jsonPath("$.resenas[0].fecha").value(LocalDate.now(CalificacionesService.LIMA).toString()))
                .andExpect(jsonPath("$..usuarioId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain(NOMBRE_COMPRADORA).doesNotContain(COMPRADORA);
    }

    @Test
    void elResumenEsDeOfertasVisibles() throws Exception {
        long borrador = productoConLote("Aguaje en borrador", "BORRADOR", null, null);
        mockMvc.perform(get("/api/productos/" + borrador + "/calificaciones"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
    }

    @Test
    void soloElCompradorCalificaYSinSesionNoHayNada() throws Exception {
        long id = pedido("ENTREGADO", aguaje);
        mockMvc.perform(get("/api/pedidos/" + id + "/calificaciones").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/pedidos/" + id + "/calificaciones").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("itemId", item(id), "estrellas", 5))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void laBaseExigeCompraVerificadaYNoPermiteEditarNiBorrar() throws Exception {
        long id = pedido("LISTO_PARA_RECOJO", aguaje);
        long item = item(id);
        assertRechazaLaBase(() -> insertarDirecto(id, item), "pedido entregado");

        jdbc.update("UPDATE pedido SET estado = 'ENTREGADO' WHERE id = ?", id);
        insertarDirecto(id, item);
        assertRechazaLaBase(() -> jdbc.update("UPDATE calificacion SET estrellas = 1 WHERE item_pedido_id = ?", item), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM calificacion WHERE item_pedido_id = ?", item), "solo insercion");
    }

    // --- Ayudas ---

    private void insertarDirecto(long pedidoId, long itemId) {
        jdbc.update("""
                INSERT INTO calificacion (item_pedido_id, pedido_id, producto_id, productor_id, usuario_id, estrellas)
                SELECT i.id, i.pedido_id, i.producto_id, i.productor_id, pe.usuario_id, 5
                FROM item_pedido i JOIN pedido pe ON pe.id = i.pedido_id WHERE i.id = ? AND pe.id = ?""", itemId, pedidoId);
    }

    private void calificarEntregado(int estrellas, String comentario) throws Exception {
        long id = pedido("ENTREGADO", aguaje);
        calificar(id, item(id), estrellas, comentario).andExpect(status().isCreated());
    }

    private ResultActions calificar(long pedidoId, long itemId, int estrellas, String comentario) throws Exception {
        Map<String, Object> cuerpo = new java.util.HashMap<>(Map.of("itemId", itemId, "estrellas", estrellas));
        if (comentario != null) {
            cuerpo.put("comentario", comentario);
        }
        return mockMvc.perform(post("/api/pedidos/" + pedidoId + "/calificaciones").cookie(sesion)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    /** Pedido de la compradora con una linea; confirmado salvo que este pendiente de pago. */
    private long pedido(String estado, long productoId) {
        pedidos++;
        Long id = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 20, ?, now()
                FROM usuario u, ubicacion l WHERE u.correo = ? AND l.nombre = ?
                RETURNING id""", Long.class, "CAL-" + pedidos, estado, COMPRADORA, LIMA);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
                SELECT ?, id, productor_id, 1, precio, precio FROM producto WHERE id = ?""", id, productoId);
        return id;
    }

    private long item(long pedidoId) {
        return jdbc.queryForObject("SELECT min(id) FROM item_pedido WHERE pedido_id = ?", Long.class, pedidoId);
    }
}
