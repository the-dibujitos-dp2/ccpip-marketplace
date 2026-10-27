package pe.ccpip.catalogo;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-55 (RF-35): en la ficha publica, los productos relacionados por materia prima. En R__02 el cacao
 * en grano (dos ofertas) es materia prima y la pasta de cacao, producto terminado de la misma linea.
 */
@Transactional
class MateriaPrimaTest extends IntegrationTest {

    private static final String FICHA = "/api/productos/{id}";

    @Test
    void unProductoTerminadoMuestraLasMateriasPrimasDeSuLinea() throws Exception {
        mockMvc.perform(get(FICHA, idDe("Pasta de cacao 500 g")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materiaPrima").value(false))
                .andExpect(jsonPath("$.porMateriaPrima[*].nombre")
                        .value(containsInAnyOrder("Cacao en grano fermentado 1 kg", "Cacao fino de aroma 1 kg")))
                .andExpect(jsonPath("$.porMateriaPrima[0].tienda.nombre").exists())
                // "Tambien de" no repite lo que ya va en la seccion de materia prima
                .andExpect(jsonPath("$.relacionados[*].nombre").value(not(hasItem("Cacao en grano fermentado 1 kg"))));
    }

    @Test
    void unaMateriaPrimaMuestraLoTerminadoDeSuLinea() throws Exception {
        mockMvc.perform(get(FICHA, idDe("Cacao en grano fermentado 1 kg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materiaPrima").value(true))
                .andExpect(jsonPath("$.porMateriaPrima[*].nombre").value(containsInAnyOrder("Pasta de cacao 500 g")))
                // La otra oferta de cacao en grano es de su misma clase: va en "Tambien de"
                .andExpect(jsonPath("$.relacionados[*].nombre").value(hasItem("Cacao fino de aroma 1 kg")));
    }

    @Test
    void soloProductosRealesYVisiblesDeUnaLineaEnComun() throws Exception {
        long borrador = productoConLote("MP Cacao en borrador", "BORRADOR", null, null);
        long otraLinea = productoConLote("MP Camu camu fresco", "PUBLICADO", null, null);
        jdbc.update("UPDATE producto SET es_materia_prima = true WHERE id IN (?, ?)", borrador, otraLinea);
        jdbc.update("""
                UPDATE producto_linea SET linea_id = (SELECT id FROM linea_producto WHERE codigo = 'CACAO')
                WHERE producto_id = ?""", borrador);

        mockMvc.perform(get(FICHA, idDe("Pasta de cacao 500 g")))
                .andExpect(jsonPath("$.porMateriaPrima[*].nombre").value(not(hasItem("MP Cacao en borrador"))))
                .andExpect(jsonPath("$.porMateriaPrima[*].nombre").value(not(hasItem("MP Camu camu fresco"))));
        // Un terminado sin materias primas en sus lineas no inventa relaciones
        mockMvc.perform(get(FICHA, idDe("Cafe molido 250 g")))
                .andExpect(jsonPath("$.porMateriaPrima.length()").value(0));
    }

    @Test
    void enInglesLaSeccionLlegaTraducidaComoElRestoDeLaFicha() throws Exception {
        mockMvc.perform(get(FICHA, idDe("Pasta de cacao 500 g")).header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Language", "en"))
                .andExpect(jsonPath("$.porMateriaPrima.length()").value(2));
    }

    private long idDe(String nombre) {
        return jdbc.queryForObject("SELECT id FROM producto WHERE nombre = ?", Long.class, nombre);
    }
}
