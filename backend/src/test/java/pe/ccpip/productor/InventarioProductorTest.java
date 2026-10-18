package pe.ccpip.productor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.alerta.PreferenciasAlerta;
import pe.ccpip.inventario.DescuentoExistenciasService;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-136 (RF-110) y CCPIP-162 (RF-131), Figma PW-16: el productor consulta su almacen virtual
 * unico, por producto, ubicacion, lote y estado. Solo sus productos (RNF-05) y todo derivado de los
 * lotes y sus movimientos (RNF-15).
 */
@Transactional
class InventarioProductorTest extends IntegrationTest {

    static final String LIMA = "Centro Nacional Lima";
    static final String UCAYALI = "Centro Regional Ucayali";
    static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));

    @Autowired
    private DescuentoExistenciasService descuento;

    @Autowired
    private PreferenciasAlerta preferencias;

    private long almacenNuevo;

    @BeforeEach
    void preparar() {
        almacenNuevo = almacenDe("productor_nuevo@ccpip.test");
    }

    @Test
    void desglosaPorUbicacionLoteYEstado() throws Exception {
        // productor_nuevo no tiene existencias sembradas: los totales son exactos
        long aceite = producto("Aceite de prueba 136", "productor_nuevo@ccpip.test");
        lote(aceite, "ALM-1", almacenNuevo, "40", HOY.plusDays(60));
        lote(aceite, "ALM-2", almacenNuevo, "10", HOY.plusDays(5));
        lote(aceite, "LIM-1", punto(LIMA), "7", HOY.minusDays(1));
        lote(aceite, "UCA-1", punto(UCAYALI), "20.5", null);
        lote(aceite, "LIM-0", punto(LIMA), "0", HOY.plusDays(90));

        inventario(loginAs("productor_nuevo@ccpip.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diasAviso").value(preferencias.diasVencimientoProvisional()))
                .andExpect(jsonPath("$.productos.length()").value(1))
                .andExpect(jsonPath("$.productos[0].nombre").value("Aceite de prueba 136"))
                // El vencido no suma a lo disponible
                .andExpect(jsonPath("$.productos[0].total").value(70.5))
                .andExpect(jsonPath("$.productos[0].porUbicacion.length()").value(2))
                .andExpect(jsonPath("$.productos[0].porUbicacion[0].tipo").value("ALMACEN_PRODUCTOR"))
                .andExpect(jsonPath("$.productos[0].porUbicacion[0].cantidad").value(50.0))
                .andExpect(jsonPath("$.productos[0].porUbicacion[1].ubicacion").value(UCAYALI))
                .andExpect(jsonPath("$.productos[0].porUbicacion[1].cantidad").value(20.5))
                // Un lote sin saldo ya no es inventario
                .andExpect(jsonPath("$.productos[0].lotes.length()").value(4))
                .andExpect(jsonPath("$.productos[0].lotes[*].codigo", not(hasItem("LIM-0"))))
                .andExpect(jsonPath("$.productos[0].lotes[0].codigo").value("ALM-2"))
                .andExpect(jsonPath("$.productos[0].lotes[0].estado").value("POR_VENCER"))
                .andExpect(jsonPath("$.productos[0].lotes[1].codigo").value("ALM-1"))
                .andExpect(jsonPath("$.productos[0].lotes[1].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.productos[0].lotes[1].cantidad").value(40.0))
                .andExpect(jsonPath("$.productos[0].lotes[?(@.codigo == 'LIM-1')].estado").value("VENCIDO"))
                .andExpect(jsonPath("$.productos[0].lotes[?(@.codigo == 'LIM-1')].ubicacion").value(LIMA))
                .andExpect(jsonPath("$.productos[0].lotes[?(@.codigo == 'UCA-1')].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.productos[0].lotes[?(@.codigo == 'UCA-1')].fechaVencimiento").doesNotExist())
                // Indicadores de PW-16
                .andExpect(jsonPath("$.resumen.enAlmacen").value(50.0))
                .andExpect(jsonPath("$.resumen.productosEnAlmacen").value(1))
                .andExpect(jsonPath("$.resumen.enPuntosAcopio").value(20.5))
                .andExpect(jsonPath("$.resumen.porPunto.length()").value(1))
                .andExpect(jsonPath("$.resumen.porPunto[0].ubicacion").value(UCAYALI))
                .andExpect(jsonPath("$.resumen.lotesPorVencer").value(1))
                .andExpect(jsonPath("$.resumen.lotesVencidos").value(1))
                // Su almacen primero, luego los puntos donde tiene lotes; no todos los de la camara
                .andExpect(jsonPath("$.ubicaciones.length()").value(3))
                .andExpect(jsonPath("$.ubicaciones[0].id").value(almacenNuevo))
                .andExpect(jsonPath("$.ubicaciones[1].nombre").value(LIMA))
                .andExpect(jsonPath("$.ubicaciones[2].nombre").value(UCAYALI));
    }

    @Test
    void elAvisoDeVencimientoCuentaDesdeHoyHastaElUltimoDia() throws Exception {
        long producto = producto("Camu camu de prueba 162", "productor_nuevo@ccpip.test");
        lote(producto, "HOY", almacenNuevo, "1", HOY);
        lote(producto, "LIMITE", almacenNuevo, "1", HOY.plusDays(preferencias.diasVencimientoProvisional()));
        lote(producto, "DESPUES", almacenNuevo, "1", HOY.plusDays(preferencias.diasVencimientoProvisional() + 1L));
        lote(producto, "AYER", almacenNuevo, "1", HOY.minusDays(1));

        inventario(loginAs("productor_nuevo@ccpip.test"))
                .andExpect(jsonPath("$.productos[0].lotes[?(@.codigo == 'HOY')].estado").value("POR_VENCER"))
                .andExpect(jsonPath("$.productos[0].lotes[?(@.codigo == 'LIMITE')].estado").value("POR_VENCER"))
                .andExpect(jsonPath("$.productos[0].lotes[?(@.codigo == 'DESPUES')].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.productos[0].lotes[?(@.codigo == 'AYER')].estado").value("VENCIDO"))
                .andExpect(jsonPath("$.productos[0].total").value(3.0))
                .andExpect(jsonPath("$.resumen.lotesPorVencer").value(2))
                .andExpect(jsonPath("$.resumen.lotesVencidos").value(1));
    }

    @Test
    void sinExistenciasMuestraSoloSuAlmacen() throws Exception {
        inventario(loginAs("productor_nuevo@ccpip.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos.length()").value(0))
                .andExpect(jsonPath("$.ubicaciones.length()").value(1))
                .andExpect(jsonPath("$.ubicaciones[0].tipo").value("ALMACEN_PRODUCTOR"))
                .andExpect(jsonPath("$.resumen.enAlmacen").value(0))
                .andExpect(jsonPath("$.resumen.enPuntosAcopio").value(0));
    }

    @Test
    void cadaProductorVeSoloSuInventario() throws Exception {
        long propio = producto("Sacha inchi de Nii Joi", "productor@ccpip.test");
        lote(propio, "NJ-136", almacenDe("productor@ccpip.test"), "12", HOY.plusDays(90));
        long ajeno = producto("Cacao de Yarina 136", "productor2@ccpip.test");
        lote(ajeno, "YA-136", almacenDe("productor2@ccpip.test"), "8", HOY.plusDays(90));

        inventario(loginAs("productor@ccpip.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos[*].nombre", hasItem("Sacha inchi de Nii Joi")))
                .andExpect(jsonPath("$.productos[?(@.nombre == 'Sacha inchi de Nii Joi')].lotes[0].codigo").value("NJ-136"))
                .andExpect(content().string(not(containsString("Cacao de Yarina 136"))))
                .andExpect(content().string(not(containsString("YA-136"))))
                .andExpect(jsonPath("$.ubicaciones[0].id").value(almacenDe("productor@ccpip.test")));

        inventario(loginAs("productor2@ccpip.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productos[?(@.nombre == 'Cacao de Yarina 136')].total").value(8.0))
                .andExpect(content().string(not(containsString("Sacha inchi de Nii Joi"))))
                .andExpect(content().string(not(containsString("NJ-136"))))
                .andExpect(jsonPath("$.ubicaciones[*].id", not(hasItem((int) almacenDe("productor@ccpip.test")))));
    }

    @Test
    void unaVentaConfirmadaBajaElLoteDelPunto() throws Exception {
        // El saldo sale del lote que movio el descuento de la compra (CCPIP-158), no de un total aparte
        long producto = productoConLote("Aguaje de prueba 136", "PUBLICADO", "PUNTO_ACOPIO", HOY.plusDays(90));
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, 5)", producto, punto(LIMA));
        Cookie productor = loginAs("productor@ccpip.test");
        inventario(productor)
                .andExpect(jsonPath("$.productos[?(@.nombre == 'Aguaje de prueba 136')].total").value(5.0));

        long pedido = pedidoConfirmado("MC-T0136", LIMA, producto, 2);
        long consumidor = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = 'consumidor@ccpip.test'", Long.class);
        descuento.descontarPorPedido(pedido, consumidor);

        inventario(productor)
                .andExpect(jsonPath("$.productos[?(@.nombre == 'Aguaje de prueba 136')].total").value(3.0))
                .andExpect(jsonPath("$.productos[?(@.nombre == 'Aguaje de prueba 136')].lotes[0].cantidad").value(3.0));
    }

    @Test
    void soloElProductorConsulta() throws Exception {
        inventario(loginAs("consumidor@ccpip.test")).andExpect(status().isForbidden());
        inventario(loginAs("admin_acopio@ccpip.test")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/productor/inventario")).andExpect(status().isUnauthorized());
    }

    private ResultActions inventario(Cookie quien) throws Exception {
        return mockMvc.perform(get("/api/productor/inventario").cookie(quien));
    }

    private long producto(String nombre, String correo) {
        return productoConLote(nombre, "PUBLICADO", null, null, correo);
    }

    private void lote(long producto, String codigo, long ubicacion, String cantidad, LocalDate vence) {
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                  fecha_produccion, fecha_vencimiento)
                VALUES (?, ?, ?, greatest(?, 1), ?, ?, ?)""",
                producto, ubicacion, codigo, new BigDecimal(cantidad), new BigDecimal(cantidad), HOY.minusDays(30), vence);
    }

    private long punto(String nombre) {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ? AND tipo = 'PUNTO_ACOPIO'", Long.class, nombre);
    }

    private long almacenDe(String correo) {
        return jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN productor p ON p.id = u.productor_id JOIN usuario x ON x.id = p.usuario_id
                WHERE x.correo = ? AND u.tipo = 'ALMACEN_PRODUCTOR'""", Long.class, correo);
    }
}
