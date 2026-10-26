package pe.ccpip.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Videos de la tienda (acordado el 26/10: MP4 o WebM de hasta 50 MB y 3 minutos, 5 por tienda). CCPIP-118:
 * el productor sube un video, el subadministrador lo revisa y el aprobado se ve en la tienda publica.
 * Transaccional: los videos de la prueba no llegan a las demas (el archivo queda en S3Mock).
 */
@Transactional
class VideosTest extends IntegrationTest {

    private static final String PRODUCTOR = "productor@ccpip.test";
    private static final String SUB_ADMIN = "sub_admin@ccpip.test";

    private Cookie productor;
    private Cookie subAdmin;
    private long tienda;

    @BeforeEach
    void preparar() throws Exception {
        productor = loginAs(PRODUCTOR);
        subAdmin = loginAs(SUB_ADMIN);
        tienda = jdbc.queryForObject("""
                SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?""", Long.class, PRODUCTOR);
        jdbc.update("UPDATE productor SET tienda_publicada = true WHERE id = ?", tienda);
    }

    // ---------- CCPIP-118: subir ----------

    @Test
    void subirUnVideoLoDejaEnRevisionYSoloLoVeSuProductor() throws Exception {
        long video = id(subir("Cosecha de cacao", "cosecha.mp4", "video/mp4")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.titulo").value("Cosecha de cacao"))
                .andExpect(jsonPath("$.estado").value("EN_REVISION"))
                .andExpect(jsonPath("$.formato").value("MP4"))
                .andExpect(jsonPath("$.duracionSegundos").value(4.0))
                .andExpect(jsonPath("$.url").exists()));

        // En revision: no es publico ni lo ve otro productor
        mockMvc.perform(get("/api/videos/{id}", video)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/videos/{id}", video).cookie(loginAs("productor_nuevo@ccpip.test")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tiendas/{id}", tienda)).andExpect(jsonPath("$.videos").value(empty()));

