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

import jakarta.servlet.http.Cookie;

import pe.ccpip.support.IntegrationTest;

/**
 * Reportes de gestion del superadministrador (SA-05, SA-10). CCPIP-122 (RF-86): productores con mayores
 * ventas; CCPIP-355 (RF-245) y CCPIP-356 (RF-246): ventas y denuncias de los puntos de acopio; CCPIP-123
 * (RF-87) y CCPIP-352 (RF-242): calificaciones de productos y de productores; CCPIP-80 (RF-66): origen y
 * destino de las ventas.
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
    private int pedidos;

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
    void lasDenunciasSumanEnCadaPuntoDondeEstaLaOferta() throws Exception {
        lote(aceite, LIMA, "RG-L1");
        lote(aceite, CUSCO, "RG-C1");
        lote(harina, LIMA, "RG-L2");
        // Sobre el aceite (esta en Lima y Cusco), aun sin resolver
        denuncia(aceite, null, "RECIBIDA", "2023-09-10 10:00-05");
        // Sobre productor2@ sin nombrar producto: su harina esta en Lima
        denuncia(null, "productor2@ccpip.test", "RESUELTA", "2023-09-11 10:00-05");
        // Fuera del rango
        denuncia(aceite, null, "DESESTIMADA", "2023-08-11 10:00-05");

        mockMvc.perform(get("/api/admin/reportes/puntos" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.denuncias.total").value(2))
                .andExpect(jsonPath("$.denuncias.abiertas").value(1))
                .andExpect(jsonPath("$.denuncias.resueltas").value(1))
                .andExpect(jsonPath("$.denuncias.desestimadas").value(0))
                .andExpect(jsonPath("$.puntos[?(@.punto == '" + LIMA + "')].denuncias").value(2))
                .andExpect(jsonPath("$.puntos[?(@.punto == '" + LIMA + "')].denunciasAbiertas").value(1))
                .andExpect(jsonPath("$.puntos[?(@.punto == '" + CUSCO + "')].denuncias").value(1))
                .andExpect(jsonPath("$.puntos[?(@.punto == '" + CUSCO + "')].denunciasAbiertas").value(1))
                // Sin el detalle de la denuncia ni quien la hizo
                .andExpect(jsonPath("$..descripcion").doesNotExist())
                .andExpect(jsonPath("$..denunciante").doesNotExist());
    }

    @Test
    void lasCalificacionesMuestranElPromedioDesdeElMinimoYSuTendencia() throws Exception {
        calificacionesDePrueba();

        mockMvc.perform(get("/api/admin/reportes/calificaciones" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimo").value(2))
                // Septiembre tiene 29 dias despues del 1: el anterior va del 2 al 31 de agosto
                .andExpect(jsonPath("$.anteriorDesde").value("2023-08-02"))
                .andExpect(jsonPath("$.anteriorHasta").value("2023-08-31"))
                .andExpect(jsonPath("$.resumen.resenas").value(3))
                .andExpect(jsonPath("$.resumen.promedio").value(4.0))
                .andExpect(jsonPath("$.resumen.distribucion[0].estrellas").value(5))
                .andExpect(jsonPath("$.resumen.distribucion[0].cantidad").value(1))
                .andExpect(jsonPath("$.resumen.distribucion[3].estrellas").value(2))
                .andExpect(jsonPath("$.resumen.distribucion[3].cantidad").value(0))
                .andExpect(jsonPath("$.productos.length()").value(2))
                .andExpect(jsonPath("$.productos[0].producto").value("RG Aceite"))
                .andExpect(jsonPath("$.productos[0].promedio").value(4.5))
                .andExpect(jsonPath("$.productos[0].resenas").value(2))
                .andExpect(jsonPath("$.productos[0].denuncias").value(1))
                // De 2.0 en agosto a 4.5 en septiembre
                .andExpect(jsonPath("$.productos[0].tendencia").value("SUBE"))
                // Una sola calificacion no alcanza el minimo: sin promedio ni tendencia
                .andExpect(jsonPath("$.productos[1].producto").value("RG Harina"))
                .andExpect(jsonPath("$.productos[1].promedio").doesNotExist())
                .andExpect(jsonPath("$.productos[1].tendencia").doesNotExist())
                // Por productor: productor@ (aceite) y productor2@ (harina)
                .andExpect(jsonPath("$.productores.length()").value(2))
                .andExpect(jsonPath("$.productores[0].promedio").value(4.5))
                .andExpect(jsonPath("$.productores[0].productos").value(1))
                .andExpect(jsonPath("$.productores[0].denuncias").value(1))
                .andExpect(jsonPath("$.productores[0].tendencia").value("SUBE"))
                .andExpect(jsonPath("$.productores[0].pueblo").exists())
                .andExpect(jsonPath("$.productores[1].resenas").value(1))
                .andExpect(jsonPath("$.productores[1].promedio").doesNotExist())
                .andExpect(jsonPath("$.lineas[?(@.codigo == 'SUPERFRUTOS')]").exists())
                // Ni quien califico ni sus comentarios
                .andExpect(jsonPath("$..comentario").doesNotExist())
                .andExpect(jsonPath("$..correo").doesNotExist());
    }

    @Test
    void lasCalificacionesSeFiltranPorLinea() throws Exception {
        calificacionesDePrueba();
        Cookie sesion = loginAs(SUPER);

        mockMvc.perform(get("/api/admin/reportes/calificaciones" + SEPTIEMBRE + "&linea=CAFE").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linea").value("CAFE"))
                .andExpect(jsonPath("$.resumen.resenas").value(0))
                .andExpect(jsonPath("$.resumen.promedio").doesNotExist())
                .andExpect(jsonPath("$.productos.length()").value(0))
                .andExpect(jsonPath("$.productores.length()").value(0));
        mockMvc.perform(get("/api/admin/reportes/calificaciones" + SEPTIEMBRE + "&linea=SUPERFRUTOS").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumen.resenas").value(3));
        mockMvc.perform(get("/api/admin/reportes/calificaciones?linea=NO_EXISTE").cookie(sesion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINEA_NO_ENCONTRADA"));
    }

    @Test
    void elOrigenYElDestinoDeLasVentas() throws Exception {
        ventasDePrueba();
        // El envio de la harina va a Cusco
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular)
                SELECT id, '080101', 'Av. de prueba 123', 'Contacto de prueba', '987654321' FROM pedido WHERE numero = 'RG-3'""");

        mockMvc.perform(get("/api/admin/reportes/origen-destino" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidos").value(3))
                .andExpect(jsonPath("$.total").value(180.00))
                .andExpect(jsonPath("$.porPunto[0].nombre").value(CUSCO))
                .andExpect(jsonPath("$.porPunto[0].detalle").value("Cusco"))
                .andExpect(jsonPath("$.porPunto[1].nombre").value(LIMA))
                .andExpect(jsonPath("$.porPunto[1].pedidos").value(2))
                .andExpect(jsonPath("$.porPunto[1].participacion").value(44.4))
                .andExpect(jsonPath("$.porProductor[0].total").value(130.00))
                .andExpect(jsonPath("$.porProductor[0].detalle").exists())
                // Cusco recibe el recojo de Cusco y el envio desde Lima
                .andExpect(jsonPath("$.porDestino[0].departamento").value("Cusco"))
                .andExpect(jsonPath("$.porDestino[0].pedidos").value(2))
                .andExpect(jsonPath("$.porDestino[0].envios").value(1))
                .andExpect(jsonPath("$.porDestino[0].recojos").value(1))
                .andExpect(jsonPath("$.porDestino[0].total").value(150.00))
                .andExpect(jsonPath("$.porDestino[0].participacion").value(83.3))
                .andExpect(jsonPath("$.porDestino[1].departamento").value("Lima"))
                .andExpect(jsonPath("$.rutas.length()").value(3))
                .andExpect(jsonPath("$.rutas[1].punto").value(LIMA))
                .andExpect(jsonPath("$.rutas[1].destino").value("Cusco"))
                .andExpect(jsonPath("$.rutas[1].total").value(50.00))
                // Ninguna direccion ni comprador
                .andExpect(jsonPath("$..direccion").doesNotExist())
                .andExpect(jsonPath("$..contacto_nombre").doesNotExist())
                .andExpect(jsonPath("$..correo").doesNotExist());
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
        mockMvc.perform(get("/api/admin/reportes/calificaciones").cookie(loginAs(correo))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reportes/origen-destino").cookie(loginAs(correo))).andExpect(status().isForbidden());
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

    private void lote(long productoId, String punto, String codigo) {
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual)
                SELECT ?, id, ?, 10, 10 FROM ubicacion WHERE nombre = ?""", productoId, codigo, punto);
    }

    /** Una denuncia sobre un producto o, si {@code productoId} es nulo, sobre el productor de {@code correo}. */
    private void denuncia(Long productoId, String correoProductor, String estado, String cuando) {
        jdbc.update("""
                INSERT INTO denuncia (denunciante_id, productor_id, producto_id, motivo, descripcion, estado, creada_en)
                SELECT (SELECT id FROM usuario WHERE correo = 'consumidor@ccpip.test'),
                       coalesce((SELECT productor_id FROM producto WHERE id = ?),
                                (SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?)),
                       ?, 'CALIDAD', 'Prueba de reporte', ?, ?::timestamptz""",
                productoId, correoProductor, productoId, estado, cuando);
    }

    /**
     * Minimo de 2 calificaciones. Septiembre: el aceite recibe 5 y 4 estrellas y una denuncia; la harina, 3.
     * Agosto: el aceite recibio 2 y 2.
     */
    private void calificacionesDePrueba() {
        jdbc.update("""
                INSERT INTO parametro_plataforma (clave, valor, registrado_por)
                SELECT 'CALIFICACIONES_MINIMO_PROMEDIO', 2, id FROM usuario WHERE correo = ?""", SUPER);
        calificar(aceite, 5, "2023-09-03 10:00-05");
        calificar(aceite, 4, "2023-09-15 10:00-05");
        calificar(harina, 3, "2023-09-16 10:00-05");
        calificar(aceite, 2, "2023-08-10 10:00-05");
        calificar(aceite, 2, "2023-08-20 10:00-05");
        denuncia(aceite, null, "EN_REVISION", "2023-09-17 10:00-05");
    }

    /** Un pedido entregado al consumidor con una linea del producto, calificada en {@code cuando}. */
    private void calificar(long productoId, int estrellas, String cuando) {
        long pedido = pedido("RGC-" + (++pedidos), "ENTREGADO", "2023-07-01 10:00-05", LIMA, "RECOJO");
        long item = linea(pedido, productoId, 1, "10.00", "MINORISTA");
        jdbc.update("""
                INSERT INTO calificacion (item_pedido_id, pedido_id, producto_id, productor_id, usuario_id, estrellas, calificado_en)
                SELECT i.id, i.pedido_id, i.producto_id, i.productor_id, pe.usuario_id, ?, ?::timestamptz
                FROM item_pedido i JOIN pedido pe ON pe.id = i.pedido_id WHERE i.id = ?""", estrellas, cuando, item);
    }
}
