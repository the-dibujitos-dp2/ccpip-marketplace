package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/** CCPIP-394: la base resuelve la carrera entre dos cambios hacia la misma direccion activa. */
class DireccionesGuardadasConcurrenciaTest extends IntegrationTest {

    private static final String RUTA = "/api/cuenta/direcciones";
    private static final String COMPRADOR = "consumidor@ccpip.test";

    private final List<Long> creadas = new ArrayList<>();

    @AfterEach
    void limpiar() {
        for (Long id : creadas) {
            jdbc.update("DELETE FROM direccion_guardada WHERE id = ?", id);
        }
    }

    @Test
    void dosDireccionesActivasCompitenPorLaMismaDireccionNormalizadaYUnaResponde409() throws Exception {
        long primera = crear("Calle Primera " + UUID.randomUUID());
        long segunda = crear("Calle Segunda " + UUID.randomUUID());
        Cookie sesion = loginAs(COMPRADOR);
        String destino = "Avenida Concurrente " + UUID.randomUUID();

        List<MvcResult> resultados = aLaVez(
                () -> modificar(sesion, primera, "  " + destino + "  "),
                () -> modificar(sesion, segunda, destino.toUpperCase()));

        assertThat(resultados).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
        MvcResult conflicto = resultados.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
        assertThat(objectMapper.readTree(conflicto.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("DIRECCION_YA_GUARDADA");
        assertThat(direccionesActivasNormalizadas(destino)).isEqualTo(1);
        assertThat(direccionesActivas(primera, segunda)).isEqualTo(2);
    }

    private long crear(String direccion) throws Exception {
        MvcResult resultado = mockMvc.perform(post(RUTA).cookie(loginAs(COMPRADOR)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("alias", "Casa", "ubigeo", "150122", "direccion", direccion))))
                .andReturn();
        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        long id = Long.parseLong(objectMapper.readTree(resultado.getResponse().getContentAsString()).get("id").asText());
        creadas.add(id);
        return id;
    }

    private MvcResult modificar(Cookie sesion, long id, String direccion) throws Exception {
        return mockMvc.perform(put(RUTA + "/{id}", id).cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("alias", "Casa", "ubigeo", "150122", "direccion", direccion))))
                .andReturn();
    }

    private List<MvcResult> aLaVez(Envio... envios) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(envios.length);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futuros = new ArrayList<>();
            for (Envio envio : envios) {
                futuros.add(hilos.submit(() -> {
                    largada.await();
                    return envio.enviar();
                }));
            }
            largada.countDown();
            List<MvcResult> resultados = new ArrayList<>();
            for (Future<MvcResult> futuro : futuros) {
                resultados.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            hilos.shutdownNow();
        }
    }

    private long direccionesActivasNormalizadas(String direccion) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM direccion_guardada d JOIN usuario u ON u.id = d.usuario_id
                WHERE u.correo = ? AND d.activa AND d.ubigeo = '150122' AND lower(d.direccion) = lower(?)""",
                Long.class, COMPRADOR, direccion);
    }

    private long direccionesActivas(long primera, long segunda) {
        return jdbc.queryForObject("SELECT count(*) FROM direccion_guardada WHERE activa AND id IN (?, ?)", Long.class,
                primera, segunda);
    }

    @FunctionalInterface
    private interface Envio {
        MvcResult enviar() throws Exception;
    }
}
