package pe.ccpip.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import pe.ccpip.ia.AsistenteCatalogo.Candidato;
import pe.ccpip.ia.AsistenteCatalogo.Interpretacion;
import pe.ccpip.ia.AsistenteCatalogo.Opcion;
import pe.ccpip.ia.AsistenteCatalogo.Respuesta;

/**
 * CCPIP-51 con Gemini contra un servidor local que imita la API. Lo importante: la IA solo puede
 * elegir lineas y regiones que existen, y solo puede citar productos que encontro la busqueda.
 */
class AsistenteCatalogoGeminiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Opcion> LINEAS = List.of(new Opcion("CAFE", "Café"), new Opcion("TEXTIL", "Textil"));
    private static final List<Opcion> REGIONES = List.of(new Opcion("Junín", "Junín"), new Opcion("Ucayali", "Ucayali"));

    private HttpServer servidor;
    private final AtomicReference<String> respuesta = new AtomicReference<>();
    private final AtomicReference<Integer> estado = new AtomicReference<>(200);
    private final AtomicReference<JsonNode> pedido = new AtomicReference<>();

    @BeforeEach
    void levantar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            pedido.set(JSON.readTree(intercambio.getRequestBody()));
            byte[] cuerpo = respuesta.get().getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado.get(), cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop(0);
    }

    private AsistenteCatalogoGemini asistente() {
        return new AsistenteCatalogoGemini(new GeminiCliente("http://127.0.0.1:" + servidor.getAddress().getPort(),
                "clave", "modelo-x", Duration.ofSeconds(5)));
    }

    private void responde(String jsonDelModelo) throws IOException {
        respuesta.set(JSON.writeValueAsString(JSON.createObjectNode()
                .set("candidates", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("finishReason", "STOP")
                        .set("content", JSON.createObjectNode().set("parts",
                                JSON.createArrayNode().add(JSON.createObjectNode().put("text", jsonDelModelo))))))));
    }

    @Test
    void laInterpretacionSoloConservaLineasYRegionesQueExisten() throws IOException {
        responde("""
                {"fueraDeAlcance": false, "terminos": ["café", "café", "molido", "tostado", "extra"],
                 "lineas": ["cafe", "AUTOS"], "regiones": ["junín", "Marte"], "modalidad": "minorista", "precioMax": 40}""");
        Interpretacion i = asistente().interpretar("café molido de Junín por menos de 40 soles", LINEAS, REGIONES);

        assertThat(i.fueraDeAlcance()).isFalse();
        assertThat(i.terminos()).containsExactly("café", "molido", "tostado");
        assertThat(i.lineas()).containsExactly("CAFE");
        assertThat(i.regiones()).containsExactly("Junín");
        assertThat(i.modalidad()).isEqualTo("MINORISTA");
        assertThat(i.precioMax()).isEqualByComparingTo("40");
        // La pregunta viaja como dato y las reglas prohiben inventar
        assertThat(pedido.get().at("/contents/0/parts/0/text").asText()).contains("café molido de Junín", "lineas_disponibles");
        assertThat(pedido.get().at("/systemInstruction/parts/0/text").asText()).contains("No inventes productos");
    }

    @Test
    void unaPreguntaAjenaQuedaFueraDeAlcanceYUnaModalidadRaraSeIgnora() throws IOException {
        responde("{\"fueraDeAlcance\": true, \"terminos\": [\"x\"], \"lineas\": [], \"regiones\": []}");
        assertThat(asistente().interpretar("escríbeme un poema", LINEAS, REGIONES).fueraDeAlcance()).isTrue();

        responde("{\"fueraDeAlcance\": false, \"terminos\": [], \"lineas\": [\"TEXTIL\"], \"regiones\": [], \"modalidad\": \"GRATIS\", \"precioMax\": -5}");
        Interpretacion i = asistente().interpretar("ponchos", LINEAS, REGIONES);
        assertThat(i.modalidad()).isNull();
        assertThat(i.precioMax()).isNull();
    }

    @Test
    void laRespuestaSoloCitaProductosEncontrados() throws IOException {
        responde("{\"texto\": \"Te recomiendo el café de la Coop. Río Ene (S/ 25).\", \"citados\": [10, 99, 10]}");
        List<Candidato> candidatos = List.of(
                new Candidato(10, "Cafe molido 250 g", "Café", "Coop. Río Ene", "Junín", "MINORISTA", new BigDecimal("25"), "250 g", true),
                new Candidato(11, "Cafe en grano", "Café", "Coop. Pangoa", "Junín", "AMBAS", new BigDecimal("30"), "500 g", true));
        Respuesta r = asistente().responder("café", candidatos, false);

        assertThat(r.texto()).contains("Río Ene");
        assertThat(r.citados()).containsExactly(10L);
        assertThat(pedido.get().at("/contents/0/parts/0/text").asText()).contains("productos_encontrados", "Coop. Pangoa", "español");
    }

    @Test
    void cualquierFallaDejaElAsistenteNoDisponible() throws IOException {
        estado.set(500);
        responde("{}");
        assertThatThrownBy(() -> asistente().interpretar("café", LINEAS, REGIONES)).isInstanceOf(IaNoDisponible.class);
        estado.set(200);
        responde("{\"texto\": \"\", \"citados\": []}");
        assertThatThrownBy(() -> asistente().responder("café", List.of(), true)).isInstanceOf(IaNoDisponible.class);
    }

    @Test
    void elSimuladoTomaPalabrasDeLaPreguntaYSinPalabrasQuedaFuera() {
        AsistenteCatalogoSimulado simulado = new AsistenteCatalogoSimulado();
        assertThat(simulado.interpretar("Busco café para regalar", LINEAS, REGIONES).terminos()).containsExactly("café");
        assertThat(simulado.interpretar("hola, ¿qué tal?", LINEAS, REGIONES).fueraDeAlcance()).isTrue();
    }

    @Test
    void elProveedorDelAsistenteSeEligeConLaConfiguracion() {
        IaConfig config = new IaConfig();
        assertThat(config.asistenteCatalogo("simulado")).isInstanceOf(AsistenteCatalogoSimulado.class);
        assertThat(config.asistenteCatalogo("gemini")).isInstanceOf(AsistenteCatalogoNoConfigurado.class);
        assertThatThrownBy(() -> config.asistenteCatalogo("ninguno").interpretar("café", LINEAS, REGIONES))
                .isInstanceOf(IaNoDisponible.class);
    }
}
