package pe.ccpip.imagenes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.ia.MejoradorImagen;
import pe.ccpip.ia.MejoradorImagenSimulado;
import pe.ccpip.ia.ValidadorImagen;
import pe.ccpip.ia.ValidadorImagenSimulado;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-144, CCPIP-168 y CCPIP-364: validar la foto contra su linea, consultar la recomendacion y
 * reemplazar desde ella. CCPIP-176, CCPIP-272, CCPIP-281 y CCPIP-282: mejora opcional, comparar,
 * aceptar o conservar la original. Con el proveedor simulado (color medio, sin IA real).
 */
class IaImagenesTest extends IntegrationTest {

    /** Cerca del color de referencia de Aceites en el simulador. */
    private static final Color AMBAR = new Color(200, 150, 50);
    /** Cerca del de Artesanias: para un aceite es una foto sospechosa. */
    private static final Color AZUL = new Color(60, 100, 170);

    @Autowired
    private ValidadorImagen validador;

    @Autowired
    private MejoradorImagen mejorador;

    private Cookie productor;
    private long producto;

    @BeforeEach
    void preparar() throws Exception {
        limpiar();
        productor = loginAs("productor@ccpip.test");
        producto = jdbc.queryForObject("SELECT id FROM producto WHERE nombre = 'Aceite de ungurahui 250 ml'", Long.class);
    }

    @AfterEach
    void restaurar() {
        ((ValidadorImagenSimulado) validador).simularFalla(false);
        ((MejoradorImagenSimulado) mejorador).simularFalla(false);
        limpiar();
    }

    private void limpiar() {
        jdbc.update("DELETE FROM imagen_mejora");
        jdbc.update("DELETE FROM imagen_validacion");
        jdbc.update("DELETE FROM imagen");
    }

    // ---------- Validacion (CCPIP-144, CCPIP-168) ----------

