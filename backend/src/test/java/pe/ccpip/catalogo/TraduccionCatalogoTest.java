package pe.ccpip.catalogo;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-166 (productos), CCPIP-357 (tiendas) y CCPIP-358 (comunidades): el catalogo responde con el
 * contenido aprobado en ingles sembrado en R__03, y con el original en espanol para lo demas.
 */
@Transactional
class TraduccionCatalogoTest extends IntegrationTest {

    private long producto(String nombre) {
        return jdbc.queryForObject("SELECT id FROM producto WHERE nombre = ?", Long.class, nombre);
    }

    private long productor(String correo) {
        return jdbc.queryForObject(
                "SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?", Long.class, correo);
    }

    // --- CCPIP-166: productos ---

    @Test
    void laFichaEnInglesMuestraElContenidoAprobado() throws Exception {
        mockMvc.perform(get("/api/productos/{id}", producto("Aceite de aguaje 250 ml")).header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.nombre").value("Aguaje oil 250 ml"))
                .andExpect(jsonPath("$.descripcion").value("Rich in vitamin A, cold-pressed."))
                .andExpect(jsonPath("$.historia.texto").value("Aguaje is gathered from the ground, never by cutting the tree."))
                .andExpect(jsonPath("$.linea.nombre").value("Oils"))
                .andExpect(jsonPath("$.unidad.nombre").value("Milliliter"))
                // Los nombres propios de la tienda no se traducen
                .andExpect(jsonPath("$.tienda.nombre").value("Asoc. Nii Joi"));
    }

    @Test
    void enEspanolOSinCabeceraSeMuestraElOriginal() throws Exception {
        long id = producto("Aceite de aguaje 250 ml");
        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "es-PE"))
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "es"))
                .andExpect(jsonPath("$.nombre").value("Aceite de aguaje 250 ml"))
                .andExpect(jsonPath("$.linea.nombre").value("Aceites"));
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "es"))
                .andExpect(jsonPath("$.nombre").value("Aceite de aguaje 250 ml"));
    }

    @Test
    void unIdiomaNoSoportadoOUnaCabeceraInvalidaRespondenEnEspanol() throws Exception {
        long id = producto("Aceite de aguaje 250 ml");
        for (String cabecera : new String[] {"fr-FR,fr;q=0.9", "en;q=0", "no es una cabecera ;;;"}) {
            mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, cabecera))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "es"))
                    .andExpect(jsonPath("$.nombre").value("Aceite de aguaje 250 ml"));
        }
    }

    @Test
    void unaTraduccionPendienteNoSePublica() throws Exception {
        long id = producto("Aceite de aguaje 250 ml");
        jdbc.update("UPDATE traduccion SET estado = 'PENDIENTE', aprobada_en = NULL WHERE entidad = 'PRODUCTO' AND clave = ? AND campo = 'nombre'",
                String.valueOf(id));
        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.nombre").value("Aceite de aguaje 250 ml"))
                // Lo demas sigue aprobado
                .andExpect(jsonPath("$.descripcion").value("Rich in vitamin A, cold-pressed."));
    }

    @Test
    void unProductoSinTraduccionSeMuestraEnSuOriginal() throws Exception {
        long id = productoConLote("Chocolate de prueba sin traducir", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusYears(1));
        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.nombre").value("Chocolate de prueba sin traducir"))
                .andExpect(jsonPath("$.linea.nombre").value("Superfruits"));
    }

    @Test
    void lasOfertasDelMaestroEnInglesTraducenSuNombreEHistoriaYLaOfertaSinTraduccionQuedaEnSuOriginal() throws Exception {
        long maestro = jdbc.queryForObject("SELECT id FROM producto_maestro WHERE codigo = 'CACAO-GRANO'", Long.class);
        mockMvc.perform(get("/api/maestros/{id}", maestro).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.linea.nombre").value("Cacao & chocolate"))
                .andExpect(jsonPath("$.ofertas[0].nombre").value("Fermented cacao beans 1 kg"))
                .andExpect(jsonPath("$.ofertas[0].historia.texto").value("It grows in the shade of primary forest, without deforestation."))
                .andExpect(jsonPath("$.ofertas[1].nombre").value("Cacao fino de aroma 1 kg"));
        // CCPIP-42: la ficha trae las mismas ofertas traducidas
        mockMvc.perform(get("/api/productos/{id}", producto("Cacao fino de aroma 1 kg")).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.maestro.ofertas[*].nombre", hasItem("Fermented cacao beans 1 kg")));
    }

    @Test
    void laBusquedaEnInglesTraduceProductosYLineas() throws Exception {
        mockMvc.perform(get("/api/productos").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].nombre", hasItem("Ground coffee 250 g")))
                .andExpect(jsonPath("$.facetas.lineas[*].nombre", hasItem("Coffee")));
    }

    @Test
    void losCatalogosDeLineasYUnidadesSalenEnIngles() throws Exception {
        mockMvc.perform(get("/api/catalogos").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.lineas[*].nombre", hasItem("Cacao & chocolate")))
                .andExpect(jsonPath("$.unidades[*].nombre", hasItem("Kilogram")));
    }

    // --- CCPIP-357 tiendas y CCPIP-358 comunidades ---

    @Test
    void laTiendaEnInglesTraduceSuDescripcionYLaHistoriaDeLaComunidad() throws Exception {
        long id = productor("productor2@ccpip.test");
        mockMvc.perform(get("/api/tiendas/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.nombre").value("C.N. Yarina"))
                .andExpect(jsonPath("$.descripcion").value("Fine-flavor cacao and herbal teas from the Upper Marañón."))
                .andExpect(jsonPath("$.cita").value("We plant cacao where there was once logging."))
                .andExpect(jsonPath("$.historia").value(org.hamcrest.Matchers.startsWith("Our cacao grows in the shade of the forest")))
                .andExpect(jsonPath("$.productos[*].nombre", hasItem("Cacao paste 500 g")));
    }

    @Test
    void elListadoDeComunidadesYElInicioUsanLaCitaEnIngles() throws Exception {
        mockMvc.perform(get("/api/tiendas").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$[*].cita", hasItem("The forest's plants heal and nourish.")));
        mockMvc.perform(get("/api/inicio").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.lineas[*].nombre", hasItem("Oils")));
    }

    @Test
    void laTiendaEnEspanolConservaSuTextoOriginal() throws Exception {
        mockMvc.perform(get("/api/tiendas/{id}", productor("productor2@ccpip.test")).header(HttpHeaders.ACCEPT_LANGUAGE, "es"))
                .andExpect(jsonPath("$.cita").value("Sembramos cacao donde antes hubo tala."));
    }
}
