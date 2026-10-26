package pe.ccpip.imagenes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import jakarta.servlet.http.Cookie;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-176, CCPIP-281 y CCPIP-282: pedir la mejora y decidirla son exclusivos por foto. Dos pedidos simultaneos
 * de mejora dejan una sola propuesta; dos aceptaciones, o aceptar y conservar la original a la vez, deciden una
 * sola vez y dejan la foto y la mejora coherentes. Sin @Transactional: cada solicitud confirma su propia
 * transaccion, como en produccion.
 */
class IaImagenesConcurrenciaTest extends IntegrationTest {

    @Autowired
    private IaImagenService ia;

    private Cookie sesion;
    private long producto;
    private long productor;
    private long usuario;

    @BeforeEach
    void preparar() throws Exception {
        limpiar();
        sesion = loginAs("productor@ccpip.test");
        producto = jdbc.queryForObject("SELECT id FROM producto WHERE nombre = 'Aceite de ungurahui 250 ml'", Long.class);
        usuario = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = 'productor@ccpip.test'", Long.class);
        productor = jdbc.queryForObject("SELECT id FROM productor WHERE usuario_id = ?", Long.class, usuario);
    }

    @AfterEach
    void limpiar() {
        jdbc.update("DELETE FROM imagen_mejora");
        jdbc.update("DELETE FROM imagen_validacion");
        jdbc.update("DELETE FROM imagen");
    }

    /** Sube una foto apagada del producto, que el mejorador simulado si cambia. */
    private UUID foto() throws Exception {
        String cuerpo = mockMvc.perform(multipart("/api/productor/imagenes")
                        .file(new MockMultipartFile("archivo", "foto", "application/octet-stream", Imagenes.apagada(400, 300)))
                        .param("tipo", "PRODUCTO").param("productoId", String.valueOf(producto)).cookie(sesion))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(cuerpo).get("id").asText());
    }

    /** Una foto con su mejora ya pendiente. */
    private UUID fotoConMejora() throws Exception {
        UUID foto = foto();
        mockMvc.perform(post("/api/productor/imagenes/{id}/mejora", foto).cookie(sesion)).andExpect(status().isCreated());
        return foto;
    }

    private int contar(String sql, Object... parametros) {
        return jdbc.queryForObject(sql, Integer.class, parametros);
    }

    @Test
    void dosPedidosDeMejoraALaVezDejanUnaSolaPropuesta() throws Exception {
        UUID foto = foto();

        List<Object> resultados = aLaVez(
                () -> ia.mejorar(productor, usuario, foto),
                () -> ia.mejorar(productor, usuario, foto));

        // Los dos reciben la misma propuesta: ninguno falla
        assertThat(resultados).allMatch(IaImagenService.Comparacion.class::isInstance);
        assertThat(resultados).extracting(r -> ((IaImagenService.Comparacion) r).mejoraId()).containsOnly(
                ((IaImagenService.Comparacion) resultados.get(0)).mejoraId());
        assertThat(contar("SELECT count(*) FROM imagen_mejora WHERE imagen_id = ?", foto)).isEqualTo(1);
    }

    @Test
    void dosAceptacionesALaVezReemplazanLaFotoUnaSolaVez() throws Exception {
        UUID foto = fotoConMejora();

        List<Object> resultados = aLaVez(
                () -> ia.aceptar(productor, usuario, foto),
                () -> ia.aceptar(productor, usuario, foto));

        assertThat(resultados).filteredOn(ImagenService.Imagen.class::isInstance).hasSize(1);
        assertThat(resultados).filteredOn(ErrorNegocio.class::isInstance).hasSize(1);
        // Una sola foto nueva vigente en el lugar de la original, y la mejora la apunta
        assertThat(contar("SELECT count(*) FROM imagen WHERE producto_id = ? AND retirada_en IS NULL", producto)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM imagen WHERE producto_id = ?", producto)).isEqualTo(2);
        assertThat(contar("""
                SELECT count(*) FROM imagen_mejora m JOIN imagen i ON i.id = m.imagen_resultante
                WHERE m.imagen_id = ? AND m.estado = 'ACEPTADA' AND i.retirada_en IS NULL""", foto)).isEqualTo(1);
    }

    @Test
    void aceptarYConservarLaOriginalALaVezDecidenUnaSolaVez() throws Exception {
        UUID foto = fotoConMejora();

        List<Object> resultados = aLaVez(
                () -> ia.aceptar(productor, usuario, foto),
                () -> {
                    ia.rechazar(productor, usuario, foto);
                    return "RECHAZADA";
                });

        // Gana una de las dos decisiones; la otra se rechaza sin cambiar nada
        assertThat(resultados).filteredOn(ErrorNegocio.class::isInstance).hasSize(1);
        String estado = jdbc.queryForObject("SELECT estado FROM imagen_mejora WHERE imagen_id = ?", String.class, foto);
        boolean originalVigente = jdbc.queryForObject("SELECT retirada_en IS NULL FROM imagen WHERE id = ?", Boolean.class, foto);
        if ("ACEPTADA".equals(estado)) {
            assertThat(originalVigente).isFalse();
            assertThat(contar("SELECT count(*) FROM imagen WHERE producto_id = ?", producto)).isEqualTo(2);
        } else {
            assertThat(estado).isEqualTo("RECHAZADA");
            assertThat(originalVigente).isTrue();
            assertThat(contar("SELECT count(*) FROM imagen WHERE producto_id = ?", producto)).isEqualTo(1);
        }
    }

    /** Lanza las solicitudes al mismo tiempo y devuelve lo que respondio cada una, o la excepcion que lanzo. */
    private List<Object> aLaVez(Callable<?>... solicitudes) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(solicitudes.length);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Object>> futuros = new ArrayList<>();
            for (Callable<?> solicitud : solicitudes) {
                futuros.add(hilos.submit(() -> {
                    largada.await();
                    try {
                        return solicitud.call();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            largada.countDown();
            List<Object> resultados = new ArrayList<>();
            for (Future<Object> f : futuros) {
                resultados.add(f.get(60, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            hilos.shutdownNow();
        }
    }
}
