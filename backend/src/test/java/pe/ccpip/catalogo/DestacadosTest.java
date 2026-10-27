package pe.ccpip.catalogo;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-57 (RF-37): los productos destacados del inicio y sus productores, segun la regla aprobada el 26/10:
 * solo lo que cada productor destaca en su tienda, en venta, y uno por productor (el publicado mas
 * recientemente). En R__02 cada una de las tres tiendas destaca dos productos.
 */
@Transactional
class DestacadosTest extends IntegrationTest {

    private static final String INICIO = "/api/inicio";
    private static final LocalDate EN_UN_ANIO = LocalDate.now(ZoneId.of("America/Lima")).plusYears(1);

    @Test
    void unoPorProductorConSuTiendaYSoloLoQueElProductorDestaco() throws Exception {
        mockMvc.perform(get(INICIO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destacados.length()").value(3))
                .andExpect(jsonPath("$.destacados[*].tienda.nombre")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("Bordadoras de San Francisco", "Cacaoteros de Umukai", "Asháninka de Pampa Michi")))
                // Nadie destaco la guayusa ni el cafe molido: no se rellena con ellos
                .andExpect(jsonPath("$.destacados[*].nombre").value(not(hasItem("Infusion de guayusa 80 g"))))
                .andExpect(jsonPath("$.destacados[*].nombre").value(not(hasItem("Cafe molido 250 g"))));
    }

    @Test
    void deCadaProductorVaSuDestacadoMasRecienteQueEsteEnVenta() throws Exception {
        long nuevo = productoConLote("D Miel destacada", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_ANIO);
        long sinStock = productoConLote("D Harina agotada", "PUBLICADO", null, null, "productor2@ccpip.test");
        long sinDestacar = productoConLote("D Mermelada reciente", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_ANIO, "productor3@ccpip.test");
        jdbc.update("UPDATE producto SET destacado_tienda = true WHERE id IN (?, ?)", nuevo, sinStock);

        mockMvc.perform(get(INICIO))
                .andExpect(jsonPath("$.destacados.length()").value(3))
                // El mas reciente va primero y reemplaza al otro destacado de su productor
                .andExpect(jsonPath("$.destacados[0].id").value(nuevo))
                .andExpect(jsonPath("$.destacados[*].nombre").value(not(hasItem("Aceite de ungurahui 250 ml"))))
                .andExpect(jsonPath("$.destacados[*].nombre").value(not(hasItem("Textil bordado shipibo"))))
                // Lo agotado no se destaca, aunque el productor lo haya marcado; sigue el anterior de su tienda
                .andExpect(jsonPath("$.destacados[*].id").value(not(hasItem((int) sinStock))))
                .andExpect(jsonPath("$.destacados[*].tienda.nombre").value(hasItem("Cacaoteros de Umukai")))
                // Lo reciente que el productor no destaco no entra
                .andExpect(jsonPath("$.destacados[*].id").value(not(hasItem((int) sinDestacar))));
    }

    @Test
    void unaTiendaSinDestacadosNoApareceYSinNingunoLaListaVaVacia() throws Exception {
        jdbc.update("""
                UPDATE producto SET destacado_tienda = false
                WHERE productor_id = (SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id
                                      WHERE u.correo = 'productor3@ccpip.test')""");
        mockMvc.perform(get(INICIO))
                .andExpect(jsonPath("$.destacados.length()").value(2))
                .andExpect(jsonPath("$.destacados[*].tienda.nombre").value(not(hasItem("Asháninka de Pampa Michi"))));

        jdbc.update("UPDATE producto SET destacado_tienda = false");
        mockMvc.perform(get(INICIO)).andExpect(jsonPath("$.destacados.length()").value(0));
    }
}
