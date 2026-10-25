package pe.ccpip.catalogo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-339 (RF-229): el visitante consulta los productos mas vendidos y sus productores por periodo, sin
 * iniciar sesion. Solo cuentan ventas reales (pedidos confirmados y no cancelados) y solo se muestra el
 * puesto: ni unidades ni montos.
 *
 * <p>Las ventas de la prueba son de miles de unidades para quedar arriba de cualquier otra, y la transaccion
 * se deshace al terminar.
 */
@Transactional
class MasVendidosTest extends IntegrationTest {

    private static final String MAS_VENDIDOS = "/api/mas-vendidos";
    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));

    private long primero;
    private long segundo;
    private long antiguo;

    @BeforeEach
    void ventas() {
        primero = productoConLote("MV Aceite mas vendido", "PUBLICADO", null, null);
        segundo = productoConLote("MV Harina segunda", "PUBLICADO", null, null, "productor2@ccpip.test");
        antiguo = productoConLote("MV Cacao de hace dos meses", "PUBLICADO", null, null);
        long oculto = productoConLote("MV Borrador oculto", "BORRADOR", null, null);

        venta(primero, "5000", 2);
        venta(primero, "4000", 10);
        venta(segundo, "8000", 5);
        // Lo cancelado y lo de un producto que no esta publicado no cuentan
        venta(segundo, "90000", 3, "CANCELADO");
        venta(oculto, "90000", 1);
        // Fuera de los ultimos 30 dias, dentro de los ultimos 3 meses
        venta(antiguo, "20000", 60);
    }

    @Test
    void losUltimos30DiasOrdenanPorUnidadesYMuestranSuProductorSinCifras() throws Exception {
        mockMvc.perform(get(MAS_VENDIDOS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo").value("ULTIMOS_30_DIAS"))
                .andExpect(jsonPath("$.hasta").value(HOY.toString()))
                .andExpect(jsonPath("$.desde").value(HOY.minusDays(29).toString()))
                // 9000 unidades en dos pedidos le ganan a 8000 en uno
                .andExpect(jsonPath("$.productos[0].id").value(primero))
                .andExpect(jsonPath("$.productos[0].tienda.nombre").exists())
                .andExpect(jsonPath("$.productos[1].id").value(segundo))
                .andExpect(jsonPath("$.productos[?(@.nombre == 'MV Cacao de hace dos meses')]").doesNotExist())
                .andExpect(jsonPath("$.productos[?(@.nombre == 'MV Borrador oculto')]").doesNotExist())
                .andExpect(jsonPath("$.productos.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(8)))
                // Solo el puesto: nada de unidades, montos ni compradores
                .andExpect(jsonPath("$..unidadesVendidas").doesNotExist())
                .andExpect(jsonPath("$..pedidosVenta").doesNotExist())
                .andExpect(jsonPath("$..correo").doesNotExist());
    }

    @Test
    void losUltimos3MesesIncluyenLoVendidoHaceDosMeses() throws Exception {
        mockMvc.perform(get(MAS_VENDIDOS + "?periodo=ULTIMOS_3_MESES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo").value("ULTIMOS_3_MESES"))
                .andExpect(jsonPath("$.desde").value(HOY.minusMonths(3).plusDays(1).toString()))
                .andExpect(jsonPath("$.productos[0].nombre").value("MV Cacao de hace dos meses"))
                .andExpect(jsonPath("$.productos[1].id").value(primero))
                .andExpect(jsonPath("$.productos[2].id").value(segundo));
    }

    @Test
    void esteAnioEmpiezaElPrimeroDeEnero() throws Exception {
        mockMvc.perform(get(MAS_VENDIDOS + "?periodo=ESTE_ANIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(HOY.withDayOfYear(1).toString()))
                .andExpect(jsonPath("$.hasta").value(HOY.toString()));
    }

    @Test
    void enInglesYUnPeriodoDesconocidoSeRechaza() throws Exception {
        mockMvc.perform(get(MAS_VENDIDOS).header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Language", "en"));
        mockMvc.perform(get(MAS_VENDIDOS + "?periodo=SIEMPRE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    private void venta(long productoId, String cantidad, int haceDias) {
        venta(productoId, cantidad, haceDias, "CONFIRMADO");
    }

    /** Un pedido de un consumidor confirmado hace {@code haceDias} dias con una linea del producto. */
    private void venta(long productoId, String cantidad, int haceDias, String estado) {
        long pedido = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 0, ?, now() - make_interval(days => ?)
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, "MV" + productoId + "-" + haceDias + estado.charAt(0), estado, haceDias);
        BigDecimal unidades = new BigDecimal(cantidad);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal, tarifa)
                SELECT ?, id, productor_id, ?, 1, ?, 'MINORISTA' FROM producto WHERE id = ?""",
                pedido, unidades, unidades, productoId);
    }
}
