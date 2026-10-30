package pe.ccpip.reportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-135 (RF-105): el productor consulta el consumo de sus productos por modalidad (B2C y B2B), con importe,
 * volumen y frecuencia de compra, y lo exporta en PDF y Excel. Sin datos de los compradores: solo conteos.
 *
 * <p>Los datos son de octubre y noviembre de 2023, meses que ninguna otra prueba usa, y la transaccion se
 * deshace al terminar.
 */
@Transactional
class ConsumoProductorTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String CONSUMO = "/api/productor/reportes/consumo";
    private static final String RANGO = "?desde=2023-10-01&hasta=2023-11-30";
    private static final String ANA = "consumidor@ccpip.test";
    private static final String BETO = "consumo.beto@ccpip.test";
    private static final String CARLA = "consumo.carla@ccpip.test";

    private long aceite;
    private long harina;
    private long ajeno;

    @BeforeEach
    void datos() {
        aceite = productoConLote("CP Aceite", "PUBLICADO", null, null);
        harina = productoConLote("CP Harina", "PUBLICADO", null, null);
        ajeno = productoConLote("CP Ajeno", "PUBLICADO", null, null, "productor2@ccpip.test");
        comprador(BETO);
        comprador(CARLA);
        comprasDePrueba();
    }

    @Test
    void separaElConsumoPorModalidadConImporteVolumenYFrecuencia() throws Exception {
        mockMvc.perform(get(CONSUMO + RANGO + "&agrupacion=MES").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.pedidos").value(7))
                .andExpect(jsonPath("$.totales.compradores").value(3))
                .andExpect(jsonPath("$.totales.unidadesB2c").value(7))
                .andExpect(jsonPath("$.totales.unidadesB2b").value(45))
                .andExpect(jsonPath("$.totales.importeB2c").value(140.00))
                .andExpect(jsonPath("$.totales.importeB2b").value(450.00))
                .andExpect(jsonPath("$.totales.importe").value(590.00))
                // 590 / 7 = 84.2857 y 7 / 3 = 2.333, con dos decimales
                .andExpect(jsonPath("$.totales.ticketPromedio").value(84.29))
                .andExpect(jsonPath("$.totales.comprasPorComprador").value(2.33))
                .andExpect(jsonPath("$.periodos.length()").value(2))
                // Octubre: Ana dos veces (una mixta) y Beto una
                .andExpect(jsonPath("$.periodos[0].pedidos").value(3))
                .andExpect(jsonPath("$.periodos[0].compradores").value(2))
                .andExpect(jsonPath("$.periodos[0].unidadesB2c").value(6))
                .andExpect(jsonPath("$.periodos[0].unidadesB2b").value(10))
                .andExpect(jsonPath("$.periodos[0].importeB2c").value(120.00))
                .andExpect(jsonPath("$.periodos[0].importeB2b").value(100.00))
                // Noviembre: Ana dos veces y Carla dos
                .andExpect(jsonPath("$.periodos[1].pedidos").value(4))
                .andExpect(jsonPath("$.periodos[1].compradores").value(2))
                .andExpect(jsonPath("$.periodos[1].importeB2b").value(350.00))
                // Beto compro una vez; Carla dos (ambas B2B); Ana cuatro: tres con algo B2C y dos con algo B2B
                .andExpect(jsonPath("$.frecuencia.length()").value(3))
                .andExpect(jsonPath("$.frecuencia[0].tramo").value("UNA"))
                .andExpect(jsonPath("$.frecuencia[0].compradoresB2c").value(1))
                .andExpect(jsonPath("$.frecuencia[0].compradoresB2b").value(0))
                .andExpect(jsonPath("$.frecuencia[0].compradores").value(1))
                .andExpect(jsonPath("$.frecuencia[0].importe").value(60.00))
                .andExpect(jsonPath("$.frecuencia[1].tramo").value("DOS_A_TRES"))
                .andExpect(jsonPath("$.frecuencia[1].compradoresB2c").value(1))
                .andExpect(jsonPath("$.frecuencia[1].compradoresB2b").value(2))
                .andExpect(jsonPath("$.frecuencia[1].compradores").value(1))
                .andExpect(jsonPath("$.frecuencia[1].importe").value(300.00))
                .andExpect(jsonPath("$.frecuencia[2].tramo").value("CUATRO_O_MAS"))
                .andExpect(jsonPath("$.frecuencia[2].compradores").value(1))
                .andExpect(jsonPath("$.frecuencia[2].importe").value(230.00));
    }

    @Test
    void noExponeANingunComprador() throws Exception {
        String cuerpo = mockMvc.perform(get(CONSUMO + RANGO).cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..correo").doesNotExist())
                .andExpect(jsonPath("$..usuarioId").doesNotExist())
                .andExpect(jsonPath("$..numero").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain(ANA, BETO, CARLA, "CPP-", "Beto", "Carla");
    }

    @Test
    void seFiltraPorModalidadYPorProducto() throws Exception {
        Cookie sesion = loginAs(PRODUCTOR);
        // Solo mayorista: la compra mixta de Ana cuenta por su parte B2B
        mockMvc.perform(get(CONSUMO + RANGO + "&modalidad=MAYORISTA").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modalidad").value("MAYORISTA"))
                .andExpect(jsonPath("$.totales.pedidos").value(4))
                .andExpect(jsonPath("$.totales.compradores").value(2))
                .andExpect(jsonPath("$.totales.importeB2c").value(0))
                .andExpect(jsonPath("$.totales.importe").value(450.00))
                .andExpect(jsonPath("$.totales.ticketPromedio").value(112.50))
                .andExpect(jsonPath("$.totales.comprasPorComprador").value(2.00))
                .andExpect(jsonPath("$.frecuencia[0].compradores").value(0))
                .andExpect(jsonPath("$.frecuencia[1].compradores").value(2));

        mockMvc.perform(get(CONSUMO + RANGO + "&producto=" + aceite).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.producto").value(aceite))
                .andExpect(jsonPath("$.totales.pedidos").value(4))
                .andExpect(jsonPath("$.totales.compradores").value(2))
                .andExpect(jsonPath("$.totales.importe").value(140.00));
    }

    @Test
    void sinComprasTodoQuedaEnCero() throws Exception {
        mockMvc.perform(get(CONSUMO + "?desde=2023-12-01&hasta=2023-12-31").cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.pedidos").value(0))
                .andExpect(jsonPath("$.totales.compradores").value(0))
                .andExpect(jsonPath("$.totales.ticketPromedio").value(0))
                .andExpect(jsonPath("$.totales.comprasPorComprador").value(0))
                .andExpect(jsonPath("$.periodos.length()").value(1))
                .andExpect(jsonPath("$.frecuencia.length()").value(3))
                .andExpect(jsonPath("$.frecuencia[2].compradores").value(0));
    }

    @Test
    void soloElProductorYConSusProductos() throws Exception {
        mockMvc.perform(get(CONSUMO + "?producto=" + ajeno).cookie(loginAs(PRODUCTOR)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        mockMvc.perform(get(CONSUMO).cookie(loginAs(ANA))).andExpect(status().isForbidden());
        mockMvc.perform(get(CONSUMO)).andExpect(status().isUnauthorized());
        // Otro productor no ve las compras de estos productos
        mockMvc.perform(get(CONSUMO + RANGO).cookie(loginAs("productor2@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.pedidos").value(1))
                .andExpect(jsonPath("$.totales.importe").value(500.00));
    }

    @Test
    void elPdfYElExcelTraenElConsumoYLaFrecuencia() throws Exception {
        Cookie sesion = loginAs(PRODUCTOR);
        byte[] pdf = mockMvc.perform(get("/api/productor/reportes/pdf" + RANGO).cookie(sesion))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String texto;
        try (PDDocument documento = Loader.loadPDF(pdf)) {
            texto = new PDFTextStripper().getText(documento);
        }
        assertThat(texto).contains("Modalidad (ventas y consumo)", "Consumo por período", "Frecuencia de compra",
                "1 compra", "2 a 3 compras", "4 o más compras", "Promedio por comprador");
        assertThat(texto).contains("Total 7 3 7 45 140.00 450.00");
        assertThat(texto).doesNotContain(ANA, BETO, CARLA, "CPP-");

        byte[] xlsx = mockMvc.perform(get("/api/productor/reportes/xlsx" + RANGO + "&lang=en").cookie(sesion))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(entrada(xlsx, "xl/workbook.xml")).contains("name=\"Consumption by period\"", "name=\"Purchase frequency\"");
        // Las hojas anteriores no se mueven: el consumo va despues del inventario
        assertThat(entrada(xlsx, "xl/worksheets/sheet8.xml")).contains("Orders", "Buyers", "<v>450.00</v>");
        assertThat(entrada(xlsx, "xl/worksheets/sheet9.xml")).contains("4 or more purchases", "<v>230.00</v>");
    }

    /**
     * Ana: el 3/10 aceite 2 und B2C (S/ 40); el 10/10 una compra mixta, aceite 1 B2C (S/ 20) y harina 10 B2B
     * (S/ 100); el 5/11 aceite 1 B2C (S/ 20); el 20/11 harina 5 B2B (S/ 50). Beto: el 15/10 aceite 3 B2C (S/ 60).
     * Carla: el 8/11 harina 20 B2B (S/ 200) y el 9/11 harina 10 B2B (S/ 100). No cuentan lo cancelado, lo no
     * pagado ni lo de otro productor.
     */
    private void comprasDePrueba() {
        linea(pedido("CPP-1", ANA, "CONFIRMADO", "2023-10-03 10:00-05"), aceite, "2", "40.00", "MINORISTA");
        long mixta = pedido("CPP-2", ANA, "ENTREGADO", "2023-10-10 10:00-05");
        linea(mixta, aceite, "1", "20.00", "MINORISTA");
        linea(mixta, harina, "10", "100.00", "MAYORISTA");
        linea(pedido("CPP-3", ANA, "CONFIRMADO", "2023-11-05 10:00-05"), aceite, "1", "20.00", "MINORISTA");
        linea(pedido("CPP-4", ANA, "CONFIRMADO", "2023-11-20 10:00-05"), harina, "5", "50.00", "MAYORISTA");
        linea(pedido("CPP-5", BETO, "CONFIRMADO", "2023-10-15 10:00-05"), aceite, "3", "60.00", "MINORISTA");
        linea(pedido("CPP-6", CARLA, "CONFIRMADO", "2023-11-08 10:00-05"), harina, "20", "200.00", "MAYORISTA");
        linea(pedido("CPP-7", CARLA, "CONFIRMADO", "2023-11-09 10:00-05"), harina, "10", "100.00", "MAYORISTA");
        linea(pedido("CPP-8", CARLA, "CANCELADO", "2023-10-20 10:00-05"), aceite, "9", "999.00", "MINORISTA");
        linea(pedido("CPP-9", BETO, "PENDIENTE_PAGO", null), harina, "9", "888.00", "MAYORISTA");
        linea(pedido("CPP-10", ANA, "CONFIRMADO", "2023-10-12 10:00-05"), ajeno, "5", "500.00", "MINORISTA");
    }

    private void comprador(String correo) {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, ?, 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""", correo, correo.equals(BETO) ? "Beto" : "Carla");
    }

    private long pedido(String numero, String correo, String estado, String confirmado) {
        return jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 0, ?, ?::timestamptz
                FROM usuario u, ubicacion l WHERE u.correo = ? AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, numero, estado, confirmado, correo);
    }

    private void linea(long pedidoId, long productoId, String cantidad, String subtotal, String tarifa) {
        BigDecimal unidades = new BigDecimal(cantidad);
        BigDecimal monto = new BigDecimal(subtotal);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal, tarifa)
                SELECT ?, id, productor_id, ?, ?, ?, ? FROM producto WHERE id = ?""",
                pedidoId, unidades, monto.divide(unidades, 2, RoundingMode.HALF_UP), monto, tarifa, productoId);
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
}
