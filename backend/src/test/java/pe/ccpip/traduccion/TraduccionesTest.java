package pe.ccpip.traduccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-170 (RF-164): la traduccion queda ligada a su original y a su version, sin duplicar campo e
 * idioma. CCPIP-373 (RF-263): editar el original la deja desactualizada y el catalogo vuelve al
 * original con aviso. CCPIP-171 (RF-163): el catalogo dice el origen y da el original. CCPIP-147
 * (RF-168): traduccion automatica con el proveedor simulado; sin proveedor, 503 y nada cambia.
 */
@Transactional
class TraduccionesTest extends IntegrationTest {

    private static final String AGUAJE = "Aceite de aguaje 250 ml";

    private Cookie productor;

    @BeforeEach
    void ingresar() throws Exception {
        productor = loginAs("productor@ccpip.test");
    }

    // ---------- CCPIP-170: version y unicidad ----------

    @Test
    void elEditorMuestraCadaCampoConSuTraduccionYLaVersionDelOriginal() throws Exception {
        long id = producto(AGUAJE);
        mockMvc.perform(get("/api/productor/productos/{id}/traducciones/en", id).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idioma").value("en"))
                .andExpect(jsonPath("$.automaticaDisponible").value(true))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].original")
                        .value("Rico en vitamina A, prensado en frio."))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].texto")
                        .value("Rich in vitamin A, cold-pressed."))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].estado").value("APROBADA"))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].origen").value("COMUNIDAD"))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].version").value(1))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].vigente").value(true))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'HISTORIA' && @.campo == 'texto')].estado").value("APROBADA"));
    }

    /** Sin transaccion de prueba: cada insercion rechazada no deja abortada a la siguiente. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void laBaseNoAdmiteDosTraduccionesDelMismoCampoEIdioma() {
        long id = producto(AGUAJE);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO traduccion (entidad, clave, campo, idioma, texto) VALUES ('PRODUCTO', ?, 'descripcion', 'en', 'Otra')""",
                String.valueOf(id))).isInstanceOf(DataIntegrityViolationException.class);
        // El espanol es el original: no se guarda como traduccion
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO traduccion (entidad, clave, campo, idioma, texto) VALUES ('PRODUCTO', ?, 'nombre', 'es', 'Otro')""",
                String.valueOf(id))).isInstanceOf(DataIntegrityViolationException.class);
        // Una automatica sin proveedor o una desactualizada sin fecha son incoherentes
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO traduccion (entidad, clave, campo, idioma, texto, origen) VALUES ('PRODUCTO', '999999', 'nombre', 'en', 'X', 'AUTOMATICA')"""))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO traduccion (entidad, clave, campo, idioma, texto, estado) VALUES ('PRODUCTO', '999999', 'nombre', 'en', 'X', 'DESACTUALIZADA')"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- CCPIP-147: traduccion automatica (proveedor simulado) ----------

    @Test
    void laTraduccionAutomaticaQuedaPendienteYNoSeDuplicaAlPedirlaOtraVez() throws Exception {
        long id = productoConLote("Chocolate para traducir", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusYears(1));
        String clave = String.valueOf(id);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en", id).cookie(productor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.campos[?(@.campo == 'nombre')].texto").value("[simulado en] Chocolate para traducir"))
                    .andExpect(jsonPath("$.campos[?(@.campo == 'nombre')].estado").value("PENDIENTE"))
                    .andExpect(jsonPath("$.campos[?(@.campo == 'nombre')].origen").value("AUTOMATICA"))
                    .andExpect(jsonPath("$.campos[?(@.campo == 'nombre')].proveedor").value("simulado"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM traduccion WHERE entidad = 'PRODUCTO' AND clave = ?",
                Integer.class, clave)).isEqualTo(2);

        // Pendiente: el comprador sigue leyendo el original, sin etiqueta
        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.nombre").value("Chocolate para traducir"))
                .andExpect(jsonPath("$.traducciones").doesNotExist());

        // El productor corrige la descripcion y publica: la corregida pasa a ser de la comunidad
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en/aprobar", id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("textos", List.of(Map.of("entidad", "PRODUCTO", "campo", "descripcion",
                                "texto", "Test product"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campos[?(@.campo == 'nombre')].estado").value("APROBADA"))
                .andExpect(jsonPath("$.campos[?(@.campo == 'descripcion')].origen").value("COMUNIDAD"));

        // CCPIP-171: la ficha etiqueta cada texto con su origen y trae el original para "Ver original"
        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.nombre").value("[simulado en] Chocolate para traducir"))
                .andExpect(jsonPath("$.descripcion").value("Test product"))
                .andExpect(jsonPath("$.traducciones.nombre.origen").value("AUTOMATICA"))
                .andExpect(jsonPath("$.traducciones.nombre.vigente").value(true))
                .andExpect(jsonPath("$.traducciones.nombre.original").value("Chocolate para traducir"))
                .andExpect(jsonPath("$.traducciones.descripcion.origen").value("COMUNIDAD"))
                .andExpect(jsonPath("$.traducciones.descripcion.original").value("Producto de prueba"));
        // En espanol no hay nada que etiquetar
        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "es"))
                .andExpect(jsonPath("$.nombre").value("Chocolate para traducir"))
                .andExpect(jsonPath("$.traducciones").doesNotExist());

        // Sin nada pendiente no hay que publicar
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en/aprobar", id).cookie(productor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIN_TRADUCCION_PENDIENTE"));
    }

    @Test
    void unaTraduccionAprobadaYAlDiaNoSeReemplazaConLaAutomatica() throws Exception {
        long id = producto(AGUAJE);
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en", id).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].texto")
                        .value("Rich in vitamin A, cold-pressed."))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].origen").value("COMUNIDAD"));
    }

    @Test
    void sinProveedorResponde503YConservaElOriginalYLasTraduccionesQueHabia() throws Exception {
        traductor.sinProveedor(true);
        long id = producto(AGUAJE);
        String antes = jdbc.queryForObject("SELECT string_agg(campo || estado || texto, '|' ORDER BY campo) FROM traduccion WHERE clave = ? AND entidad IN ('PRODUCTO', 'HISTORIA')",
                String.class, String.valueOf(id));
        // Editar la descripcion en espanol la deja desactualizada; pedir la automatica falla sin tocar nada
        editarDescripcion(id, "Prensado en frio, sin refinar.");
        String desactualizadas = jdbc.queryForObject("SELECT string_agg(campo || estado || texto, '|' ORDER BY campo) FROM traduccion WHERE clave = ? AND entidad IN ('PRODUCTO', 'HISTORIA')",
                String.class, String.valueOf(id));
        assertThat(desactualizadas).isNotEqualTo(antes);

        mockMvc.perform(get("/api/productor/productos/{id}/traducciones/en", id).cookie(productor))
                .andExpect(jsonPath("$.automaticaDisponible").value(false));
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en", id).cookie(productor))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRADUCCION_NO_DISPONIBLE"));
        mockMvc.perform(post("/api/productor/tienda/traducciones/en").cookie(productor))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("SELECT string_agg(campo || estado || texto, '|' ORDER BY campo) FROM traduccion WHERE clave = ? AND entidad IN ('PRODUCTO', 'HISTORIA')",
                String.class, String.valueOf(id))).isEqualTo(desactualizadas);
        // El comprador ve el original en espanol de lo editado y la traduccion vigente de lo demas
        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion").value("Prensado en frio, sin refinar."))
                .andExpect(jsonPath("$.nombre").value("Aguaje oil 250 ml"));
    }

    // ---------- CCPIP-373: invalidar al editar el original ----------

    @Test
    void editarLaDescripcionDejaSuTraduccionDesactualizadaYElCatalogoMuestraElOriginal() throws Exception {
        long id = producto(AGUAJE);
        String clave = String.valueOf(id);
        editarDescripcion(id, "Prensado en frio, sin refinar.");

        // No se borra: queda desactualizada, con su texto y la fecha
        Map<String, Object> fila = jdbc.queryForMap(
                "SELECT estado, texto, desactualizada_en, version_original FROM traduccion WHERE entidad = 'PRODUCTO' AND clave = ? AND campo = 'descripcion'",
                clave);
        assertThat(fila.get("estado")).isEqualTo("DESACTUALIZADA");
        assertThat(fila.get("texto")).isEqualTo("Rich in vitamin A, cold-pressed.");
        assertThat(fila.get("desactualizada_en")).isNotNull();
        assertThat(version("PRODUCTO", clave, "descripcion")).isEqualTo(2);
        // El nombre no cambio: su traduccion sigue vigente
        assertThat(jdbc.queryForObject("SELECT estado FROM traduccion WHERE entidad = 'PRODUCTO' AND clave = ? AND campo = 'nombre'",
                String.class, clave)).isEqualTo("APROBADA");

        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.descripcion").value("Prensado en frio, sin refinar."))
                .andExpect(jsonPath("$.traducciones.descripcion.vigente").value(false))
                .andExpect(jsonPath("$.traducciones.descripcion.original").doesNotExist())
                .andExpect(jsonPath("$.nombre").value("Aguaje oil 250 ml"))
                .andExpect(jsonPath("$.traducciones.nombre.vigente").value(true));
        // La busqueda tampoco sirve la traduccion vieja (solo usa el nombre, que sigue vigente)
        mockMvc.perform(get("/api/productos").param("q", "aguaje").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.items[0].nombre").value("Aguaje oil 250 ml"));

        // Guardar sin cambiar el texto no sube la version
        editarDescripcion(id, "Prensado en frio, sin refinar.");
        assertThat(version("PRODUCTO", clave, "descripcion")).isEqualTo(2);

        // Traducir otra vez liga la nueva traduccion a la version 2, en la misma fila
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en", id).cookie(productor))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].texto")
                        .value("[simulado en] Prensado en frio, sin refinar."))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].version").value(2))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'PRODUCTO' && @.campo == 'descripcion')].vigente").value(true));
        assertThat(jdbc.queryForObject(
                "SELECT version_original FROM traduccion WHERE entidad = 'PRODUCTO' AND clave = ? AND campo = 'descripcion'",
                Integer.class, clave)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM traduccion WHERE entidad = 'PRODUCTO' AND clave = ? AND campo = 'descripcion'",
                Integer.class, clave)).isEqualTo(1);
    }

    @Test
    void aprobarOtraHistoriaInvalidaLaTraduccionDeSuTexto() throws Exception {
        long id = producto(AGUAJE);
        mockMvc.perform(post("/api/productor/productos/{id}/historia/aprobar", id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("textoFinal", "Recolectamos el aguaje del suelo del aguajal, sin cortar nunca la palmera.",
                                "asistida", false))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT estado FROM traduccion WHERE entidad = 'HISTORIA' AND clave = ? AND campo = 'texto'",
                String.class, String.valueOf(id))).isEqualTo("DESACTUALIZADA");
        mockMvc.perform(get("/api/productos/{id}", id).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.historia.texto").value("Recolectamos el aguaje del suelo del aguajal, sin cortar nunca la palmera."))
                .andExpect(jsonPath("$.traducciones['historia.texto'].vigente").value(false));
    }

    @Test
    void editarLaHistoriaDeLaTiendaInvalidaSuTraduccionYConservaLaCita() throws Exception {
        long productorId = jdbc.queryForObject(
                "SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor@ccpip.test'", Long.class);
        JsonNode t = objectMapper.readTree(mockMvc.perform(get("/api/productor/tienda").cookie(productor))
                .andReturn().getResponse().getContentAsString());
        Map<String, Object> datos = new LinkedHashMap<>();
        for (String campo : List.of("nombreTienda", "descripcionCorta", "cita", "pueblo", "localidad")) {
            datos.put(campo, t.get(campo).isNull() ? null : t.get(campo).asText());
        }
        datos.put("socios", t.get("socios").isNull() ? null : t.get("socios").asInt());
        datos.put("historia", "Somos mujeres shipibo que trabajamos el aceite de ungurahui desde hace cuatro generaciones.");
        mockMvc.perform(put("/api/productor/tienda").cookie(productor).contentType(MediaType.APPLICATION_JSON).content(json(datos)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT estado FROM traduccion WHERE entidad = 'COMUNIDAD' AND clave = ? AND campo = 'historia'",
                String.class, String.valueOf(productorId))).isEqualTo("DESACTUALIZADA");
        mockMvc.perform(get("/api/tiendas/{id}", productorId).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.historia").value(org.hamcrest.Matchers.startsWith("Somos mujeres shipibo")))
                .andExpect(jsonPath("$.traducciones.historia.vigente").value(false))
                .andExpect(jsonPath("$.cita").value("The forest's plants heal and nourish."))
                .andExpect(jsonPath("$.traducciones.cita.origen").value("COMUNIDAD"))
                .andExpect(jsonPath("$.traducciones.cita.original").value(t.get("cita").asText()));

        // PW-08: la tienda tambien se traduce con el proveedor y queda pendiente
        mockMvc.perform(post("/api/productor/tienda/traducciones/en").cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campos[?(@.entidad == 'COMUNIDAD' && @.campo == 'historia')].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.campos[?(@.entidad == 'COMUNIDAD' && @.campo == 'cita')].estado").value("APROBADA"));
    }

    // ---------- Permisos y casos invalidos ----------

    @Test
    void soloElProductorDuenoTraduceSuContenido() throws Exception {
        long id = producto(AGUAJE);
        mockMvc.perform(get("/api/productor/productos/{id}/traducciones/en", id)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en", id).cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(status().isForbidden());
        Cookie otro = loginAs("productor2@ccpip.test");
        mockMvc.perform(get("/api/productor/productos/{id}/traducciones/en", id).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en", id).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en/aprobar", id).cookie(otro)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM traduccion WHERE clave = ? AND estado = 'PENDIENTE'",
                Integer.class, String.valueOf(id))).isZero();
    }

    @Test
    void rechazaTraducirAlEspanolOUnCampoQueNoEsDelContenido() throws Exception {
        long id = producto(AGUAJE);
        for (String idioma : List.of("es", "fr")) {
            mockMvc.perform(post("/api/productor/productos/{id}/traducciones/{idioma}", id, idioma).cookie(productor))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("IDIOMA_NO_TRADUCIBLE"));
        }
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en/aprobar", id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("textos", List.of(Map.of("entidad", "LINEA", "campo", "nombre", "texto", "Oils"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMPO_NO_TRADUCIBLE"));
        mockMvc.perform(post("/api/productor/productos/{id}/traducciones/en/aprobar", id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("textos", List.of(Map.of("entidad", "PRODUCTO", "campo", "descripcion", "texto", " "))))))
                .andExpect(status().isBadRequest());
    }

    // ---------- Apoyo ----------

    private long producto(String nombre) {
        return jdbc.queryForObject("SELECT id FROM producto WHERE nombre = ?", Long.class, nombre);
    }

    private int version(String entidad, String clave, String campo) {
        return jdbc.queryForObject("""
                SELECT coalesce((SELECT version FROM contenido_version WHERE entidad = ? AND clave = ? AND campo = ?), 1)""",
                Integer.class, entidad, clave, campo);
    }

    /** PW-04: guarda el producto tal como esta, con otra descripcion. */
    private void editarDescripcion(long id, String descripcion) throws Exception {
        JsonNode p = objectMapper.readTree(mockMvc.perform(get("/api/productor/productos/{id}", id).cookie(productor))
                .andReturn().getResponse().getContentAsString());
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("nombre", p.get("nombre").asText());
        datos.put("descripcion", descripcion);
        datos.put("lineas", objectMapper.convertValue(p.get("lineas"), List.class));
        datos.put("unidad", p.get("unidad").asText());
        datos.put("contenido", p.get("contenido").isNull() ? null : p.get("contenido").asText());
        datos.put("modalidad", p.get("modalidad").asText());
        datos.put("precio", p.get("precio").isNull() ? null : p.get("precio").decimalValue());
        datos.put("precioMayorista", p.get("precioMayorista").isNull() ? null : p.get("precioMayorista").decimalValue());
        datos.put("cantidadMinimaMayorista", p.get("cantidadMinimaMayorista").isNull() ? null : p.get("cantidadMinimaMayorista").asInt());
        datos.put("esMateriaPrima", p.get("esMateriaPrima").asBoolean());
        mockMvc.perform(put("/api/productor/productos/{id}", id).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(datos)))
                .andExpect(status().isOk());
    }
}
