package pe.ccpip.imagenes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/** CCPIP-44 y CCPIP-30: fotos de producto, portada y galeria contra S3Mock. CCPIP-387 a CCPIP-389: consultar, reemplazar y retirar. */
class ImagenesControllerTest extends IntegrationTest {

    private Cookie productor;
    private long producto;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM imagen");
        productor = loginAsUnchecked("productor@ccpip.test");
        producto = jdbc.queryForObject("SELECT id FROM producto WHERE nombre = 'Aceite de ungurahui 250 ml'", Long.class);
    }

    @Test
    void subeUnaFotoDeProductoYSeSirvePublicaConCacheLarga() throws Exception {
        JsonNode foto = json(subir(Imagenes.jpg(2400, 1600), "PRODUCTO", null, producto)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(true))
                .andExpect(jsonPath("$.ancho").value(1600))
                .andExpect(jsonPath("$.url").value(startsWith("/api/imagenes/"))));

        mockMvc.perform(get(foto.get("url").asText()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
        byte[] mini = mockMvc.perform(get(foto.get("urlMiniatura").asText())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(mini.length).isPositive();

        // La ficha publica muestra la foto
        mockMvc.perform(get("/api/productos/{id}", producto))
                .andExpect(jsonPath("$.imagenes[0].url").value(foto.get("url").asText()));
    }

    @Test
    void aceptaPngYWebP() throws Exception {
        subir(Imagenes.png(300, 300), "PRODUCTO", null, producto).andExpect(status().isCreated());
        subir(Imagenes.WEBP, "PRODUCTO", null, producto).andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(false));
    }

    @Test
    void rechazaConMensajeClaroLoQueNoEsFoto() throws Exception {
        subir("no soy una foto".getBytes(), "PRODUCTO", null, producto)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORMATO_NO_PERMITIDO"))
                .andExpect(jsonPath("$.error").value("Solo se aceptan fotos JPG, PNG o WebP."));
    }

    @Test
    void limitaASeisFotosPorProducto() throws Exception {
        for (int i = 0; i < ImagenService.MAX_FOTOS_PRODUCTO; i++) {
            subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto).andExpect(status().isCreated());
        }
        subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LIMITE_DE_FOTOS"));
    }

    @Test
    void cambiaLaPrincipalYAlBorrarlaOtraTomaSuLugar() throws Exception {
        String primera = json(subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto)).get("id").asText();
        String segunda = json(subir(Imagenes.jpg(60, 60), "PRODUCTO", null, producto)).get("id").asText();

        mockMvc.perform(put("/api/productor/imagenes/{id}/principal", segunda).cookie(productor)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.imagenes[0].id").value(segunda));

        mockMvc.perform(delete("/api/productor/imagenes/{id}", segunda).cookie(productor)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT principal FROM imagen WHERE id = ?", Boolean.class, UUID.fromString(primera))).isTrue();
        mockMvc.perform(get("/api/productos/{id}", producto))
                .andExpect(jsonPath("$.imagenes.length()").value(1))
                .andExpect(jsonPath("$.imagenes[0].id").value(primera));
    }

    @Test
    void retirarConservaLaFotoYRegistraQuienYCuando() throws Exception {
        String foto = json(subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto)).get("id").asText();

        mockMvc.perform(delete("/api/productor/imagenes/{id}", foto).cookie(productor)).andExpect(status().isNoContent());

        // CCPIP-389: sigue en la base y se sigue sirviendo para referencias historicas
        assertThat(jdbc.queryForObject("""
                SELECT retirada_en IS NOT NULL AND retirada_por = (SELECT id FROM usuario WHERE correo = 'productor@ccpip.test')
                FROM imagen WHERE id = ?""", Boolean.class, UUID.fromString(foto))).isTrue();
        mockMvc.perform(get("/api/imagenes/{id}", foto)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'IMAGEN' AND cambios ->> 'retirada' = ?""",
                Integer.class, producto, foto)).isEqualTo(1);

        // Sale del editor y de la ficha publica, y no se puede retirar dos veces
        mockMvc.perform(get("/api/productor/imagenes").param("productoId", String.valueOf(producto)).cookie(productor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.imagenes.length()").value(0));
        mockMvc.perform(delete("/api/productor/imagenes/{id}", foto).cookie(productor)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/productor/imagenes/{id}/principal", foto).cookie(productor)).andExpect(status().isNotFound());
    }

    @Test
    void lasRetiradasNoCuentanParaElLimiteDeFotos() throws Exception {
        String primera = null;
        for (int i = 0; i < ImagenService.MAX_FOTOS_PRODUCTO; i++) {
            String id = json(subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto)).get("id").asText();
            primera = primera == null ? id : primera;
        }
        mockMvc.perform(delete("/api/productor/imagenes/{id}", primera).cookie(productor)).andExpect(status().isNoContent());
        subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto).andExpect(status().isCreated());
    }

    @Test
    void reemplazarPoneLaNuevaEnElMismoLugarYConservaLaAnterior() throws Exception {
        String principal = json(subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto)).get("id").asText();
        String segunda = json(subir(Imagenes.jpg(60, 60), "PRODUCTO", null, producto)).get("id").asText();

        JsonNode nueva = json(reemplazar(principal, Imagenes.png(80, 80), productor)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(true))
                .andExpect(jsonPath("$.productoId").value(producto)));
        String id = nueva.get("id").asText();

        mockMvc.perform(get("/api/productor/imagenes").param("productoId", String.valueOf(producto)).cookie(productor))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[1].id").value(segunda));
        assertThat(jdbc.queryForObject("SELECT reemplazada_por FROM imagen WHERE id = ? AND retirada_en IS NOT NULL",
                UUID.class, UUID.fromString(principal))).isEqualTo(UUID.fromString(id));
        mockMvc.perform(get("/api/imagenes/{id}", principal)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND cambios ->> 'reemplazada' = ? AND cambios ->> 'nueva' = ?""",
                Integer.class, producto, principal, id)).isEqualTo(1);
    }

    @Test
    void reemplazarValidaElArchivoYNoTocaLaFotoSiFalla() throws Exception {
        String foto = json(subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto)).get("id").asText();

        reemplazar(foto, "no soy una foto".getBytes(), productor)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FORMATO_NO_PERMITIDO"));
        reemplazar(foto, new byte[0], productor)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ARCHIVO_VACIO"));

        assertThat(jdbc.queryForObject("SELECT principal AND retirada_en IS NULL FROM imagen WHERE id = ?",
                Boolean.class, UUID.fromString(foto))).isTrue();
    }

    @Test
    void soloSeReemplazanFotosDeProducto() throws Exception {
        String portada = json(subir(Imagenes.jpg(800, 300), "PORTADA", null, null)).get("id").asText();
        reemplazar(portada, Imagenes.jpg(800, 300), productor)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOLO_FOTOS_DE_PRODUCTO"));

        // La galeria se sigue borrando: no es parte de una publicacion
        String galeria = json(subir(Imagenes.jpg(100, 100), "GALERIA", "CULTIVO", null)).get("id").asText();
        mockMvc.perform(delete("/api/productor/imagenes/{id}", galeria).cookie(productor)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/imagenes/{id}", galeria)).andExpect(status().isNotFound());
    }

    @Test
    void laPortadaNuevaReemplazaALaAnteriorYLaGaleriaPideCategoria() throws Exception {
        String vieja = json(subir(Imagenes.jpg(800, 300), "PORTADA", null, null)).get("id").asText();
        subir(Imagenes.jpg(800, 300), "PORTADA", null, null).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM imagen WHERE tipo = 'PORTADA'", Integer.class)).isEqualTo(1);
        mockMvc.perform(get("/api/imagenes/{id}", vieja)).andExpect(status().isNotFound());

        subir(Imagenes.jpg(100, 100), "GALERIA", null, null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CATEGORIA_INVALIDA"));
        subir(Imagenes.jpg(100, 100), "GALERIA", "CULTIVO", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoria").value("CULTIVO"));
    }

    @Test
    void noSeTocanImagenesNiProductosAjenos() throws Exception {
        String foto = json(subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto)).get("id").asText();
        Cookie otro = loginAsUnchecked("productor2@ccpip.test");
        mockMvc.perform(delete("/api/productor/imagenes/{id}", foto).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/productor/imagenes/{id}/principal", foto).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/productor/imagenes").file(archivo(Imagenes.jpg(50, 50)))
                        .param("tipo", "PRODUCTO").param("productoId", String.valueOf(producto)).cookie(otro))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/productor/imagenes").file(archivo(Imagenes.jpg(50, 50))).param("tipo", "PORTADA"))
                .andExpect(status().isUnauthorized());

        // CCPIP-387 y CCPIP-388: tampoco se consultan ni se reemplazan
        mockMvc.perform(get("/api/productor/imagenes").param("productoId", String.valueOf(producto)).cookie(otro))
                .andExpect(status().isNotFound());
        reemplazar(foto, Imagenes.jpg(50, 50), otro).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/imagenes").param("productoId", String.valueOf(producto))
                        .cookie(loginAsUnchecked("consumidor@ccpip.test")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT retirada_en IS NULL FROM imagen WHERE id = ?",
                Boolean.class, UUID.fromString(foto))).isTrue();
    }

    @Test
    void consultaLasFotosDeSuPublicacionYRechazaDatosInvalidos() throws Exception {
        String foto = json(subir(Imagenes.jpg(50, 50), "PRODUCTO", null, producto)).get("id").asText();
        mockMvc.perform(get("/api/productor/imagenes").param("productoId", String.valueOf(producto)).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(foto))
                .andExpect(jsonPath("$[0].principal").value(true))
                .andExpect(jsonPath("$[0].urlMiniatura").value(startsWith("/api/imagenes/")));
        mockMvc.perform(get("/api/productor/imagenes").param("productoId", "abc").cookie(productor))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/productor/imagenes").param("productoId", "999999").cookie(productor))
                .andExpect(status().isNotFound());
    }

    private ResultActions subir(byte[] contenido, String tipo, String categoria, Long productoId) throws Exception {
        var pedido = multipart("/api/productor/imagenes").file(archivo(contenido)).param("tipo", tipo).cookie(productor);
        if (categoria != null) {
            pedido.param("categoria", categoria);
        }
        if (productoId != null) {
            pedido.param("productoId", productoId.toString());
        }
        return mockMvc.perform(pedido);
    }

    private ResultActions reemplazar(String id, byte[] contenido, Cookie quien) throws Exception {
        return mockMvc.perform(multipart("/api/productor/imagenes/{id}/reemplazo", id).file(archivo(contenido)).cookie(quien));
    }

    private static MockMultipartFile archivo(byte[] contenido) {
        return new MockMultipartFile("archivo", "foto", "application/octet-stream", contenido);
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private Cookie loginAsUnchecked(String correo) {
        try {
            return loginAs(correo);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