    @Test
    void validaUnaFotoQueCorrespondeYLaEvaluacionQuedaRegistrada() throws Exception {
        String foto = subir(Imagenes.color(400, 400, AMBAR));

        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", foto).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imagenId").value(foto))
                .andExpect(jsonPath("$.estado").value("VALIDA"))
                .andExpect(jsonPath("$.lineaEsperada.codigo").value("ACEITES"))
                .andExpect(jsonPath("$.linea.nombre").value("Aceites"))
                .andExpect(jsonPath("$.proveedor").value("simulado"))
                .andExpect(jsonPath("$.codigoError").value(nullValue()));

        // CCPIP-168: se consulta despues, por foto o por publicacion
        mockMvc.perform(get("/api/productor/imagenes/{id}/validacion", foto).cookie(productor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("VALIDA"));
        mockMvc.perform(get("/api/productor/imagenes/validaciones").param("productoId", String.valueOf(producto)).cookie(productor))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].imagenId").value(foto));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM imagen_validacion
                WHERE imagen_id = ? AND estado = 'VALIDA' AND terminada_en IS NOT NULL
                  AND solicitada_por = (SELECT id FROM usuario WHERE correo = 'productor@ccpip.test')""",
                Integer.class, UUID.fromString(foto))).isEqualTo(1);
    }

    @Test
    void unaFotoSospechosaEsSoloUnaRecomendacionYNoBloqueaLaPublicacion() throws Exception {
        String foto = subir(Imagenes.color(400, 400, AZUL));
        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", foto).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("SOSPECHOSA"))
                .andExpect(jsonPath("$.linea.codigo").value("ARTESANIA"))
                .andExpect(jsonPath("$.lineaEsperada.nombre").value("Aceites"));

        // RF-137: retirar y volver a publicar sigue funcionando con la foto sospechosa
        mockMvc.perform(post("/api/productor/productos/{id}/retirar", producto).cookie(productor)).andExpect(status().isOk());
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", producto).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_MODERACION"));
        aprobarPublicacion(producto);
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.imagenes[0].id").value(foto));
    }

    @Test
    void desdeElAvisoSeReemplazaLaFotoYLaNuevaSeVuelveAValidar() throws Exception {
        String sospechosa = subir(Imagenes.color(400, 400, AZUL));
        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", sospechosa).cookie(productor))
                .andExpect(jsonPath("$.estado").value("SOSPECHOSA"));

        // CCPIP-364: el mismo reemplazo de CCPIP-388
        String nueva = json(mockMvc.perform(multipart("/api/productor/imagenes/{id}/reemplazo", sospechosa)
                        .file(archivo(Imagenes.color(400, 400, AMBAR))).cookie(productor))
                .andExpect(status().isCreated())).get("id").asText();
        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", nueva).cookie(productor))
                .andExpect(jsonPath("$.estado").value("VALIDA"));

        // La evaluacion anterior queda registrada contra la foto retirada, que ya no se consulta en el editor
        mockMvc.perform(get("/api/productor/imagenes/{id}/validacion", sospechosa).cookie(productor)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/imagenes/validaciones").param("productoId", String.valueOf(producto)).cookie(productor))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].imagenId").value(nueva));
        assertThat(jdbc.queryForObject("SELECT estado FROM imagen_validacion WHERE imagen_id = ?",
                String.class, UUID.fromString(sospechosa))).isEqualTo("SOSPECHOSA");
    }

    @Test
    void sinValidacionNiFotosDeTiendaNoHayRecomendacion() throws Exception {
        String foto = subir(Imagenes.color(100, 100, AMBAR));
        mockMvc.perform(get("/api/productor/imagenes/{id}/validacion", foto).cookie(productor))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SIN_VALIDACION"));

        String portada = json(mockMvc.perform(multipart("/api/productor/imagenes").file(archivo(Imagenes.jpg(800, 300)))
                .param("tipo", "PORTADA").cookie(productor))).get("id").asText();
        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", portada).cookie(productor))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SOLO_FOTOS_DE_PRODUCTO"));
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", portada).cookie(productor))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- IA no disponible ----------

    @Test
    void sinIaRespondeNoDisponibleRegistraElErrorYConservaLaOriginal() throws Exception {
        String foto = subir(Imagenes.color(400, 400, AZUL));
        ((ValidadorImagenSimulado) validador).simularFalla(true);
        ((MejoradorImagenSimulado) mejorador).simularFalla(true);

        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", foto).cookie(productor))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IA_NO_DISPONIBLE"));
        // CCPIP-168: el error queda registrado y se puede seguir con la original
        mockMvc.perform(get("/api/productor/imagenes/{id}/validacion", foto).cookie(productor))
                .andExpect(jsonPath("$.estado").value("ERROR"))
                .andExpect(jsonPath("$.codigoError").value("IA_NO_DISPONIBLE"));

        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", foto).cookie(productor))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IA_NO_DISPONIBLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM imagen_mejora", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT principal AND retirada_en IS NULL FROM imagen WHERE id = ?",
                Boolean.class, UUID.fromString(foto))).isTrue();
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.imagenes[0].id").value(foto));
    }

    // ---------- Mejora opcional (CCPIP-176, CCPIP-272, CCPIP-281, CCPIP-282) ----------

    @Test
    void laMejoraEsUnaVariantePrivadaQueSoloSePublicaAlAceptarla() throws Exception {
        String foto = subir(Imagenes.apagada(400, 300));

        JsonNode comparacion = json(mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", foto).cookie(productor))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.original.url").value("/api/imagenes/" + foto))
                .andExpect(jsonPath("$.mejorada.url").value(startsWith("/api/productor/mejoras/")))
                .andExpect(jsonPath("$.ajustes[0]").value("AUTOCONTRASTE"))
                .andExpect(jsonPath("$.proveedor").value("simulado")));
        String mejora = comparacion.get("mejoraId").asText();

        // Pedirla otra vez devuelve la misma propuesta; la comparacion se puede volver a consultar
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", foto).cookie(productor))
                .andExpect(jsonPath("$.mejoraId").value(mejora));
        mockMvc.perform(get("/api/productor/imagenes/{id}/mejora", foto).cookie(productor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mejoraId").value(mejora));

        // La variante la ve solo su dueno y no es publica; la ficha sigue con la original
        mockMvc.perform(get(comparacion.get("mejorada").get("url").asText()).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get(comparacion.get("mejorada").get("urlMiniatura").asText()).cookie(productor)).andExpect(status().isOk());
        mockMvc.perform(get(comparacion.get("mejorada").get("url").asText()).cookie(loginAs("productor2@ccpip.test")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/imagenes/{id}", mejora)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.imagenes[0].id").value(foto));

        // CCPIP-282: aceptarla la pone en el lugar de la original, que queda retirada y apunta a ella
        String nueva = json(mockMvc.perform(post("/api/productor/imagenes/{id}/mejora/aceptar", foto).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(true))
                .andExpect(jsonPath("$.productoId").value(producto))).get("id").asText();
        mockMvc.perform(get("/api/productos/{id}", producto))
                .andExpect(jsonPath("$.imagenes.length()").value(1))
                .andExpect(jsonPath("$.imagenes[0].id").value(nueva));
        assertThat(jdbc.queryForObject("SELECT reemplazada_por FROM imagen WHERE id = ? AND retirada_en IS NOT NULL",
                UUID.class, UUID.fromString(foto))).isEqualTo(UUID.fromString(nueva));
        assertThat(jdbc.queryForObject("SELECT estado || ':' || imagen_resultante FROM imagen_mejora WHERE id = ?",
                String.class, UUID.fromString(mejora))).isEqualTo("ACEPTADA:" + nueva);
        mockMvc.perform(get("/api/imagenes/{id}", nueva)).andExpect(status().isOk());
        mockMvc.perform(get("/api/imagenes/{id}", foto)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND cambios ->> 'mejorada' = ? AND cambios ->> 'nueva' = ?""",
                Integer.class, producto, foto, nueva)).isEqualTo(1);

        // Ya no hay nada pendiente que aceptar
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora/aceptar", foto).cookie(productor)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/imagenes/{id}/mejora", nueva).cookie(productor))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SIN_MEJORA_PENDIENTE"));
    }

    @Test
    void rechazarLaMejoraConservaLaOriginal() throws Exception {
        String foto = subir(Imagenes.apagada(400, 300));
        String mejora = json(mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", foto).cookie(productor)))
                .get("mejoraId").asText();

        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora/rechazar", foto).cookie(productor)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT principal AND retirada_en IS NULL FROM imagen WHERE id = ?",
                Boolean.class, UUID.fromString(foto))).isTrue();
        assertThat(jdbc.queryForObject("SELECT estado FROM imagen_mejora WHERE id = ? AND decidida_en IS NOT NULL",
                String.class, UUID.fromString(mejora))).isEqualTo("RECHAZADA");
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.imagenes[0].id").value(foto));
        mockMvc.perform(get("/api/productor/imagenes/{id}/mejora", foto).cookie(productor)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora/aceptar", foto).cookie(productor)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora/rechazar", foto).cookie(productor)).andExpect(status().isNotFound());

        // Se puede volver a pedir otra propuesta
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", foto).cookie(productor)).andExpect(status().isCreated());
    }

    // ---------- Permisos ----------

    @Test
    void soloSobreFotosPropiasYSoloElProductor() throws Exception {
        String foto = subir(Imagenes.color(100, 100, AMBAR));
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", foto).cookie(productor)).andExpect(status().isCreated());

        Cookie otro = loginAs("productor2@ccpip.test");
        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", foto).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/imagenes/{id}/validacion", foto).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/imagenes/validaciones").param("productoId", String.valueOf(producto)).cookie(otro))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", foto).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/imagenes/{id}/mejora", foto).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora/aceptar", foto).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora/rechazar", foto).cookie(otro)).andExpect(status().isNotFound());

        Cookie consumidor = loginAs("consumidor@ccpip.test");
        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", foto).cookie(consumidor)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora/aceptar", foto).cookie(consumidor)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/productor/imagenes/{id}/validacion", foto)).andExpect(status().isUnauthorized());

        // Nada cambio: la original sigue y la propuesta sigue pendiente
        assertThat(jdbc.queryForObject("SELECT retirada_en IS NULL FROM imagen WHERE id = ?", Boolean.class, UUID.fromString(foto))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM imagen_mejora WHERE estado = 'PENDIENTE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM imagen_validacion", Integer.class)).isZero();
    }

    private String subir(byte[] contenido) throws Exception {
        return json(mockMvc.perform(multipart("/api/productor/imagenes").file(archivo(contenido))
                        .param("tipo", "PRODUCTO").param("productoId", String.valueOf(producto)).cookie(productor))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private static MockMultipartFile archivo(byte[] contenido) {
        return new MockMultipartFile("archivo", "foto", "application/octet-stream", contenido);
    }

    private JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }
}
