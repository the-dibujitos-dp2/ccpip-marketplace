package pe.ccpip.catalogo;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * Catalogo publico contra los productos sembrados en R__02: 10 productos publicados de 3
 * tiendas, cada uno con un lote vigente en el Centro Nacional Lima. Cada prueba corre en una
 * transaccion que se revierte.
 */
@Transactional
class CatalogoControllerTest extends IntegrationTest {

    /** Hoy en Lima, como lo calcula el servidor: el CI corre en UTC (de 19:00 a 24:00 en Lima ya es manana). */
    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    private static final LocalDate EN_UN_ANIO = HOY.plusYears(1);

    // --- CCPIP-52: buscar ---

    @Test
    void sinTerminoListaElCatalogoDisponibleSinSesion() throws Exception {
        mockMvc.perform(get("/api/productos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.totalComunidades").value(3))
                .andExpect(jsonPath("$.items[0].tienda.nombre").exists())
                .andExpect(jsonPath("$.items[0].tienda.pueblo").exists())
                .andExpect(jsonPath("$.items[*].disponible", everyItem(Matchers.is(true))));
    }

    @Test
    void buscaPorNombreSinDistinguirMayusculasNiTildes() throws Exception {
        mockMvc.perform(get("/api/productos").param("q", "CAFÉ"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].nombre", everyItem(startsWith("Cafe"))));
    }

    @Test
    void tambienBuscaEnLaDescripcion() throws Exception {
        mockMvc.perform(get("/api/productos").param("q", "frío"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].linea.codigo", everyItem(Matchers.is("ACEITES"))));
    }

    @Test
    void losResultadosSePaginan() throws Exception {
        mockMvc.perform(get("/api/productos").param("tamano", "4").param("pagina", "2"))
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.totalPaginas").value(3))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void sinResultadosDevuelveTotalCeroYLosComodinesSonLiterales() throws Exception {
        mockMvc.perform(get("/api/productos").param("q", "televisor")).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/productos").param("q", "%")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void ordenaPorPrecioYNovedades() throws Exception {
        mockMvc.perform(get("/api/productos").param("orden", "precio_asc"))
                .andExpect(jsonPath("$.items[0].nombre").value("Cafe molido 250 g"));
        mockMvc.perform(get("/api/productos").param("orden", "precio_desc"))
                .andExpect(jsonPath("$.items[0].nombre").value("Textil bordado shipibo"));
        mockMvc.perform(get("/api/productos").param("orden", "otro")).andExpect(status().isUnprocessableEntity());
    }

    // --- CCPIP-54 y CCPIP-165: filtros ---

    @Test
    void filtraPorLineaRegionComunidadYPrecio() throws Exception {
        mockMvc.perform(get("/api/productos").param("linea", "CACAO"))
                .andExpect(jsonPath("$.total").value(2));
        mockMvc.perform(get("/api/productos").param("region", "Junin"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].tienda.region", everyItem(Matchers.is("Junin"))));
        mockMvc.perform(get("/api/productos").param("precioMin", "40").param("precioMax", "55"))
                .andExpect(jsonPath("$.items[*].precio", everyItem(Matchers.both(
                        Matchers.greaterThanOrEqualTo(40.0)).and(Matchers.lessThanOrEqualTo(55.0)))));
        Long tienda = jdbc.queryForObject("SELECT id FROM productor WHERE nombre_tienda = 'C.N. Yarina'", Long.class);
        mockMvc.perform(get("/api/productos").param("tienda", tienda.toString()))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void lasFacetasTraenConteosParaLosFiltros() throws Exception {
        mockMvc.perform(get("/api/productos"))
                .andExpect(jsonPath("$.facetas.lineas[?(@.codigo == 'CAFE')].total").value(hasItem(2)))
                .andExpect(jsonPath("$.facetas.regiones[?(@.codigo == 'Ucayali')].total").value(hasItem(4)))
                .andExpect(jsonPath("$.facetas.modalidades[?(@.codigo == 'MINORISTA')].total").value(hasItem(10)))
                .andExpect(jsonPath("$.facetas.comunidades.length()").value(3));
    }

    @Test
    void soloMuestraLoVendibleSalvoQueSePidanTodos() throws Exception {
        long borrador = productoConLote("Miel borrador", "BORRADOR", "PUNTO_ACOPIO", EN_UN_ANIO);
        long sinLote = productoConLote("Miel sin lote", "PUBLICADO", null, null);
        long vencido = productoConLote("Miel vencida", "PUBLICADO", "PUNTO_ACOPIO", HOY.minusDays(1));
        long enAlmacen = productoConLote("Miel en almacen", "PUBLICADO", "ALMACEN_PRODUCTOR", EN_UN_ANIO);
        long vendible = productoConLote("Miel vendible", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_ANIO);

        mockMvc.perform(get("/api/productos").param("q", "miel"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(vendible));

        // "Mostrar solo disponibles" apagado: aparecen los agotados, nunca los borradores
        mockMvc.perform(get("/api/productos").param("q", "miel").param("disponibles", "false"))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[*].id", not(hasItem((int) borrador))))
                .andExpect(jsonPath("$.items[?(@.id == " + sinLote + ")].disponible").value(hasItem(false)))
                .andExpect(jsonPath("$.items[?(@.id == " + vencido + ")].disponible").value(hasItem(false)))
                .andExpect(jsonPath("$.items[?(@.id == " + enAlmacen + ")].disponible").value(hasItem(false)));
    }

    @Test
    void unaTiendaSinPublicarNoMuestraSusProductos() throws Exception {
        jdbc.update("UPDATE productor SET tienda_publicada = false WHERE nombre_tienda = 'C.N. Yarina'");
        mockMvc.perform(get("/api/productos")).andExpect(jsonPath("$.total").value(7));
    }

    @Test
    void parametrosInvalidosDevuelven400o422() throws Exception {
        mockMvc.perform(get("/api/productos").param("tamano", "500")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/productos").param("pagina", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/productos").param("modalidad", "otra")).andExpect(status().isUnprocessableEntity());
    }

    // --- CCPIP-53: ficha ---

    @Test
    void laFichaEsPublicaYMuestraLoNecesarioParaComprar() throws Exception {
        mockMvc.perform(get("/api/productos/{id}", idDe("Cafe tostado en grano 500 g")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion").exists())
                .andExpect(jsonPath("$.precio").value(46.00))
                .andExpect(jsonPath("$.modalidad").value("MINORISTA"))
                .andExpect(jsonPath("$.linea.nombre").value("Café"))
                .andExpect(jsonPath("$.unidad.nombre").value("Gramo"))
                .andExpect(jsonPath("$.tienda.nombre").value("Coop. Río Ene"))
                .andExpect(jsonPath("$.disponible").value(true))
                .andExpect(jsonPath("$.cosecha.consumirAntesDe").exists())
                .andExpect(jsonPath("$.relacionados[*].nombre").value(hasItem("Cafe molido 250 g")));
    }

    @Test
    void sinStockSeVeComoNoDisponibleYNuncaExponeLotesNiCantidades() throws Exception {
        long id = productoConLote("Miel agotada", "PUBLICADO", "PUNTO_ACOPIO", HOY.minusDays(1));
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disponible").value(false))
                .andExpect(jsonPath("$.cosecha").doesNotExist())
                .andExpect(content().string(not(containsString("L-T"))))
                .andExpect(content().string(not(containsString("cantidad\""))));
    }

    @Test
    void sinCuentaBancariaActivaLaFichaSeVePeroNoEsComprable() throws Exception {
        long id = idDe("Cafe tostado en grano 500 g");
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(jsonPath("$.comprable").value(true));

        // CCPIP-269: la oferta sigue consultable, con su precio y su stock, pero no se puede comprar
        jdbc.update("UPDATE cuenta_bancaria SET estado = 'DESACTIVADA' WHERE productor_id = (SELECT productor_id FROM producto WHERE id = ?)",
                id);
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precio").value(46.00))
                .andExpect(jsonPath("$.disponible").value(true))
                .andExpect(jsonPath("$.comprable").value(false));
        mockMvc.perform(get("/api/productos").param("q", "cafe tostado"))
                .andExpect(jsonPath("$.items[*].id").value(hasItem((int) id)));
    }

    @Test
    void unaAutorizacionVencidaDejaLaFichaSinCompra() throws Exception {
        long id = productoConLote("Miel autorizada", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_ANIO);
        jdbc.update("UPDATE autorizacion_producto SET vigente_hasta = current_date - 2 WHERE producto_id = ?", id);
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comprable").value(false));
    }

    @Test
    void unProductoNoPublicadoONoExistenteDevuelve404() throws Exception {
        long borrador = productoConLote("Miel en borrador", "BORRADOR", null, null);
        mockMvc.perform(get("/api/productos/{id}", borrador))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        mockMvc.perform(get("/api/productos/{id}", 999_999)).andExpect(status().isNotFound());
    }

    // --- CCPIP-152: historia ---

    @Test
    void laFichaMuestraLaHistoriaAprobadaConComunidadYRegion() throws Exception {
        mockMvc.perform(get("/api/productos/{id}", idDe("Aceite de ungurahui 250 ml")))
                .andExpect(jsonPath("$.historia.texto").value(containsString("Ucayali")))
                .andExpect(jsonPath("$.historia.asistida").value(false))
                .andExpect(jsonPath("$.tienda.comunidad").value("Comunidad Shipibo Konibo de Yarinacocha"))
                .andExpect(jsonPath("$.tienda.region").value("Ucayali"))
                .andExpect(jsonPath("$.tienda.origenVerificado").value(true));
    }

    @Test
    void unaHistoriaSinAprobarNoSePublica() throws Exception {
        long id = idDe("Aceite de ungurahui 250 ml");
        jdbc.update("UPDATE producto_historia SET aprobada_en = NULL WHERE producto_id = ?", id);
        mockMvc.perform(get("/api/productos/{id}", id)).andExpect(jsonPath("$.historia").doesNotExist());
    }

    // --- CCPIP-153, 154 y 56: tiendas ---

    @Test
    void listaLasTiendasPublicadasYMuestraCadaUna() throws Exception {
        mockMvc.perform(get("/api/tiendas"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].nombre").value(hasItem("Asoc. Nii Joi")));
        Long id = jdbc.queryForObject("SELECT id FROM productor WHERE nombre_tienda = 'Asoc. Nii Joi'", Long.class);
        mockMvc.perform(get("/api/tiendas/{id}", id))
                .andExpect(jsonPath("$.pueblo").value("Shipibo-Konibo"))
                .andExpect(jsonPath("$.cita").value("Las plantas del monte curan y alimentan."))
                .andExpect(jsonPath("$.historia").exists())
                .andExpect(jsonPath("$.productos.length()").value(4))
                .andExpect(jsonPath("$.lineas[*].codigo").value(hasItem("ACEITES")));
    }

    @Test
    void unaTiendaSinPublicarNoExisteParaElPublico() throws Exception {
        Long nueva = jdbc.queryForObject("""
                SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id
                WHERE u.correo = 'productor_nuevo@ccpip.test'""", Long.class);
        mockMvc.perform(get("/api/tiendas/{id}", nueva)).andExpect(status().isNotFound());
    }

    @Test
    void elInicioTraeComunidadesLineasYProductos() throws Exception {
        mockMvc.perform(get("/api/inicio"))
                .andExpect(jsonPath("$.tiendas.length()").value(3))
                .andExpect(jsonPath("$.lineas[0].codigo").value("CACAO"))
                .andExpect(jsonPath("$.destacados.length()").value(8))
                .andExpect(jsonPath("$.novedades.length()").value(8));
    }

    @Test
    void losCatalogosCerradosIncluyenLasPresentaciones() throws Exception {
        mockMvc.perform(get("/api/catalogos"))
                .andExpect(jsonPath("$.unidades[*].codigo").value(hasItem("BOTELLA")))
                .andExpect(jsonPath("$.lineas[?(@.codigo == 'ARTESANIA')].requiereVencimiento").value(hasItem(false)))
                .andExpect(jsonPath("$.lineas[?(@.codigo == 'ACEITES')].unidadSugerida").value(hasItem("BOTELLA")))
                .andExpect(jsonPath("$.comisionPorcentaje").value(10));
    }

    private long idDe(String nombre) {
        return jdbc.queryForObject("SELECT id FROM producto WHERE nombre = ?", Long.class, nombre);
    }
}
