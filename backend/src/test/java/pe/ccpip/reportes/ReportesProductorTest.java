package pe.ccpip.reportes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Pantalla «Reportes» del productor: estadisticas de sus productos por periodo. CCPIP-114 (RF-25): ventas.
 *
 * <p>Los datos de la prueba son de mayo y junio de 2024, meses que ninguna otra prueba usa, y la transaccion
 * se deshace al terminar.
 */
@Transactional
class ReportesProductorTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String OTRO = "productor2@ccpip.test";
    private static final String VENTAS = "/api/productor/reportes/ventas";

    private long aceite;
    private long harina;
    private long ajeno;

    @BeforeEach
    void productos() {
        aceite = productoConLote("RP Aceite", "PUBLICADO", null, null);
        harina = productoConLote("RP Harina", "PUBLICADO", null, null);
        ajeno = productoConLote("RP Ajeno", "PUBLICADO", null, null, OTRO);
    }

    @Test
    void lasVentasDeSusProductosSeAgrupanPorMesConSuModalidad() throws Exception {
        ventasDePrueba();

        mockMvc.perform(get(VENTAS + "?desde=2024-05-01&hasta=2024-06-30&agrupacion=MES").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agrupacion").value("MES"))
                .andExpect(jsonPath("$.periodos.length()").value(2))
                .andExpect(jsonPath("$.periodos[0].inicio").value("2024-05-01"))
                .andExpect(jsonPath("$.periodos[0].fin").value("2024-05-31"))
                // Mayo: 2 + 1 (el 31 a las 23:30 de Lima ya es 1 de junio en UTC) minoristas y 10 mayoristas
                .andExpect(jsonPath("$.periodos[0].unidades").value(13))
                .andExpect(jsonPath("$.periodos[0].b2c").value(60.00))
                .andExpect(jsonPath("$.periodos[0].b2b").value(100.00))
                .andExpect(jsonPath("$.periodos[0].total").value(160.00))
                .andExpect(jsonPath("$.periodos[0].acumulado").value(160.00))
                .andExpect(jsonPath("$.periodos[1].unidades").value(1))
                .andExpect(jsonPath("$.periodos[1].b2c").value(20.00))
                .andExpect(jsonPath("$.periodos[1].b2b").value(0))
                .andExpect(jsonPath("$.periodos[1].acumulado").value(180.00))
                .andExpect(jsonPath("$.totales.unidades").value(14))
                .andExpect(jsonPath("$.totales.b2c").value(80.00))
                .andExpect(jsonPath("$.totales.b2b").value(100.00))
                .andExpect(jsonPath("$.totales.total").value(180.00))
                // Por producto, de mas a menos vendido; nunca lo de otro productor
                .andExpect(jsonPath("$.porProducto.length()").value(2))
                .andExpect(jsonPath("$.porProducto[0].producto").value("RP Harina"))
                .andExpect(jsonPath("$.porProducto[0].unidades").value(10))
                .andExpect(jsonPath("$.porProducto[0].b2b").value(100.00))
                .andExpect(jsonPath("$.porProducto[1].producto").value("RP Aceite"))
                .andExpect(jsonPath("$.porProducto[1].unidades").value(4))
                .andExpect(jsonPath("$.porProducto[1].total").value(80.00))
                .andExpect(jsonPath("$.productos[?(@.nombre == 'RP Aceite')]").exists())
                .andExpect(jsonPath("$.productos[?(@.nombre == 'RP Ajeno')]").doesNotExist())
                // Sin datos de los compradores
                .andExpect(jsonPath("$..correo").doesNotExist())
                .andExpect(jsonPath("$..numero").doesNotExist());
    }

    @Test
    void seFiltraPorProductoYPorModalidad() throws Exception {
        ventasDePrueba();
        Cookie sesion = loginAs(PRODUCTOR);

        mockMvc.perform(get(VENTAS + "?desde=2024-05-01&hasta=2024-06-30&producto=" + aceite).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.producto").value(aceite))
                .andExpect(jsonPath("$.totales.unidades").value(4))
                .andExpect(jsonPath("$.totales.total").value(80.00))
                .andExpect(jsonPath("$.porProducto.length()").value(1));

        mockMvc.perform(get(VENTAS + "?desde=2024-05-01&hasta=2024-06-30&modalidad=MAYORISTA").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modalidad").value("MAYORISTA"))
                .andExpect(jsonPath("$.totales.b2c").value(0))
                .andExpect(jsonPath("$.totales.b2b").value(100.00))
                .andExpect(jsonPath("$.totales.unidades").value(10));
    }

    @Test
    void lasSemanasEmpiezanEnLunesYSeRecortanAlRango() throws Exception {
        ventasDePrueba();

        // Del miercoles 1 al martes 14 de mayo de 2024
        mockMvc.perform(get(VENTAS + "?desde=2024-05-01&hasta=2024-05-14&agrupacion=SEMANA").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodos.length()").value(3))
                .andExpect(jsonPath("$.periodos[0].inicio").value("2024-05-01"))
                .andExpect(jsonPath("$.periodos[0].fin").value("2024-05-05"))
                .andExpect(jsonPath("$.periodos[0].total").value(40.00))
                .andExpect(jsonPath("$.periodos[1].inicio").value("2024-05-06"))
                .andExpect(jsonPath("$.periodos[2].fin").value("2024-05-14"))
                .andExpect(jsonPath("$.totales.total").value(40.00));
    }

    @Test
    void unProductoAjenoNoExisteYOtroRolNoEntra() throws Exception {
        mockMvc.perform(get(VENTAS + "?producto=" + ajeno).cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        mockMvc.perform(get(VENTAS).cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(VENTAS))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unRangoInvalidoOUnaModalidadDesconocidaSeRechazan() throws Exception {
        Cookie sesion = loginAs(PRODUCTOR);
        mockMvc.perform(get(VENTAS + "?desde=2024-06-30&hasta=2024-05-01").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        mockMvc.perform(get(VENTAS + "?desde=2023-01-01&hasta=2024-06-30").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_MUY_LARGO"));
        mockMvc.perform(get(VENTAS + "?modalidad=OTRA").cookie(sesion))
                .andExpect(status().isBadRequest());
    }

    /**
     * Mayo: aceite 2 und minorista (S/ 40) el 3; harina 10 und mayorista (S/ 100) el 20, ya entregada; aceite
     * 1 und minorista (S/ 20) el 31 a las 23:30 de Lima. Junio: aceite 1 und minorista (S/ 20) el 15. No
     * cuentan el pedido cancelado, el que no se pago ni la venta de otro productor.
     */
    private void ventasDePrueba() {
        linea(pedido("RPP-1", "CONFIRMADO", "2024-05-03 10:00-05"), aceite, "2", "40.00", "MINORISTA");
        linea(pedido("RPP-2", "ENTREGADO", "2024-05-20 12:00-05"), harina, "10", "100.00", "MAYORISTA");
        linea(pedido("RPP-3", "CONFIRMADO", "2024-05-31 23:30-05"), aceite, "1", "20.00", "MINORISTA");
        linea(pedido("RPP-4", "CONFIRMADO", "2024-06-15 10:00-05"), aceite, "1", "20.00", "MINORISTA");
        linea(pedido("RPP-5", "CANCELADO", "2024-05-10 10:00-05"), aceite, "9", "999.00", "MINORISTA");
        linea(pedido("RPP-6", "PENDIENTE_PAGO", null), harina, "9", "888.00", "MAYORISTA");
        linea(pedido("RPP-7", "CONFIRMADO", "2024-05-12 10:00-05"), ajeno, "5", "500.00", "MINORISTA");
    }

    private long pedido(String numero, String estado, String confirmado) {
        return jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 0, ?, ?::timestamptz
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, numero, estado, confirmado);
    }

    private void linea(long pedidoId, long productoId, String cantidad, String subtotal, String tarifa) {
        BigDecimal unidades = new BigDecimal(cantidad);
        BigDecimal monto = new BigDecimal(subtotal);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal, tarifa)
                SELECT ?, id, productor_id, ?, ?, ?, ? FROM producto WHERE id = ?""",
                pedidoId, unidades, monto.divide(unidades, 2, java.math.RoundingMode.HALF_UP), monto, tarifa, productoId);
    }
}
