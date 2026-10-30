package pe.ccpip.reportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Pantalla «Reportes» del productor: estadisticas de sus productos por periodo. CCPIP-114 (RF-25): ventas;
 * CCPIP-333 (RF-223): produccion; CCPIP-334 (RF-224): inventario; CCPIP-115 (RF-26) y CCPIP-335 (RF-225):
 * exportacion en PDF y Excel.
 *
 * <p>Los datos de la prueba son de mayo y junio de 2024, meses que ninguna otra prueba usa, y la transaccion
 * se deshace al terminar.
 */
@Transactional
class ReportesProductorTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String OTRO = "productor2@ccpip.test";
    private static final String VENTAS = "/api/productor/reportes/ventas";
    private static final String PRODUCCION = "/api/productor/reportes/produccion";
    private static final String INVENTARIO = "/api/productor/reportes/inventario";
    private static final String PDF = "/api/productor/reportes/pdf";
    private static final String XLSX = "/api/productor/reportes/xlsx";

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

    @Test
    void laProduccionSonLasEntradasEnSusLotesYCuentaLosLotesNuevos() throws Exception {
        produccionDePrueba();

        mockMvc.perform(get(PRODUCCION + "?desde=2024-05-01&hasta=2024-06-30").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agrupacion").value("MES"))
                .andExpect(jsonPath("$.periodos.length()").value(2))
                // Mayo: lote de 50 y una entrada de produccion de 10 en el mismo lote
                .andExpect(jsonPath("$.periodos[0].unidades").value(60))
                .andExpect(jsonPath("$.periodos[0].lotes").value(1))
                .andExpect(jsonPath("$.periodos[0].acumulado").value(60))
                .andExpect(jsonPath("$.periodos[1].unidades").value(30))
                .andExpect(jsonPath("$.periodos[1].lotes").value(1))
                .andExpect(jsonPath("$.periodos[1].acumulado").value(90))
                .andExpect(jsonPath("$.totales.unidades").value(90))
                .andExpect(jsonPath("$.totales.lotes").value(2))
                // Lo de otro productor no cuenta
                .andExpect(jsonPath("$.porProducto.length()").value(2))
                .andExpect(jsonPath("$.porProducto[0].producto").value("RP Aceite"))
                .andExpect(jsonPath("$.porProducto[0].unidades").value(60))
                .andExpect(jsonPath("$.porProducto[1].producto").value("RP Harina"))
                .andExpect(jsonPath("$.porProducto[1].lotes").value(1));
    }

    @Test
    void laProduccionSeFiltraPorProductoYRechazaUnoAjeno() throws Exception {
        produccionDePrueba();
        Cookie sesion = loginAs(PRODUCTOR);

        mockMvc.perform(get(PRODUCCION + "?desde=2024-05-01&hasta=2024-06-30&producto=" + harina).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.unidades").value(30))
                .andExpect(jsonPath("$.periodos[0].unidades").value(0))
                .andExpect(jsonPath("$.porProducto.length()").value(1));
        mockMvc.perform(get(PRODUCCION + "?producto=" + ajeno).cookie(sesion))
                .andExpect(status().isNotFound());
    }

    @Test
    void elInventarioExplicaCadaSaldoConSusMovimientos() throws Exception {
        inventarioDePrueba();

        mockMvc.perform(get(INVENTARIO + "?desde=2024-05-01&hasta=2024-06-30").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodos.length()").value(2))
                // Abril dejo 20 de aceite
                .andExpect(jsonPath("$.periodos[0].saldoInicial").value(20))
                .andExpect(jsonPath("$.periodos[0].entradas").value(60))
                .andExpect(jsonPath("$.periodos[0].vendidasB2c").value(5))
                .andExpect(jsonPath("$.periodos[0].vendidasB2b").value(0))
                .andExpect(jsonPath("$.periodos[0].saldoFinal").value(75))
                .andExpect(jsonPath("$.periodos[1].saldoInicial").value(75))
                .andExpect(jsonPath("$.periodos[1].entradas").value(30))
                .andExpect(jsonPath("$.periodos[1].vendidasB2b").value(4))
                .andExpect(jsonPath("$.periodos[1].merma").value(3))
                .andExpect(jsonPath("$.periodos[1].otrasSalidas").value(2))
                .andExpect(jsonPath("$.periodos[1].saldoFinal").value(96))
                .andExpect(jsonPath("$.totales.saldoInicial").value(20))
                .andExpect(jsonPath("$.totales.entradas").value(90))
                .andExpect(jsonPath("$.totales.vendidasB2c").value(5))
                .andExpect(jsonPath("$.totales.vendidasB2b").value(4))
                .andExpect(jsonPath("$.totales.saldoFinal").value(96))
                // Por producto, por nombre; lo de otro productor no aparece
                .andExpect(jsonPath("$.porProducto.length()").value(2))
                .andExpect(jsonPath("$.porProducto[0].producto").value("RP Aceite"))
                .andExpect(jsonPath("$.porProducto[0].saldoInicial").value(20))
                .andExpect(jsonPath("$.porProducto[0].vendidas").value(5))
                .andExpect(jsonPath("$.porProducto[0].merma").value(3))
                .andExpect(jsonPath("$.porProducto[0].saldoFinal").value(72))
                .andExpect(jsonPath("$.porProducto[1].producto").value("RP Harina"))
                .andExpect(jsonPath("$.porProducto[1].otrasSalidas").value(2))
                .andExpect(jsonPath("$.porProducto[1].saldoFinal").value(24));
    }

    @Test
    void elInventarioSeFiltraPorProducto() throws Exception {
        inventarioDePrueba();

        mockMvc.perform(get(INVENTARIO + "?desde=2024-06-01&hasta=2024-06-30&agrupacion=SEMANA&producto=" + harina)
                        .cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.saldoInicial").value(0))
                .andExpect(jsonPath("$.totales.entradas").value(30))
                .andExpect(jsonPath("$.totales.saldoFinal").value(24))
                .andExpect(jsonPath("$.porProducto.length()").value(1));
    }

    @Test
    void elPdfTraeLasTresSeccionesConLasMismasCifras() throws Exception {
        ventasDePrueba();
        inventarioDePrueba();

        byte[] pdf = mockMvc.perform(get(PDF + "?desde=2024-05-01&hasta=2024-06-30").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Language", "es"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("attachment; filename=\"reporte-productos.pdf\"")))
                .andReturn().getResponse().getContentAsByteArray();

        String texto = texto(pdf);
        assertThat(texto).contains("Reporte de sus productos", "Período: 01/05/2024 al 30/06/2024", "Producto: Todos",
                "Ventas por período", "Mayo 2024", "Junio 2024", "Ventas por producto", "RP Harina", "RP Aceite",
                "Producción por período", "Inventario por período (unidades)", "Inventario por producto (unidades)");
        // Ventas: las cuatro de mayo y junio mas las dos del inventario; sin lo cancelado, lo no pagado ni lo ajeno
        assertThat(texto).contains("Total 23 180.00 140.00 320.00 320.00");
        // Inventario: 20 de abril + 90 entradas - 9 vendidas - 3 de merma - 2 retiradas
        assertThat(texto).contains("Total 20 90 5 4 3 2 96");
        assertThat(texto).doesNotContain("RP Ajeno", "consumidor@ccpip.test", "RPP-1");
    }

    @Test
    void elPdfSaleEnInglesYRespetaLosFiltros() throws Exception {
        ventasDePrueba();
        Cookie sesion = loginAs(PRODUCTOR);

        byte[] pdf = mockMvc.perform(get(PDF + "?desde=2024-05-01&hasta=2024-06-30&producto=" + harina + "&modalidad=MAYORISTA&lang=en")
                        .cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Language", "en"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(texto(pdf)).contains("Product report", "Product: RP Harina", "Purchase mode (sales and consumption): Wholesale (B2B)",
                "Sales by period", "May 2024", "Inventory by period (units)").doesNotContain("RP Aceite");

        mockMvc.perform(get(PDF + "?producto=" + ajeno).cookie(sesion)).andExpect(status().isNotFound());
        mockMvc.perform(get(PDF).cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
    }

    @Test
    void elExcelTraeUnaHojaPorSeccionConLasCifrasComoNumeros() throws Exception {
        ventasDePrueba();
        inventarioDePrueba();

        byte[] xlsx = mockMvc.perform(get(XLSX + "?desde=2024-05-01&hasta=2024-06-30&lang=en").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Language", "en"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("attachment; filename=\"reporte-productos.xlsx\"")))
                .andReturn().getResponse().getContentAsByteArray();

        String libro = entrada(xlsx, "xl/workbook.xml");
        assertThat(libro).contains("name=\"Product report\"", "name=\"Sales by period\"", "name=\"Sales by product\"",
                "name=\"Production by period\"", "name=\"Inventory by period\"", "name=\"Inventory by product\"");
        // Hoja 2, ventas por periodo: el total es un numero (no texto) con formato de importe
        String ventas = entrada(xlsx, "xl/worksheets/sheet2.xml");
        assertThat(ventas).contains("May 2024", "<v>320.00</v>").doesNotContain("RP Ajeno");
        String inventario = entrada(xlsx, "xl/worksheets/sheet6.xml");
        assertThat(inventario).contains("Closing", "<v>96.000</v>");
        assertThat(entrada(xlsx, "xl/worksheets/sheet1.xml")).contains("Producer: ", "Period: 01/05/2024 to 30/06/2024");
    }

    @Test
    void elExcelRespetaLosPermisos() throws Exception {
        mockMvc.perform(get(XLSX + "?producto=" + ajeno).cookie(loginAs(PRODUCTOR))).andExpect(status().isNotFound());
        mockMvc.perform(get(XLSX).cookie(loginAs("admin_acopio@ccpip.test"))).andExpect(status().isForbidden());
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

    /**
     * Mayo: lote de aceite de 50 el 2 y, en el mismo lote, una entrada de produccion de 10 el 20. Junio: lote
     * de harina de 30 el 3. Un lote de otro productor el 5 de mayo no cuenta.
     */
    private long[] produccionDePrueba() {
        long lAceite = lote(aceite, "RP-A1", "50", "2024-05-02 09:00-05");
        entrada(lAceite, "10", "2024-05-20 09:00-05");
        long lHarina = lote(harina, "RP-H1", "30", "2024-06-03 09:00-05");
        lote(ajeno, "RP-X1", "99", "2024-05-05 09:00-05");
        return new long[] {lAceite, lHarina};
    }

    /** Un lote en Lima registrado en {@code cuando}, con la ENTRADA de su registro (sin motivo). */
    private long lote(long productoId, String codigo, String cantidad, String cuando) {
        BigDecimal unidades = new BigDecimal(cantidad);
        long id = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, registrado_en)
                SELECT ?, id, ?, ?, ?, ?::timestamptz FROM ubicacion WHERE nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, productoId, codigo, unidades, unidades, cuando);
        movimiento(id, "ENTRADA", null, cantidad, cuando);
        return id;
    }

    /** Una entrada de produccion en un lote existente: sube su cantidad inicial y su saldo (CCPIP-283). */
    private void entrada(long loteId, String cantidad, String cuando) {
        jdbc.update("UPDATE lote SET cantidad_inicial = cantidad_inicial + ?, cantidad_actual = cantidad_actual + ? WHERE id = ?",
                new BigDecimal(cantidad), new BigDecimal(cantidad), loteId);
        movimiento(loteId, "ENTRADA", "PRODUCCION", cantidad, cuando);
    }

    /** Un movimiento del lote, registrado en {@code cuando}. */
    private void movimiento(long loteId, String tipo, String motivo, String cantidad, String cuando) {
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, motivo, cantidad, usuario_id,
                                                   registrado_en)
                SELECT l.producto_id, l.ubicacion_id, l.id, ?, ?, ?, (SELECT id FROM usuario WHERE correo = 'productor@ccpip.test'),
                       ?::timestamptz
                FROM lote l WHERE l.id = ?""", tipo, motivo, new BigDecimal(cantidad), cuando, loteId);
    }

    /**
     * La produccion de {@link #produccionDePrueba()} mas: un lote de aceite de 20 en abril (saldo inicial);
     * en mayo se venden 5 de aceite a tarifa minorista; en junio, 4 de harina a tarifa mayorista, 3 de aceite
     * van a merma y se retiran 2 de harina.
     */
    private void inventarioDePrueba() {
        lote(aceite, "RP-A0", "20", "2024-04-10 09:00-05");
        long[] lotes = produccionDePrueba();
        venta(lotes[0], linea(pedido("RPI-1", "CONFIRMADO", "2024-05-25 10:00-05"), aceite, "5", "100.00", "MINORISTA"),
                "5", "2024-05-25 10:00-05");
        venta(lotes[1], linea(pedido("RPI-2", "CONFIRMADO", "2024-06-12 10:00-05"), harina, "4", "40.00", "MAYORISTA"),
                "4", "2024-06-12 10:00-05");
        movimiento(lotes[0], "MERMA", null, "3", "2024-06-05 10:00-05");
        movimiento(lotes[1], "SALIDA", "RETIRO_PRODUCTOR", "2", "2024-06-20 10:00-05");
    }

    /** La salida por venta de una linea del pedido, como la registra la compra (CCPIP-62). */
    private void venta(long loteId, long itemId, String cantidad, String cuando) {
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, pedido_id, item_pedido_id,
                                                   registrado_en)
                SELECT l.producto_id, l.ubicacion_id, l.id, 'SALIDA_VENTA', ?, i.pedido_id, i.id, ?::timestamptz
                FROM lote l, item_pedido i WHERE l.id = ? AND i.id = ?""", new BigDecimal(cantidad), cuando, loteId, itemId);
    }

    private static String texto(byte[] pdf) throws Exception {
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(documento);
        }
    }

    /** El contenido de una parte del XLSX, que es un ZIP. */
    private static String entrada(byte[] xlsx, String nombre) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals(nombre)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("El XLSX no trae " + nombre);
    }

    private long pedido(String numero, String estado, String confirmado) {
        return jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 0, ?, ?::timestamptz
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, numero, estado, confirmado);
    }

    private long linea(long pedidoId, long productoId, String cantidad, String subtotal, String tarifa) {
        BigDecimal unidades = new BigDecimal(cantidad);
        BigDecimal monto = new BigDecimal(subtotal);
        return jdbc.queryForObject("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal, tarifa)
                SELECT ?, id, productor_id, ?, ?, ?, ? FROM producto WHERE id = ? RETURNING id""", Long.class,
                pedidoId, unidades, monto.divide(unidades, 2, java.math.RoundingMode.HALF_UP), monto, tarifa, productoId);
    }
}
