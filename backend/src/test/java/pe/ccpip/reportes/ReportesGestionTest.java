package pe.ccpip.reportes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * Reportes de gestion del superadministrador (SA-05, SA-10). CCPIP-122 (RF-86): productores con mayores
 * ventas; CCPIP-355 (RF-245): ventas de los puntos de acopio.
 *
 * <p>Los datos son de agosto y septiembre de 2023, meses que ninguna otra prueba usa, y la transaccion se
 * deshace al terminar.
 */
@Transactional
class ReportesGestionTest extends IntegrationTest {

    private static final String SUPER = "sudo@ccpip.test";
    private static final String LIMA = "Centro Nacional Lima";
    private static final String CUSCO = "Centro Regional Cusco";
    private static final String SEPTIEMBRE = "?desde=2023-09-01&hasta=2023-09-30";

    private long aceite;
    private long harina;

    @BeforeEach
    void productos() {
        aceite = productoConLote("RG Aceite", "PUBLICADO", null, null);
        harina = productoConLote("RG Harina", "PUBLICADO", null, null, "productor2@ccpip.test");
    }

    @Test
    void losProductoresQueMasVendenConSuDesgloseB2cYB2b() throws Exception {
        ventasDePrueba();

        mockMvc.perform(get("/api/admin/reportes/productores" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value("2023-09-01"))
                .andExpect(jsonPath("$.hasta").value("2023-09-30"))
                .andExpect(jsonPath("$.totales.b2c").value(80.00))
                .andExpect(jsonPath("$.totales.b2b").value(100.00))
                .andExpect(jsonPath("$.totales.total").value(180.00))
                .andExpect(jsonPath("$.productoresConVentas").value(2))
                .andExpect(jsonPath("$.productores.length()").value(2))
                // productor@: 30 minorista y 100 mayorista en dos pedidos
                .andExpect(jsonPath("$.productores[0].pedidos").value(2))
                .andExpect(jsonPath("$.productores[0].unidades").value(4))
                .andExpect(jsonPath("$.productores[0].b2c").value(30.00))
                .andExpect(jsonPath("$.productores[0].b2b").value(100.00))
                .andExpect(jsonPath("$.productores[0].total").value(130.00))
                .andExpect(jsonPath("$.productores[0].participacion").value(72.2))
                .andExpect(jsonPath("$.productores[1].total").value(50.00))
                .andExpect(jsonPath("$.productores[1].participacion").value(27.8))
                // Ni compradores ni numeros de pedido
                .andExpect(jsonPath("$..correo").doesNotExist())
                .andExpect(jsonPath("$..numero").doesNotExist());
    }

    @Test
    void lasVentasDeCadaPuntoDeAcopio() throws Exception {
        ventasDePrueba();

        mockMvc.perform(get("/api/admin/reportes/puntos" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.total").value(180.00))
                .andExpect(jsonPath("$.pedidos").value(3))
                // Cusco vendio mas: va primero
                .andExpect(jsonPath("$.puntos[0].punto").value(CUSCO))
                .andExpect(jsonPath("$.puntos[0].pedidos").value(1))
                .andExpect(jsonPath("$.puntos[0].b2b").value(100.00))
                .andExpect(jsonPath("$.puntos[0].participacion").value(55.6))
                .andExpect(jsonPath("$.puntos[1].punto").value(LIMA))
                .andExpect(jsonPath("$.puntos[1].pedidos").value(2))
                .andExpect(jsonPath("$.puntos[1].b2c").value(80.00))
                .andExpect(jsonPath("$.puntos[1].b2b").value(0))
                .andExpect(jsonPath("$.puntos[1].participacion").value(44.4))
                .andExpect(jsonPath("$.puntos[1].departamento").exists())
                .andExpect(jsonPath("$..stock").doesNotExist())
                .andExpect(jsonPath("$..numero").doesNotExist());
    }

    @Test
    void sinVentasElRankingQuedaVacio() throws Exception {
        mockMvc.perform(get("/api/admin/reportes/productores?desde=2023-08-01&hasta=2023-08-02").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.total").value(0))
                .andExpect(jsonPath("$.productoresConVentas").value(0))
                .andExpect(jsonPath("$.productores.length()").value(0));
    }

    @Test
    void elRangoSeValida() throws Exception {
        mockMvc.perform(get("/api/admin/reportes/productores?desde=2023-09-30&hasta=2023-09-01").cookie(loginAs(SUPER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"consumidor@ccpip.test", "productor@ccpip.test", "admin_acopio@ccpip.test", "sub_admin@ccpip.test"})
    void losReportesDeGestionSonDelSuperadministrador(String correo) throws Exception {
        mockMvc.perform(get("/api/admin/reportes/productores").cookie(loginAs(correo))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reportes/puntos").cookie(loginAs(correo))).andExpect(status().isForbidden());
    }

    @Test
    void sinSesionNoHayReportesDeGestion() throws Exception {
        mockMvc.perform(get("/api/admin/reportes/productores")).andExpect(status().isUnauthorized());
    }

    /**
     * Septiembre: productor@ vende 1 aceite minorista (S/ 30) en Lima y 3 mayoristas (S/ 100) en Cusco;
     * productor2@ vende 2 harinas minoristas (S/ 50) en Lima con envio. No cuentan el pedido cancelado ni el
     * de agosto.
     */
    private void ventasDePrueba() {
        linea(pedido("RG-1", "ENTREGADO", "2023-09-05 10:00-05", LIMA, "RECOJO"), aceite, 1, "30.00", "MINORISTA");
        linea(pedido("RG-2", "CONFIRMADO", "2023-09-12 10:00-05", CUSCO, "RECOJO"), aceite, 3, "100.00", "MAYORISTA");
        linea(pedido("RG-3", "ENTREGADO", "2023-09-20 10:00-05", LIMA, "ENVIO"), harina, 2, "50.00", "MINORISTA");
        linea(pedido("RG-4", "CANCELADO", "2023-09-21 10:00-05", LIMA, "RECOJO"), aceite, 9, "999.00", "MINORISTA");
        linea(pedido("RG-5", "ENTREGADO", "2023-08-15 10:00-05", LIMA, "RECOJO"), harina, 1, "25.00", "MINORISTA");
    }

    private long pedido(String numero, String estado, String confirmado, String punto, String modalidad) {
        return jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, ?, l.id, 0, ?, ?::timestamptz
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = ?
                RETURNING id""", Long.class, numero, modalidad, estado, confirmado, punto);
    }

    private long linea(long pedidoId, long productoId, int cantidad, String subtotal, String tarifa) {
        BigDecimal monto = new BigDecimal(subtotal);
        return jdbc.queryForObject("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal, tarifa)
                SELECT ?, id, productor_id, ?, ?, ?, ? FROM producto WHERE id = ?
                RETURNING id""", Long.class, pedidoId, cantidad, monto, monto, tarifa, productoId);
    }
}