        // Su productor lo ve completo y por partes, sin cache
        byte[] completo = mockMvc.perform(get("/api/videos/{id}", video).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(completo).isEqualTo(recurso("cosecha.mp4"));
        byte[] parte = mockMvc.perform(get("/api/videos/{id}", video).cookie(productor).header("Range", "bytes=10-109"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 10-109/" + completo.length))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(parte).hasSize(100).isEqualTo(java.util.Arrays.copyOfRange(completo, 10, 110));
        mockMvc.perform(get("/api/videos/{id}", video).cookie(productor).header("Range", "bytes=999999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
    }

    @Test
    void aceptaWebmYRechazaLoQueNoCumpleLoAcordado() throws Exception {
        subir("Telar de cintura", "telar.webm", "video/webm")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formato").value("WEBM"));
        subir("Muy largo", "largo.mp4", "video/mp4")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIDEO_MUY_LARGO"));
        // Lo que dice el navegador no basta: se lee el contenido
        mockMvc.perform(multipart("/api/productor/videos").file(new MockMultipartFile("archivo", "foto.mp4", "video/mp4",
                        "no soy un video".getBytes())).param("titulo", "Falso").cookie(productor))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIDEO_NO_RECONOCIDO"));
        subir("   ", "cosecha.mp4", "video/mp4")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TITULO_REQUERIDO"));
    }

    @Test
    void unaTiendaTieneHastaCincoVideos() throws Exception {
        for (int i = 0; i < VideosService.MAX_VIDEOS; i++) {
            subir("Video " + i, "cosecha.mp4", "video/mp4").andExpect(status().isCreated());
        }
        subir("Sexto", "cosecha.mp4", "video/mp4")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LIMITE_DE_VIDEOS"));
        // Uno rechazado deja su lugar
        jdbc.update("""
                UPDATE video_tienda SET estado = 'RECHAZADO', motivo = 'Prueba', decidido_en = now(),
                       decidido_por = (SELECT id FROM usuario WHERE correo = ?)
                WHERE id = (SELECT max(id) FROM video_tienda WHERE productor_id = ?)""", SUB_ADMIN, tienda);
        subir("Sexto", "cosecha.mp4", "video/mp4").andExpect(status().isCreated());
    }

    @Test
    void soloElProductorSubeVideos() throws Exception {
        mockMvc.perform(multipart("/api/productor/videos").file(archivo("cosecha.mp4", "video/mp4")).param("titulo", "x")
                .cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/productor/videos").file(archivo("cosecha.mp4", "video/mp4")).param("titulo", "x"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- CCPIP-118: la revision del subadministrador ----------

    @Test
    void elSubadministradorApruebaYElVideoSeVeEnLaTienda() throws Exception {
        long video = id(subir("Cosecha de cacao", "cosecha.mp4", "video/mp4"));
        mockMvc.perform(get("/api/admin/moderacion/videos").cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contadores.pendientes").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].titulo".formatted(video)).value("Cosecha de cacao"))
                .andExpect(jsonPath("$.solicitudes[?(@.id == %d)].reemplazo".formatted(video)).value(false));
        mockMvc.perform(get("/api/admin/moderacion/videos/{id}", video).cookie(subAdmin))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.productor.id").value((int) tienda))
                .andExpect(jsonPath("$.puedeDecidir").value(true));
        // El subadministrador lo puede ver antes de decidir
        mockMvc.perform(get("/api/videos/{id}", video).cookie(subAdmin)).andExpect(status().isOk());

        decidir(video, "aprobar", "Muestra el trabajo de la comunidad.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.motivo").value("Muestra el trabajo de la comunidad."))
                .andExpect(jsonPath("$.puedeDecidir").value(false));

        mockMvc.perform(get("/api/videos/{id}", video))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, public"));
        mockMvc.perform(get("/api/tiendas/{id}", tienda))
                .andExpect(jsonPath("$.videos[0].id").value((int) video))
                .andExpect(jsonPath("$.videos[0].titulo").value("Cosecha de cacao"));
        assertThat(correos.ultimoPara(PRODUCTOR).orElseThrow().asunto()).startsWith("Publicamos tu video Cosecha de cacao");
        mockMvc.perform(get("/api/admin/mi-historial").param("tipo", "MODERACION").cookie(subAdmin))
                .andExpect(jsonPath("$.acciones[?(@.clave == 'MV-%d')].accion".formatted(video)).value("VIDEO_PUBLICADO"))
                .andExpect(jsonPath("$.acciones[?(@.clave == 'MV-%d')].objeto".formatted(video)).value("VIDEO"));

        decidir(video, "rechazar", "Otra vez").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIDEO_YA_RESUELTO"));
    }

    @Test
    void unVideoRechazadoNoSeVeYElProductorRecibeElMotivo() throws Exception {
        long video = id(subir("Cosecha de cacao", "cosecha.mp4", "video/mp4"));
        decidir(video, "rechazar", "  ").andExpect(status().isBadRequest());
        decidir(video, "rechazar", "Se ve el logo de otra marca.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"));
        mockMvc.perform(get("/api/videos/{id}", video)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tiendas/{id}", tienda)).andExpect(jsonPath("$.videos[*].id").value(not(hasItem((int) video))));
        assertThat(correos.ultimoPara(PRODUCTOR).orElseThrow().texto()).contains("Motivo: Se ve el logo de otra marca.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aviso WHERE tipo = 'DECISION_VIDEO' AND referencia_id = ?",
                Integer.class, video)).isOne();
    }

    @Test
    void soloElSubadministradorRevisaVideos() throws Exception {
        long video = id(subir("Cosecha de cacao", "cosecha.mp4", "video/mp4"));
        mockMvc.perform(get("/api/admin/moderacion/videos").cookie(loginAs("sudo@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/moderacion/videos/{id}/aprobar", video).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Yo mismo\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/moderacion/videos/{id}", 999_999).cookie(subAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VIDEO_NO_ENCONTRADO"));
    }

    // ---------- Ayudas ----------

    private ResultActions subir(String titulo, String recurso, String tipo) throws Exception {
        return mockMvc.perform(multipart("/api/productor/videos").file(archivo(recurso, tipo)).param("titulo", titulo)
                .cookie(productor));
    }

    private ResultActions decidir(long video, String accion, String motivo) throws Exception {
        return mockMvc.perform(post("/api/admin/moderacion/videos/{id}/{accion}", video, accion).cookie(subAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("motivo", motivo))));
    }

    private long id(ResultActions respuesta) throws Exception {
        return objectMapper.readTree(respuesta.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    static MockMultipartFile archivo(String recurso, String tipo) throws IOException {
        return new MockMultipartFile("archivo", recurso, tipo, recurso(recurso));
    }

    static byte[] recurso(String nombre) throws IOException {
        try (InputStream in = VideosTest.class.getResourceAsStream("/videos/" + nombre)) {
            return in.readAllBytes();
        }
    }
}
