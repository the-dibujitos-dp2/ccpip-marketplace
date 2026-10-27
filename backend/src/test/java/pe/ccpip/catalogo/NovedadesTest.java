package pe.ccpip.catalogo;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-338 (RF-228): el visitante consulta los productos publicados recientemente y sus productores, por
 * periodo y sin iniciar sesion. La fecha es la de publicacion; solo lo visible y en venta.
 *
 * <p>Para no depender de cuando se sembro R__02, la prueba deja lo sembrado como publicado hace un año y
 * medio; la transaccion se deshace al terminar.
 */
@Transactional
class NovedadesTest extends IntegrationTest {

    private static final String NOVEDADES = "/api/novedades";
    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    private static final LocalDate EN_UN_ANIO = HOY.plusYears(1);

    private long ayer;
    private long hoy;
    private long hace50Dias;

    @BeforeEach
    void publicaciones() {
        jdbc.update("UPDATE producto SET publicado_en = now() - interval '18 months' WHERE publicado_en IS NOT NULL");
        ayer = publicado("NV Aceite de ayer", 1);
        hoy = publicado("NV Harina de hoy", 0);
        hace50Dias = publicado("NV Cacao de hace 50 dias", 50);
        // Lo agotado y lo que no esta publicado no son novedades
        long agotado = productoConLote("NV Miel agotada", "PUBLICADO", null, null, "productor2@ccpip.test");
        long borrador = productoConLote("NV Borrador", "BORRADOR", "PUNTO_ACOPIO", EN_UN_ANIO);
        jdbc.update("UPDATE producto SET publicado_en = now() WHERE id IN (?, ?)", agotado, borrador);
    }

    @Test
    void losUltimos30DiasVanDelMasRecienteAlMasAntiguoConSuProductor() throws Exception {
        mockMvc.perform(get(NOVEDADES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo").value("ULTIMOS_30_DIAS"))
                .andExpect(jsonPath("$.desde").value(HOY.minusDays(29).toString()))
                .andExpect(jsonPath("$.hasta").value(HOY.toString()))
                .andExpect(jsonPath("$.productos.length()").value(2))
                .andExpect(jsonPath("$.productos[0].id").value(hoy))
                .andExpect(jsonPath("$.productos[1].id").value(ayer))
                .andExpect(jsonPath("$.productos[0].tienda.nombre").exists())
                .andExpect(jsonPath("$.productos[*].nombre").value(not(hasItem("NV Cacao de hace 50 dias"))))
                .andExpect(jsonPath("$.productos[*].nombre").value(not(hasItem("NV Miel agotada"))))
                .andExpect(jsonPath("$.productos[*].nombre").value(not(hasItem("NV Borrador"))));
    }

    @Test
    void losUltimos3MesesIncluyenLoPublicadoHace50DiasYNoRellenanConLoAntiguo() throws Exception {
        mockMvc.perform(get(NOVEDADES + "?periodo=ULTIMOS_3_MESES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(HOY.minusMonths(3).plusDays(1).toString()))
                .andExpect(jsonPath("$.productos.length()").value(3))
                .andExpect(jsonPath("$.productos[2].id").value(hace50Dias))
                // Lo sembrado hace un año y medio no aparece aunque sobre lugar
                .andExpect(jsonPath("$.productos[*].nombre").value(not(hasItem("Cafe molido 250 g"))));
    }

    @Test
    void sinPublicacionesEnElPeriodoLaListaVaVacia() throws Exception {
        jdbc.update("UPDATE producto SET publicado_en = now() - interval '18 months' WHERE nombre LIKE 'NV %'");
        mockMvc.perform(get(NOVEDADES + "?periodo=ESTE_ANIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(HOY.withDayOfYear(1).toString()))
                .andExpect(jsonPath("$.productos.length()").value(0));
    }

    @Test
    void enInglesYUnPeriodoDesconocidoSeRechaza() throws Exception {
        mockMvc.perform(get(NOVEDADES).header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Language", "en"));
        mockMvc.perform(get(NOVEDADES + "?periodo=SIEMPRE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    /** Un producto en venta publicado hace {@code haceDias} dias, del productor sembrado. */
    private long publicado(String nombre, int haceDias) {
        long id = productoConLote(nombre, "PUBLICADO", "PUNTO_ACOPIO", EN_UN_ANIO);
        jdbc.update("UPDATE producto SET publicado_en = now() - make_interval(days => ?) WHERE id = ?", haceDias, id);
        return id;
    }
}
