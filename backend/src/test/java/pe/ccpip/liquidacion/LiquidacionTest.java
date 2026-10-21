package pe.ccpip.liquidacion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-92 (RF-70): el productor consulta su liquidacion por periodo. Bruto, comision y neto salen de sus
 * lineas, tal como se copiaron al confirmar, y coinciden con los pedidos.
 *
 * <p>Los pedidos de la prueba se confirman en marzo de 2025, un mes que ninguna otra prueba usa, y la
 * transaccion se deshace al terminar.
 */
@Transactional
class LiquidacionTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String OTRO = "productor2@ccpip.test";
    private static final String MARZO = "?desde=2025-03-01&hasta=2025-03-31";

    @Test
    void elProductorVeSoloSusLineasYLosImportesCuadranConLosPedidos() throws Exception {
        // Pedido mixto: 25.00 de productor@ y 59.70 de productor2@, con 12.5 % de comision por linea
        long mixto = pedido("LQ-1", "CONFIRMADO", "2025-03-10 10:00-05", "12.5");
        linea(mixto, PRODUCTOR, "1", "25.00", "3.13");
        linea(mixto, OTRO, "3", "19.90", "7.46");
        long otro = pedido("LQ-2", "ENTREGADO", "2025-03-20 18:00-05", "12.5");
        linea(otro, PRODUCTOR, "2", "15.00", "3.75");

        mockMvc.perform(get("/api/productor/liquidaciones" + MARZO).cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value("2025-03-01"))
                .andExpect(jsonPath("$.hasta").value("2025-03-31"))
                .andExpect(jsonPath("$.productor").isString())
                .andExpect(jsonPath("$.resumen.pedidos").value(2))
                .andExpect(jsonPath("$.resumen.bruto").value(55.00))
                .andExpect(jsonPath("$.resumen.comision").value(6.88))
                .andExpect(jsonPath("$.resumen.neto").value(48.12))
                .andExpect(jsonPath("$.resumen.sinComision").value(0))
                // El mas reciente primero
                .andExpect(jsonPath("$.pedidos[0].numero").value("LQ-2"))
                .andExpect(jsonPath("$.pedidos[1].numero").value("LQ-1"))
                // Del pedido mixto solo su linea, nunca la del otro productor
                .andExpect(jsonPath("$.pedidos[1].lineas.length()").value(1))
                .andExpect(jsonPath("$.pedidos[1].bruto").value(25.00))
                .andExpect(jsonPath("$.pedidos[1].comision").value(3.13))
                .andExpect(jsonPath("$.pedidos[1].neto").value(21.87))
                .andExpect(jsonPath("$.pedidos[1].porcentaje").value(12.5))
                // Sin datos del comprador
                .andExpect(jsonPath("$..correo").doesNotExist())
                .andExpect(jsonPath("$..celularContacto").doesNotExist())
                .andExpect(jsonPath("$..personaAutorizada").doesNotExist());

        // El otro productor ve su propia parte del mismo pedido
        mockMvc.perform(get("/api/productor/liquidaciones" + MARZO).cookie(loginAs(OTRO)))
                .andExpect(jsonPath("$.resumen.pedidos").value(1))
                .andExpect(jsonPath("$.resumen.bruto").value(59.70))
                .andExpect(jsonPath("$.resumen.comision").value(7.46))
                .andExpect(jsonPath("$.resumen.neto").value(52.24));
    }

    @Test
    void unPedidoSinComisionConfiguradaSeLiquidaSinDescuento() throws Exception {
        long pedido = pedido("LQ-3", "CONFIRMADO", "2025-03-05 09:00-05", null);
        linea(pedido, PRODUCTOR, "2", "20.00", null);

        mockMvc.perform(get("/api/productor/liquidaciones" + MARZO).cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath("$.resumen.sinComision").value(1))
                .andExpect(jsonPath("$.pedidos[0].porcentaje").doesNotExist())
                .andExpect(jsonPath("$.pedidos[0].bruto").value(40.00))
                .andExpect(jsonPath("$.pedidos[0].comision").value(0))
                .andExpect(jsonPath("$.pedidos[0].neto").value(40.00));
    }

    @Test
    void soloSeLiquidanLosPedidosConfirmadosDelPeriodo() throws Exception {
        long cancelado = pedido("LQ-4", "CANCELADO", "2025-03-12 12:00-05", null);
        linea(cancelado, PRODUCTOR, "1", "99.00", null);
        long pendiente = pedido("LQ-5", "PENDIENTE_PAGO", null, null);
        linea(pendiente, PRODUCTOR, "1", "50.00", null);
        // 23:30 del 31 de marzo en Lima ya es 1 de abril en UTC: cuenta para marzo
        long ultimoDia = pedido("LQ-6", "CONFIRMADO", "2025-03-31 23:30-05", null);
        linea(ultimoDia, PRODUCTOR, "1", "10.00", null);
        long abril = pedido("LQ-7", "CONFIRMADO", "2025-04-01 00:30-05", null);
        linea(abril, PRODUCTOR, "1", "70.00", null);

        mockMvc.perform(get("/api/productor/liquidaciones" + MARZO).cookie(loginAs(PRODUCTOR)))
                .andExpect(jsonPath("$.resumen.pedidos").value(1))
                .andExpect(jsonPath("$.pedidos[0].numero").value("LQ-6"))
                .andExpect(jsonPath("$.resumen.bruto").value(10.00));
    }

    @Test
    void sinFechasEsElMesEnCurso() throws Exception {
        String hoy = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date::text", String.class);
        mockMvc.perform(get("/api/productor/liquidaciones").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(hoy.substring(0, 8) + "01"))
                .andExpect(jsonPath("$.hasta").value(hoy));
    }

    @Test
    void elPeriodoSeValida() throws Exception {
        Cookie sesion = loginAs(PRODUCTOR);
        mockMvc.perform(get("/api/productor/liquidaciones?desde=2025-03-10&hasta=2025-03-01").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        mockMvc.perform(get("/api/productor/liquidaciones?desde=2023-01-01&hasta=2025-03-01").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_MUY_LARGO"));
        mockMvc.perform(get("/api/productor/liquidaciones?desde=marzo").cookie(sesion))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"consumidor@ccpip.test", "sudo@ccpip.test", "sub_admin@ccpip.test",
            "admin_acopio@ccpip.test", "distribuidor@ccpip.test"})
    void laLiquidacionEsSoloDelProductor(String correo) throws Exception {
        mockMvc.perform(get("/api/productor/liquidaciones").cookie(loginAs(correo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void sinSesionNoHayLiquidacion() throws Exception {
        mockMvc.perform(get("/api/productor/liquidaciones")).andExpect(status().isUnauthorized());
    }

    // --- Ayudas ---

    /** Pedido de consumidor@ armado en la base. {@code confirmado} es un timestamptz de SQL o nulo. */
    private long pedido(String numero, String estado, String confirmado, String porcentaje) {
        Long id = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 0, ?, ?::timestamptz
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, numero, estado, confirmado);
        if (porcentaje != null) {
            jdbc.update("""
                    UPDATE pedido SET comision_configuracion_id = (SELECT min(id) FROM comision_configuracion),
                                      comision_porcentaje = ?, comision_base = 'PRECIO_SIN_ENVIO', comision_monto = 0
                    WHERE id = ?""", new BigDecimal(porcentaje), id);
        }
        return id;
    }

    /** Una linea del productor indicado con su comision copiada, o sin ella si {@code comision} es nula. */
    private void linea(long pedidoId, String correoProductor, String cantidad, String precio, String comision) {
        BigDecimal subtotal = new BigDecimal(precio).multiply(new BigDecimal(cantidad));
        BigDecimal monto = comision == null ? null : new BigDecimal(comision);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal,
                                         comision_monto, neto)
                SELECT ?, pr.id, pr.productor_id, ?, ?, ?, ?, ?
                FROM producto pr JOIN productor p ON p.id = pr.productor_id JOIN usuario u ON u.id = p.usuario_id
                WHERE u.correo = ? ORDER BY pr.id LIMIT 1""",
                pedidoId, new BigDecimal(cantidad), new BigDecimal(precio), subtotal, monto,
                monto == null ? null : subtotal.subtract(monto), correoProductor);
    }
}
