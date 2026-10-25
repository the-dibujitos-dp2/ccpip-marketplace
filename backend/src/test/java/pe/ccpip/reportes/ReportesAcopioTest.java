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
 * Pantalla «Reportes» del administrador de punto de acopio: lo que paso en sus puntos por periodo.
 * CCPIP-117 (RF-65): entradas; CCPIP-341 (RF-231): salidas; CCPIP-342 (RF-232): ventas;
 * CCPIP-343 (RF-233): inventario.
 *
 * <p>admin_acopio@ administra el Centro Nacional Lima; lo de Cusco no es suyo. Los datos son de mayo y
 * junio de 2024, meses que ninguna otra prueba usa, y la transaccion se deshace al terminar.
 */
@Transactional
class ReportesAcopioTest extends IntegrationTest {

    private static final String ADMIN = "admin_acopio@ccpip.test";
    private static final String LIMA = "Centro Nacional Lima";
    private static final String CUSCO = "Centro Regional Cusco";
    private static final String ENTRADAS = "/api/acopio/reportes/entradas";
    private static final String SALIDAS = "/api/acopio/reportes/salidas";
    private static final String VENTAS = "/api/acopio/reportes/ventas";
    private static final String INVENTARIO = "/api/acopio/reportes/inventario";

    private long aceite;
    private long harina;
    private long soloEnCusco;

    @BeforeEach
    void productos() {
        aceite = productoConLote("RA Aceite", "PUBLICADO", null, null);
        harina = productoConLote("RA Harina", "PUBLICADO", null, null, "productor2@ccpip.test");
        soloEnCusco = productoConLote("RA Solo en Cusco", "PUBLICADO", null, null);
    }

