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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;

import pe.ccpip.support.IntegrationTest;

/**
 * Reportes de gestion del superadministrador (SA-05, SA-10). CCPIP-122 (RF-86): productores con mayores
 * ventas; CCPIP-355 (RF-245) y CCPIP-356 (RF-246): ventas y denuncias de los puntos de acopio; CCPIP-123
 * (RF-87) y CCPIP-352 (RF-242): calificaciones de productos y de productores; CCPIP-80 (RF-66): origen y
 * destino de las ventas; indicadores agregados de la plataforma (CCPIP-348 ventas,
 * CCPIP-349 productores, CCPIP-350 productos, CCPIP-94 actividad). Cada pestaña se exporta en PDF y Excel.
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
    void losIndicadoresDeVentasSeComparanConElPeriodoAnterior() throws Exception {
        ventasDePrueba();

        mockMvc.perform(get("/api/admin/reportes/indicadores" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agrupacion").value("MES"))
                .andExpect(jsonPath("$.anteriorDesde").value("2023-08-02"))
                .andExpect(jsonPath("$.anteriorHasta").value("2023-08-31"))
                // Septiembre: S/ 180 en 3 pedidos; agosto: S/ 25 en 1
                .andExpect(jsonPath("$.ventas.total.valor").value(180.00))
                .andExpect(jsonPath("$.ventas.total.anterior").value(25.00))
                .andExpect(jsonPath("$.ventas.total.variacion").value(620.0))
                .andExpect(jsonPath("$.ventas.pedidos.valor").value(3))
                .andExpect(jsonPath("$.ventas.pedidos.variacion").value(200.0))
                .andExpect(jsonPath("$.ventas.ticketPromedio.valor").value(60.00))
                .andExpect(jsonPath("$.ventas.ticketPromedio.anterior").value(25.00))
                .andExpect(jsonPath("$.ventas.desglose.b2c").value(80.00))
                .andExpect(jsonPath("$.ventas.desglose.b2b").value(100.00))
                .andExpect(jsonPath("$.periodos.length()").value(1))
                .andExpect(jsonPath("$.periodos[0].ventas").value(180.00))
                .andExpect(jsonPath("$.periodos[0].pedidos").value(3))
                .andExpect(jsonPath("$.periodos[0].productoresConVentas").value(2));
    }

    @Test
    void losIndicadoresDeProductoresCuentanLosQueVendieronYLosNuevos() throws Exception {
        ventasDePrueba();
        long usuario = jdbc.queryForObject("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                VALUES ('rg-nuevo@ccpip.test', 'x', 'Productor nuevo', 2, true) RETURNING id""", Long.class);
        jdbc.update("""
                INSERT INTO productor (usuario_id, comunidad, region, creado_en)
                VALUES (?, 'Comunidad nueva', 'Ucayali', '2023-09-10 10:00-05'::timestamptz)""", usuario);

        mockMvc.perform(get("/api/admin/reportes/indicadores" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productores.activos").isNumber())
                // Vendieron dos en septiembre y uno en agosto
                .andExpect(jsonPath("$.productores.conVentas.valor").value(2))
                .andExpect(jsonPath("$.productores.conVentas.anterior").value(1))
                .andExpect(jsonPath("$.productores.conVentas.variacion").value(100.0))
                .andExpect(jsonPath("$.productores.nuevos.valor").value(1))
                .andExpect(jsonPath("$.productores.nuevos.anterior").value(0))
                .andExpect(jsonPath("$.productores.nuevos.variacion").doesNotExist());
    }

    @Test
    void losIndicadoresSeAgrupanPorSemanaConPeriodosSinVentas() throws Exception {
        ventasDePrueba();

        // Del lunes 4 al domingo 24 de septiembre: tres semanas; el pedido del 5 cae en la primera
        mockMvc.perform(get("/api/admin/reportes/indicadores?desde=2023-09-04&hasta=2023-09-24&agrupacion=SEMANA")
                        .cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodos.length()").value(3))
                .andExpect(jsonPath("$.periodos[0].inicio").value("2023-09-04"))
                .andExpect(jsonPath("$.periodos[0].ventas").value(30.00))
                .andExpect(jsonPath("$.periodos[1].ventas").value(100.00))
                .andExpect(jsonPath("$.periodos[2].ventas").value(50.00))
                // El anterior va del 14 de agosto al 3 de septiembre: incluye el pedido del 15 de agosto
                .andExpect(jsonPath("$.anteriorDesde").value("2023-08-14"))
                .andExpect(jsonPath("$.ventas.total.anterior").value(25.00));
        // Sin ventas en el periodo anterior, no hay variacion
        mockMvc.perform(get("/api/admin/reportes/indicadores?desde=2023-09-01&hasta=2023-09-07").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventas.total.valor").value(30.00))
                .andExpect(jsonPath("$.ventas.total.anterior").value(0))
                .andExpect(jsonPath("$.ventas.total.variacion").doesNotExist());
    }

    @Test
    void losIndicadoresDeProductosCuentanLosPublicadosYLosQueSeVendieron() throws Exception {
        ventasDePrueba();
        jdbc.update("UPDATE producto SET publicado_en = '2023-09-08 10:00-05'::timestamptz WHERE id = ?", aceite);

        mockMvc.perform(get("/api/admin/reportes/indicadores" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos.publicados").isNumber())
                .andExpect(jsonPath("$.productos.nuevos.valor").value(1))
                .andExpect(jsonPath("$.productos.nuevos.anterior").value(0))
                // Se vendieron el aceite y la harina en septiembre; solo la harina en agosto
                .andExpect(jsonPath("$.productos.conVentas.valor").value(2))
                .andExpect(jsonPath("$.productos.conVentas.anterior").value(1))
                .andExpect(jsonPath("$.productos.porLinea[?(@.codigo == 'SUPERFRUTOS')].publicados").isNotEmpty())
                .andExpect(jsonPath("$.productos.porLinea[?(@.codigo == 'TEXTIL')]").exists())
                .andExpect(jsonPath("$.periodos[0].productosNuevos").value(1));
    }

    @Test
    void losIndicadoresDeActividadCuentanCompradoresRegistrosCalificacionesYDenuncias() throws Exception {
        ventasDePrueba();
        calificacionesDePrueba();
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado, creado_en)
                VALUES ('rg-comprador@ccpip.test', 'x', 'Comprador nuevo', 1, true, '2023-09-02 10:00-05'::timestamptz)""");

        mockMvc.perform(get("/api/admin/reportes/indicadores" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                // Compro el mismo consumidor en septiembre y en agosto: se cuenta una vez y sin decir quien
                .andExpect(jsonPath("$.actividad.compradores.valor").value(1))
                .andExpect(jsonPath("$.actividad.compradores.variacion").value(0.0))
                .andExpect(jsonPath("$.actividad.registros.valor").value(1))
                .andExpect(jsonPath("$.actividad.calificaciones.valor").value(3))
                .andExpect(jsonPath("$.actividad.calificaciones.anterior").value(2))
                .andExpect(jsonPath("$.actividad.calificaciones.variacion").value(50.0))
                .andExpect(jsonPath("$.actividad.denuncias.valor").value(1))
                .andExpect(jsonPath("$.periodos[0].compradores").value(1))
                .andExpect(jsonPath("$..correo").doesNotExist());
    }

    @Test
    void cadaPestanaSeExportaEnPdfConSusFiltros() throws Exception {
        ventasDePrueba();
        calificacionesDePrueba();
        Cookie sesion = loginAs(SUPER);

        byte[] pdf = mockMvc.perform(get("/api/admin/reportes/calificaciones/pdf" + SEPTIEMBRE + "&linea=SUPERFRUTOS").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Language", "es"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("attachment; filename=\"reporte-calificaciones.pdf\"")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(texto(pdf)).contains("Calificaciones", "Período: 01/09/2023 al 30/09/2023", "Línea: Superfrutos",
                "Promedio: 4", "RG Aceite", "4.5 2 1 Sube", "RG Harina", "El promedio se muestra desde 2 calificaciones")
                .doesNotContain("Prueba de reporte");

        byte[] productores = mockMvc.perform(get("/api/admin/reportes/productores/pdf" + SEPTIEMBRE + "&lang=en").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Language", "en"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(texto(productores)).contains("Top-selling producers", "Platform sales: S/ 180.00", "72.2");

        for (String reporte : new String[] {"puntos", "origen-destino", "indicadores"}) {
            assertThat(texto(mockMvc.perform(get("/api/admin/reportes/" + reporte + "/pdf" + SEPTIEMBRE).cookie(sesion))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()))
                    .contains("Período: 01/09/2023 al 30/09/2023");
        }
    }

    @Test
    void cadaPestanaSeExportaEnExcelConLasCifrasComoNumeros() throws Exception {
        ventasDePrueba();

        byte[] xlsx = mockMvc.perform(get("/api/admin/reportes/indicadores/xlsx" + SEPTIEMBRE).cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        Matchers.containsString("attachment; filename=\"reporte-indicadores.xlsx\"")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(entrada(xlsx, "xl/workbook.xml")).contains("name=\"Indicadores de la plataforma\"", "name=\"Ventas\"",
                "name=\"Actividad\"", "name=\"Por período\"");
        // Hoja 2, ventas: lo vendido es un numero, no texto
        assertThat(entrada(xlsx, "xl/worksheets/sheet2.xml")).contains("Ticket promedio", "<v>180.00</v>", "<v>60.00</v>");
    }

    @Test
    void unReporteOFormatoQueNoExisteNoSeExporta() throws Exception {
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(get("/api/admin/reportes/otro/pdf").cookie(sesion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORTE_NO_ENCONTRADO"));
        mockMvc.perform(get("/api/admin/reportes/productores/csv").cookie(sesion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORMATO_NO_ENCONTRADO"));
        mockMvc.perform(get("/api/admin/reportes/productores/pdf").cookie(loginAs("sub_admin@ccpip.test")))
                .andExpect(status().isForbidden());
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
        mockMvc.perform(get("/api/admin/reportes/indicadores").cookie(loginAs(correo))).andExpect(status().isForbidden());
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
}
