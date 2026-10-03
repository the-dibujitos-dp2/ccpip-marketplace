package pe.ccpip.catalogo;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * Catalogo publico contra las cuentas y productos sembrados en R__02 (10 productos con stock).
 * Cada prueba corre en una transaccion que se revierte: los productos que inserta no quedan.
 */
@Transactional
class CatalogoControllerTest extends IntegrationTest {

    // --- CCPIP-52: buscar productos por nombre ---

    @Test
    void sinTerminoListaElCatalogoDisponibleSinSesion() throws Exception {
        mockMvc.perform(get("/api/productos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].productor.comunidad").exists());
    }

    @Test
    void buscaPorNombreSinDistinguirMayusculasNiTildes() throws Exception {
        // En la base esta como "Cafe tostado..." y "Cafe molido..."
        mockMvc.perform(get("/api/productos").param("q", "CAFÉ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].nombre", everyItem(Matchers.startsWith("Cafe"))));
    }

    @Test
    void tambienBuscaEnLaDescripcion() throws Exception {
        // "prensado en frio" solo aparece en la descripcion de los aceites
        mockMvc.perform(get("/api/productos").param("q", "frío"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].linea.codigo", everyItem(Matchers.is("ACEITES"))));
    }

    @Test
    void losResultadosSePaginan() throws Exception {
        mockMvc.perform(get("/api/productos").param("tamano", "4").param("pagina", "2"))
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.totalPaginas").value(3))
                .andExpect(jsonPath("$.pagina").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void unaBusquedaSinResultadosDevuelveTotalCero() throws Exception {
        mockMvc.perform(get("/api/productos").param("q", "televisor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void losComodinesEscritosPorElUsuarioSeBuscanLiteral() throws Exception {
        mockMvc.perform(get("/api/productos").param("q", "%")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void soloDevuelveProductosPublicadosYConStock() throws Exception {
        long borrador = insertarProducto("Miel de abeja nativa borrador", "BORRADOR", true);
        long sinStock = insertarProducto("Miel de abeja nativa sin stock", "PUBLICADO", false);
        long visible = insertarProducto("Miel de abeja nativa visible", "PUBLICADO", true);

        mockMvc.perform(get("/api/productos").param("q", "miel"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[*].id", hasItem((int) visible)))
                .andExpect(jsonPath("$.items[*].id", not(hasItem((int) borrador))))
                .andExpect(jsonPath("$.items[*].id", not(hasItem((int) sinStock))));
    }

    @Test
    void parametrosInvalidosDevuelven400() throws Exception {
        mockMvc.perform(get("/api/productos").param("tamano", "500")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/productos").param("pagina", "-1")).andExpect(status().isBadRequest());
    }

    // --- CCPIP-53: detalle del producto ---

    @Test
    void laFichaEsPublicaYMuestraLoNecesarioParaComprar() throws Exception {
        long id = idDe("Cafe tostado en grano 500 g");
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Cafe tostado en grano 500 g"))
                .andExpect(jsonPath("$.descripcion").exists())
                .andExpect(jsonPath("$.precio").value(46.00))
                .andExpect(jsonPath("$.linea.nombre").value("Cafe"))
                .andExpect(jsonPath("$.unidad.nombre").value("Gramo"))
                .andExpect(jsonPath("$.productor.id").isNumber())
                .andExpect(jsonPath("$.disponible").value(true));
    }

    @Test
    void unProductoSinStockSeVePeroMarcaNoDisponibleSinRevelarCantidades() throws Exception {
        long id = insertarProducto("Miel sin stock", "PUBLICADO", false);
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disponible").value(false))
                .andExpect(content().string(not(Matchers.containsString("cantidad"))))
                .andExpect(content().string(not(Matchers.containsString("lote"))));
    }

    @Test
    void unProductoNoPublicadoONoExistenteDevuelve404() throws Exception {
        long borrador = insertarProducto("Miel en borrador", "BORRADOR", true);
        mockMvc.perform(get("/api/productos/{id}", borrador))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        mockMvc.perform(get("/api/productos/{id}", 999_999)).andExpect(status().isNotFound());
    }

    // --- CCPIP-152: historia de origen ---

    @Test
    void laFichaMuestraLaHistoriaDeOrigenComunidadYRegion() throws Exception {
        mockMvc.perform(get("/api/productos/{id}", idDe("Aceite de ungurahui 250 ml")))
                .andExpect(jsonPath("$.historiaOrigen").value(Matchers.containsString("Ucayali")))
                .andExpect(jsonPath("$.productor.comunidad").value("Comunidad Shipibo Konibo de Yarinacocha"))
                .andExpect(jsonPath("$.productor.region").value("Ucayali"))
                .andExpect(jsonPath("$.productor.origenVerificado").value(true));
    }

    @Test
    void sinHistoriaCargadaElCampoNoViene() throws Exception {
        long id = insertarProducto("Miel sin historia", "PUBLICADO", true);
        jdbc.update("UPDATE producto SET historia_origen = '  ' WHERE id = ?", id);
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historiaOrigen").doesNotExist());
    }

    private long idDe(String nombre) {
        return jdbc.queryForObject("SELECT id FROM producto WHERE nombre = ?", Long.class, nombre);
    }

    private long insertarProducto(String nombre, String estado, boolean conStock) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado)
                SELECT (SELECT min(id) FROM productor), ?, 'Producto de prueba',
                       (SELECT id FROM linea_producto WHERE codigo = 'SUPERFRUTOS'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'G'), 20.00, ?
                RETURNING id""", Long.class, nombre, estado);
        if (conStock) {
            jdbc.update("""
                    INSERT INTO stock (producto_id, ubicacion_id, cantidad)
                    SELECT ?, id, 5 FROM ubicacion WHERE nombre = 'Centro Nacional Lima'""", id);
        }
        return id;
    }
}