    @Test
    void lasEntradasSeparanRecepcionYTransferenciasYCuentanLoQueAunNoLlega() throws Exception {
        entradasDePrueba();

        mockMvc.perform(get(ENTRADAS + "?desde=2024-05-01&hasta=2024-06-30").cookie(loginAs(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.puntos").value(LIMA))
                .andExpect(jsonPath("$.agrupacion").value("MES"))
                .andExpect(jsonPath("$.periodos.length()").value(2))
                .andExpect(jsonPath("$.periodos[0].recepcion").value(50))
                .andExpect(jsonPath("$.periodos[0].transferencias").value(12))
                .andExpect(jsonPath("$.periodos[0].total").value(62))
                .andExpect(jsonPath("$.periodos[1].recepcion").value(30))
                .andExpect(jsonPath("$.periodos[1].transferencias").value(0))
                .andExpect(jsonPath("$.periodos[1].acumulado").value(92))
                .andExpect(jsonPath("$.totales.recepcion").value(80))
                .andExpect(jsonPath("$.totales.transferencias").value(12))
                .andExpect(jsonPath("$.totales.total").value(92))
                // No atendidas: la transferencia pedida en junio que aun no se recibe
                .andExpect(jsonPath("$.totales.transferenciasPendientes").value(1))
                .andExpect(jsonPath("$.totales.unidadesPendientes").value(7))
                // Por producto con su productor; lo de Cusco no aparece
                .andExpect(jsonPath("$.porProducto.length()").value(2))
                .andExpect(jsonPath("$.porProducto[0].producto").value("RA Aceite"))
                .andExpect(jsonPath("$.porProducto[0].productor").exists())
                .andExpect(jsonPath("$.porProducto[0].total").value(62))
                .andExpect(jsonPath("$.porProducto[1].producto").value("RA Harina"))
                .andExpect(jsonPath("$.productos[?(@.nombre == 'RA Solo en Cusco')]").doesNotExist())
                .andExpect(jsonPath("$..correo").doesNotExist());
    }

    @Test
    void lasEntradasSeFiltranPorProductorYProducto() throws Exception {
        entradasDePrueba();
        Cookie sesion = loginAs(ADMIN);
        long productor2 = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, harina);

        mockMvc.perform(get(ENTRADAS + "?desde=2024-05-01&hasta=2024-06-30&productor=" + productor2).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productor").value(productor2))
                .andExpect(jsonPath("$.totales.total").value(30))
                .andExpect(jsonPath("$.totales.unidadesPendientes").value(7))
                .andExpect(jsonPath("$.porProducto.length()").value(1));

        mockMvc.perform(get(ENTRADAS + "?desde=2024-05-01&hasta=2024-06-30&producto=" + aceite).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.total").value(62))
                .andExpect(jsonPath("$.totales.transferenciasPendientes").value(0));
    }

    @Test
    void lasSalidasSeparanLasVentasAtendidasDeLasPorEntregar() throws Exception {
        salidasDePrueba();

        mockMvc.perform(get(SALIDAS + "?desde=2024-05-01&hasta=2024-06-30").cookie(loginAs(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodos.length()").value(2))
                .andExpect(jsonPath("$.periodos[0].ventasEntregadas").value(5))
                .andExpect(jsonPath("$.periodos[0].ventasPorEntregar").value(0))
                .andExpect(jsonPath("$.periodos[0].total").value(5))
                .andExpect(jsonPath("$.periodos[1].ventasEntregadas").value(0))
                .andExpect(jsonPath("$.periodos[1].ventasPorEntregar").value(3))
                .andExpect(jsonPath("$.periodos[1].transferencias").value(4))
                .andExpect(jsonPath("$.periodos[1].devoluciones").value(2))
                .andExpect(jsonPath("$.periodos[1].merma").value(1))
                .andExpect(jsonPath("$.periodos[1].total").value(10))
                .andExpect(jsonPath("$.periodos[1].acumulado").value(15))
                .andExpect(jsonPath("$.totales.ventasEntregadas").value(5))
                .andExpect(jsonPath("$.totales.ventasPorEntregar").value(3))
                .andExpect(jsonPath("$.totales.total").value(15))
                // La merma de Cusco no es de sus puntos
                .andExpect(jsonPath("$.porProducto.length()").value(1))
                .andExpect(jsonPath("$.porProducto[0].producto").value("RA Aceite"))
                .andExpect(jsonPath("$.porProducto[0].ventas").value(8))
                .andExpect(jsonPath("$.porProducto[0].transferencias").value(4))
                .andExpect(jsonPath("$.porProducto[0].devoluciones").value(2))
                .andExpect(jsonPath("$.porProducto[0].merma").value(1))
                .andExpect(jsonPath("$.porProducto[0].total").value(15))
                .andExpect(jsonPath("$..numero").doesNotExist());
    }

    @Test
    void lasVentasDelPuntoCuentanPedidosAtendidosYPorEntregar() throws Exception {
        long enLima = lote(aceite, LIMA, "RA-V1", "50", "2024-05-02 09:00-05");
        venta(enLima, "RAV-1", "ENTREGADO", "5", "2024-05-20 10:00-05");
        venta(enLima, "RAV-2", "LISTO_PARA_RECOJO", "3", "2024-06-05 10:00-05", "MAYORISTA");
        venta(enLima, "RAV-3", "CANCELADO", "9", "2024-06-06 10:00-05");
        long enCusco = lote(aceite, CUSCO, "RA-V2", "20", "2024-05-02 09:00-05");
        venta(enCusco, "RAV-4", "ENTREGADO", "7", "2024-05-21 10:00-05");

        mockMvc.perform(get(VENTAS + "?desde=2024-05-01&hasta=2024-06-30").cookie(loginAs(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodos[0].pedidos").value(1))
                .andExpect(jsonPath("$.periodos[0].pedidosAtendidos").value(1))
                .andExpect(jsonPath("$.periodos[0].unidades").value(5))
                .andExpect(jsonPath("$.periodos[0].b2c").value(5.00))
                .andExpect(jsonPath("$.periodos[1].pedidosPorEntregar").value(1))
                .andExpect(jsonPath("$.periodos[1].b2b").value(3.00))
                .andExpect(jsonPath("$.periodos[1].acumulado").value(8.00))
                // Ni el pedido cancelado ni la venta de Cusco
                .andExpect(jsonPath("$.totales.pedidos").value(2))
                .andExpect(jsonPath("$.totales.pedidosAtendidos").value(1))
                .andExpect(jsonPath("$.totales.pedidosPorEntregar").value(1))
                .andExpect(jsonPath("$.totales.unidades").value(8))
                .andExpect(jsonPath("$.totales.total").value(8.00))
                .andExpect(jsonPath("$.porProducto.length()").value(1))
                .andExpect(jsonPath("$.porProducto[0].producto").value("RA Aceite"))
                .andExpect(jsonPath("$.porProducto[0].unidades").value(8))
                .andExpect(jsonPath("$..numero").doesNotExist())
                .andExpect(jsonPath("$..correo").doesNotExist());
    }

    @Test
    void elInventarioExplicaCadaSaldoYCuentaLoVendidoQueSigueEnElPunto() throws Exception {
        salidasDePrueba();
        Cookie sesion = loginAs(ADMIN);

        // Junio parte de lo que quedo en mayo: 50 recibidos menos 5 vendidos
        mockMvc.perform(get(INVENTARIO + "?desde=2024-06-01&hasta=2024-06-30&agrupacion=SEMANA&producto=" + aceite)
                        .cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodos[0].inicio").value("2024-06-01"))
                .andExpect(jsonPath("$.periodos[0].saldoInicial").value(45))
                // Semana del 3 al 9: venta de 3, transferencia de 4 y devolucion de 2
                .andExpect(jsonPath("$.periodos[1].salidas").value(9))
                .andExpect(jsonPath("$.periodos[1].saldoFinal").value(36))
                .andExpect(jsonPath("$.periodos[2].saldoInicial").value(36))
                .andExpect(jsonPath("$.totales.saldoInicial").value(45))
                .andExpect(jsonPath("$.totales.entradas").value(0))
                .andExpect(jsonPath("$.totales.salidas").value(10))
                .andExpect(jsonPath("$.totales.saldoFinal").value(35))
                // Los 3 vendidos en el pedido listo para recoger siguen fisicamente en el punto
                .andExpect(jsonPath("$.totales.porEntregar").value(3))
                .andExpect(jsonPath("$.totales.existenciaFisica").value(38))
                // La merma de Cusco no es de sus puntos
                .andExpect(jsonPath("$.porProducto.length()").value(1))
                .andExpect(jsonPath("$.porProducto[0].producto").value("RA Aceite"))
                .andExpect(jsonPath("$.porProducto[0].saldoInicial").value(45))
                .andExpect(jsonPath("$.porProducto[0].salidas").value(10))
                .andExpect(jsonPath("$.porProducto[0].saldoFinal").value(35))
                .andExpect(jsonPath("$.porProducto[0].porEntregar").value(3));

        // A fin de mayo esa venta aun no existia
        mockMvc.perform(get(INVENTARIO + "?desde=2024-05-01&hasta=2024-05-31&producto=" + aceite).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.saldoInicial").value(0))
                .andExpect(jsonPath("$.totales.entradas").value(50))
                .andExpect(jsonPath("$.totales.salidas").value(5))
                .andExpect(jsonPath("$.totales.saldoFinal").value(45))
                .andExpect(jsonPath("$.totales.porEntregar").value(0));
    }

    @Test
    void loAjenoNoExisteYOtroRolNoEntra() throws Exception {
        entradasDePrueba();
        Cookie sesion = loginAs(ADMIN);
        mockMvc.perform(get(ENTRADAS + "?producto=" + soloEnCusco).cookie(sesion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        mockMvc.perform(get(ENTRADAS + "?productor=999999").cookie(sesion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTOR_NO_ENCONTRADO"));
        mockMvc.perform(get(ENTRADAS + "?desde=2024-06-30&hasta=2024-05-01").cookie(sesion))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get(ENTRADAS).cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isForbidden());
    }

    /**
     * Lima: lote de aceite de 50 el 2 de mayo, 12 de aceite recibidos de Cusco por transferencia el 15 de mayo y
     * lote de harina de 30 el 3 de junio. Pendiente: 7 de harina pedidos a Lima el 10 de junio. En Cusco, que
     * no es suyo: un lote de 99 el 5 de mayo.
     */
    private void entradasDePrueba() {
        lote(aceite, LIMA, "RA-A1", "50", "2024-05-02 09:00-05");
        long enCusco = lote(aceite, CUSCO, "RA-C1", "20", "2024-04-01 09:00-05");
        transferenciaRecibida(enCusco, "RA-T1", "12", "2024-05-15 09:00-05");
        long harinaLima = lote(harina, LIMA, "RA-H1", "30", "2024-06-03 09:00-05");
        transferenciaPendiente(harinaLima, "7", "2024-06-10 09:00-05");
        lote(soloEnCusco, CUSCO, "RA-X1", "99", "2024-05-05 09:00-05");
    }

    /**
     * Lima: lote de aceite de 50 el 2 de mayo. Mayo: 5 vendidos en un pedido ya entregado. Junio: 3 vendidos en
     * un pedido listo para recoger (por entregar), 4 transferidos a Cusco, 2 devueltos al productor y 1 de
     * merma. En Cusco, que no es suyo: 9 de merma.
     */
    private void salidasDePrueba() {
        long enLima = lote(aceite, LIMA, "RA-S1", "50", "2024-05-02 09:00-05");
        venta(enLima, "RAS-1", "ENTREGADO", "5", "2024-05-20 10:00-05");
        venta(enLima, "RAS-2", "LISTO_PARA_RECOJO", "3", "2024-06-05 10:00-05");
        long transferencia = transferencia(enLima, LIMA, CUSCO, "DESPACHADA", "2024-06-08 10:00-05");
        long item = jdbc.queryForObject("""
                INSERT INTO transferencia_item (transferencia_id, producto_id, lote_origen_id, cantidad)
                SELECT ?, producto_id, id, 4 FROM lote WHERE id = ? RETURNING id""", Long.class, transferencia, enLima);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, transferencia_item_id,
                                                   registrado_en)
                SELECT producto_id, ubicacion_id, id, 'TRANSFERENCIA_SALIDA', 4, ?, '2024-06-08 10:00-05'::timestamptz
                FROM lote WHERE id = ?""", item, enLima);
        salida(enLima, "SALIDA", "DEVOLUCION_PRODUCTOR", "2", "2024-06-09 10:00-05");
        salida(enLima, "MERMA", null, "1", "2024-06-12 10:00-05");
        long enCusco = lote(aceite, CUSCO, "RA-S2", "20", "2024-05-02 09:00-05");
        salida(enCusco, "MERMA", null, "9", "2024-05-10 10:00-05");
    }

    private void venta(long lote, String numero, String estado, String cantidad, String cuando) {
        venta(lote, numero, estado, cantidad, cuando, "MINORISTA");
    }

    /** Un pedido confirmado en {@code cuando}, ahora en {@code estado}, con su SALIDA_VENTA del lote. */
    private void venta(long lote, String numero, String estado, String cantidad, String cuando, String tarifa) {
        BigDecimal unidades = new BigDecimal(cantidad);
        long pedido = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.ubicacion_id, ?, ?, ?::timestamptz
                FROM usuario u, lote l WHERE u.correo = 'consumidor@ccpip.test' AND l.id = ?
                RETURNING id""", Long.class, numero, unidades, estado, cuando, lote);
        long item = jdbc.queryForObject("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal, tarifa)
                SELECT ?, pr.id, pr.productor_id, ?, 1, ?, ? FROM lote l JOIN producto pr ON pr.id = l.producto_id
                WHERE l.id = ? RETURNING id""", Long.class, pedido, unidades, unidades, tarifa, lote);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, pedido_id, item_pedido_id,
                                                   registrado_en)
                SELECT producto_id, ubicacion_id, id, 'SALIDA_VENTA', ?, ?, ?, ?::timestamptz FROM lote WHERE id = ?""",
                unidades, pedido, item, cuando, lote);
    }

    /** Una salida manual (con motivo) o una merma del lote, registrada en {@code cuando}. */
    private void salida(long lote, String tipo, String motivo, String cantidad, String cuando) {
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, motivo, cantidad, usuario_id,
                                                   registrado_en)
                SELECT producto_id, ubicacion_id, id, ?, ?, ?, (SELECT id FROM usuario WHERE correo = ?), ?::timestamptz
                FROM lote WHERE id = ?""", tipo, motivo, new BigDecimal(cantidad), ADMIN, cuando, lote);
    }

    /** Un lote en la ubicacion indicada, con la ENTRADA de su registro en {@code cuando}. */
    private long lote(long productoId, String ubicacion, String codigo, String cantidad, String cuando) {
        BigDecimal unidades = new BigDecimal(cantidad);
        long id = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, registrado_en)
                SELECT ?, id, ?, ?, ?, ?::timestamptz FROM ubicacion WHERE nombre = ?
                RETURNING id""", Long.class, productoId, codigo, unidades, unidades, cuando, ubicacion);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, registrado_en)
                SELECT producto_id, ubicacion_id, id, 'ENTRADA', ?, ?::timestamptz FROM lote WHERE id = ?""",
                unidades, cuando, id);
        return id;
    }

    /** Una transferencia de Cusco a Lima ya recibida: el lote nuevo en Lima con su TRANSFERENCIA_ENTRADA. */
    private void transferenciaRecibida(long loteOrigen, String codigo, String cantidad, String cuando) {
        BigDecimal unidades = new BigDecimal(cantidad);
        long transferencia = transferencia(loteOrigen, CUSCO, LIMA, "RECIBIDA", cuando);
        long destino = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, registrado_en)
                SELECT l.producto_id, u.id, ?, ?, ?, ?::timestamptz FROM lote l, ubicacion u WHERE l.id = ? AND u.nombre = ?
                RETURNING id""", Long.class, codigo, unidades, unidades, cuando, loteOrigen, LIMA);
        long item = jdbc.queryForObject("""
                INSERT INTO transferencia_item (transferencia_id, producto_id, lote_origen_id, cantidad, lote_destino_id)
                SELECT ?, producto_id, id, ?, ? FROM lote WHERE id = ? RETURNING id""", Long.class,
                transferencia, unidades, destino, loteOrigen);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, transferencia_item_id,
                                                   registrado_en)
                SELECT producto_id, ubicacion_id, id, 'TRANSFERENCIA_ENTRADA', ?, ?, ?::timestamptz FROM lote WHERE id = ?""",
                unidades, item, cuando, destino);
    }

    /** Una transferencia hacia Lima pedida en {@code cuando} que aun no se recibe. */
    private void transferenciaPendiente(long lote, String cantidad, String cuando) {
        long transferencia = transferencia(lote, CUSCO, LIMA, "SOLICITADA", cuando);
        jdbc.update("""
                INSERT INTO transferencia_item (transferencia_id, producto_id, lote_origen_id, cantidad)
                SELECT ?, producto_id, id, ? FROM lote WHERE id = ?""", transferencia, new BigDecimal(cantidad), lote);
    }

    private long transferencia(long lote, String origen, String destino, String estado, String cuando) {
        return jdbc.queryForObject("""
                INSERT INTO transferencia (tipo, productor_id, origen_id, destino_id, estado, creada_en)
                SELECT 'ENTRE_PUNTOS', pr.productor_id, (SELECT id FROM ubicacion WHERE nombre = ?),
                       (SELECT id FROM ubicacion WHERE nombre = ?), ?, ?::timestamptz
                FROM lote l JOIN producto pr ON pr.id = l.producto_id WHERE l.id = ? RETURNING id""", Long.class,
                origen, destino, estado, cuando, lote);
    }
}
