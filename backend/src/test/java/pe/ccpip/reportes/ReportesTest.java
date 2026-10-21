package pe.ccpip.reportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-345 (RF-235): el superadministrador consulta las ventas acumuladas de la plataforma por periodo,
 * con su desglose B2C (tarifa minorista) y B2B (tarifa mayorista).
 *
 * <p>Los pedidos de la prueba se confirman en mayo y junio de 2025, meses que ninguna otra prueba usa, y
 * la transaccion se deshace al terminar.
 */
@Transactional
class ReportesTest extends IntegrationTest {

    private static final String SUPER = "sudo@ccpip.test";
    private static final String CUSCO = "Centro Regional Cusco";

    @Test
    void lasVentasSeSeparanEnB2cYB2bYSeAcumulanPorMes() throws Exception {
        ventasDePrueba();

        mockMvc.perform(get("/api/admin/reportes/ventas?desde=2025-05-01&hasta=2025-06-30&agrupacion=MES").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrica").value("VENTAS"))
                .andExpect(jsonPath("$.agrupacion").value("MES"))
                .andExpect(jsonPath("$.periodos.length()").value(2))
                .andExpect(jsonPath("$.periodos[0].inicio").value("2025-05-01"))
                .andExpect(jsonPath("$.periodos[0].fin").value("2025-05-31"))
                .andExpect(jsonPath("$.periodos[0].b2c").value(40.00))
                .andExpect(jsonPath("$.periodos[0].b2b").value(100.00))
                .andExpect(jsonPath("$.periodos[0].total").value(140.00))
                .andExpect(jsonPath("$.periodos[0].acumulado").value(140.00))
                .andExpect(jsonPath("$.periodos[1].b2c").value(0))
                .andExpect(jsonPath("$.periodos[1].b2b").value(50.00))
                .andExpect(jsonPath("$.periodos[1].acumulado").value(190.00))
                .andExpect(jsonPath("$.totales.b2c").value(40.00))
                .andExpect(jsonPath("$.totales.b2b").value(150.00))
                .andExpect(jsonPath("$.totales.total").value(190.00))
                // Agregados: sin compradores ni productores
                .andExpect(jsonPath("$..correo").doesNotExist())
                .andExpect(jsonPath("$..productor").doesNotExist())
                .andExpect(jsonPath("$..numero").doesNotExist());
    }

    @Test
    void lasSemanasEmpiezanEnLunesYSeRecortanAlRango() throws Exception {
        ventasDePrueba();

        // Del miercoles 7 al martes 20 de mayo de 2025
        mockMvc.perform(get("/api/admin/reportes/ventas?desde=2025-05-07&hasta=2025-05-20&agrupacion=SEMANA").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodos.length()").value(3))
                .andExpect(jsonPath("$.periodos[0].inicio").value("2025-05-07"))
                .andExpect(jsonPath("$.periodos[0].fin").value("2025-05-11"))
                .andExpect(jsonPath("$.periodos[1].inicio").value("2025-05-12"))
                .andExpect(jsonPath("$.periodos[1].fin").value("2025-05-18"))
                .andExpect(jsonPath("$.periodos[2].inicio").value("2025-05-19"))
                .andExpect(jsonPath("$.periodos[2].fin").value("2025-05-20"))
                .andExpect(jsonPath("$.periodos[2].total").value(110.00))
                // El pedido del 3 de mayo queda fuera del rango
                .andExpect(jsonPath("$.totales.total").value(110.00));
    }

    @Test
    void porDiaIncluyeLosDiasSinVentas() throws Exception {
        ventasDePrueba();

        mockMvc.perform(get("/api/admin/reportes/ventas?desde=2025-05-01&hasta=2025-05-31&agrupacion=DIA").cookie(loginAs(SUPER)))
                .andExpect(jsonPath("$.periodos.length()").value(31))
                .andExpect(jsonPath("$.periodos[0].total").value(0))
                .andExpect(jsonPath("$.periodos[2].inicio").value("2025-05-03"))
                .andExpect(jsonPath("$.periodos[2].total").value(30.00))
                .andExpect(jsonPath("$.periodos[30].acumulado").value(140.00));
    }

    @Test
    void sinParametrosEsElAnioEnCursoPorMes() throws Exception {
        String hoy = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date::text", String.class);
        mockMvc.perform(get("/api/admin/reportes/ventas").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(hoy.substring(0, 4) + "-01-01"))
                .andExpect(jsonPath("$.hasta").value(hoy))
                .andExpect(jsonPath("$.agrupacion").value("MES"))
                .andExpect(jsonPath("$.periodos.length()").value(Integer.parseInt(hoy.substring(5, 7))));
    }

    @Test
    void elPeriodoYLaAgrupacionSeValidan() throws Exception {
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(get("/api/admin/reportes/ventas?desde=2025-05-10&hasta=2025-05-01").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        mockMvc.perform(get("/api/admin/reportes/ventas?desde=2023-01-01&hasta=2025-05-01").cookie(sesion))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_MUY_LARGO"));
        mockMvc.perform(get("/api/admin/reportes/ventas?agrupacion=ANIO").cookie(sesion))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sub_admin@ccpip.test", "consumidor@ccpip.test", "productor@ccpip.test",
            "admin_acopio@ccpip.test", "distribuidor@ccpip.test"})
    void losReportesSonDelSuperadministrador(String correo) throws Exception {
        mockMvc.perform(get("/api/admin/reportes/ventas").cookie(loginAs(correo)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void sinSesionNoHayReportes() throws Exception {
        mockMvc.perform(get("/api/admin/reportes/ventas")).andExpect(status().isUnauthorized());
    }

    @Test
    void laCompraCopiaEnCadaLineaLaTarifaConLaQueSeVendio() throws Exception {
        Cookie comprador = comprador();
        // Desde 3 unidades, la oferta AMBAS se vende al precio mayorista
        long porMayor = productoEnCusco("Cafe por mayor", "AMBAS", "25.00", "20.00", 3);
        long alPorMenor = productoEnCusco("Cacao al por menor", "AMBAS", "25.00", "20.00", 3);
        alCarrito(comprador, porMayor, 3);
        alCarrito(comprador, alPorMenor, 1);
        long pedido = objectMapper.readTree(mockMvc.perform(post("/api/pedidos").cookie(comprador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("puntoId", puntoCusco(), "celularContacto", "987654321"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        List<Map<String, Object>> lineas = jdbc.queryForList(
                "SELECT producto_id, tarifa, precio_unitario FROM item_pedido WHERE pedido_id = ? ORDER BY producto_id", pedido);
        assertThat(lineas).extracting(l -> ((Number) l.get("producto_id")).longValue(), l -> l.get("tarifa"),
                        l -> ((BigDecimal) l.get("precio_unitario")).toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(porMayor, "MAYORISTA", "20.00"),
                        org.assertj.core.groups.Tuple.tuple(alPorMenor, "MINORISTA", "25.00"));
    }

    @Test
    void laTarifaSoloAdmiteMinoristaOMayorista() {
        long pedido = pedido("RP-X", "CONFIRMADO", "2025-05-03 10:00-05");
        assertRechazaLaBase(() -> linea(pedido, "10.00", "OTRA"), "item_pedido_tarifa_valida");
    }

    // --- Ayudas ---

    /**
     * Mayo de 2025: el 3, 30.00 B2C; el 20, 100.00 B2B y 10.00 B2C; el 10, uno cancelado y uno sin pagar
     * que no son ventas. Junio: el 15, 50.00 B2B.
     */
    private void ventasDePrueba() {
        linea(pedido("RP-1", "CONFIRMADO", "2025-05-03 10:00-05"), "30.00", "MINORISTA");
        long mixto = pedido("RP-2", "ENTREGADO", "2025-05-20 23:30-05");
        linea(mixto, "100.00", "MAYORISTA");
        linea(mixto, "10.00", "MINORISTA");
        linea(pedido("RP-3", "CANCELADO", "2025-05-10 10:00-05"), "999.00", "MINORISTA");
        linea(pedido("RP-4", "PENDIENTE_PAGO", null), "888.00", "MAYORISTA");
        linea(pedido("RP-5", "CONFIRMADO", "2025-06-15 10:00-05"), "50.00", "MAYORISTA");
    }

    private long pedido(String numero, String estado, String confirmado) {
        return jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 0, ?, ?::timestamptz
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, numero, estado, confirmado);
    }

    /** Una linea de una unidad de un producto de productor@ al precio y la tarifa indicados. */
    private void linea(long pedidoId, String subtotal, String tarifa) {
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal, tarifa)
                SELECT ?, pr.id, pr.productor_id, 1, ?, ?, ?
                FROM producto pr JOIN productor p ON p.id = pr.productor_id JOIN usuario u ON u.id = p.usuario_id
                WHERE u.correo = 'productor@ccpip.test' ORDER BY pr.id LIMIT 1""",
                pedidoId, new BigDecimal(subtotal), new BigDecimal(subtotal), tarifa);
    }

    private Cookie comprador() throws Exception {
        String correo = "comprador.reportes@ccpip.test";
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Comprador de Prueba', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", correo);
        return loginAs(correo);
    }

    /** Oferta publicada y autorizada de productor@ con 5 unidades en el punto de Cusco. */
    private long productoEnCusco(String nombre, String modalidad, String minorista, String mayorista, int minimo) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, modalidad,
                                      precio_mayorista, cantidad_minima_mayorista, estado, publicado_en)
                SELECT p.id, ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = 'ARTESANIA'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'UNIDAD'), ?, ?, ?, ?, 'PUBLICADO', now()
                FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor@ccpip.test'
                RETURNING id""", Long.class, nombre + " " + UUID.randomUUID(), new BigDecimal(minorista), modalidad,
                new BigDecimal(mayorista), minimo);
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, linea_id FROM producto WHERE id = ?", id, id);
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion)
                SELECT ?, u.id, 'L-R' || ?, 5, 5, current_date - 10 FROM ubicacion u WHERE u.nombre = ?""", id, id, CUSCO);
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) SELECT ?, id, 5 FROM ubicacion WHERE nombre = ?",
                id, CUSCO);
        return id;
    }

    private void alCarrito(Cookie comprador, long productoId, int cantidad) throws Exception {
        mockMvc.perform(post("/api/carrito/items").cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productoId", productoId, "cantidad", cantidad))))
                .andExpect(status().isOk());
    }

    private long puntoCusco() {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, CUSCO);
    }
}
