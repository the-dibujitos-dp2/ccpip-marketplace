package pe.ccpip.imagenes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import pe.ccpip.support.IntegrationTest;

/** Portadas reales de las comunidades sembradas: una por comunidad, sin pisar la que suba el productor. */
class PortadasSembradasTest extends IntegrationTest {

    @Autowired
    private PortadasSembradas portadas;

    @BeforeEach
    void preparar() {
        jdbc.update("DELETE FROM imagen_mejora");
        jdbc.update("DELETE FROM imagen_validacion");
        jdbc.update("DELETE FROM imagen");
    }

    @Test
    void cadaComunidadSembradaRecibeSuPortadaUnaSolaVez() throws Exception {
        portadas.run(null);
        portadas.run(null);

        List<String> conPortada = jdbc.queryForList("""
                SELECT u.correo FROM imagen i
                JOIN productor p ON p.id = i.productor_id JOIN usuario u ON u.id = p.usuario_id
                WHERE i.tipo = 'PORTADA'""", String.class);
        assertThat(conPortada).containsExactlyInAnyOrderElementsOf(PortadasSembradas.PORTADAS.keySet());

        UUID una = jdbc.queryForObject("SELECT id FROM imagen WHERE tipo = 'PORTADA' LIMIT 1", UUID.class);
        mockMvc.perform(get("/api/imagenes/{id}", una)).andExpect(status().isOk());
    }

    @Test
    void noReemplazaLaPortadaQueSubioElProductor() throws Exception {
        long productor = jdbc.queryForObject("""
                SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id
                WHERE u.correo = 'productor@ccpip.test'""", Long.class);
        mockMvc.perform(multipart("/api/productor/imagenes")
                        .file(new MockMultipartFile("archivo", "propia.jpg", "image/jpeg", Imagenes.jpg(800, 300)))
                        .param("tipo", "PORTADA").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isCreated());
        UUID propia = jdbc.queryForObject("SELECT id FROM imagen WHERE productor_id = ? AND tipo = 'PORTADA'", UUID.class, productor);

        portadas.run(null);

        assertThat(jdbc.queryForObject("SELECT id FROM imagen WHERE productor_id = ? AND tipo = 'PORTADA'", UUID.class, productor))
                .isEqualTo(propia);
    }
}
