package pe.ccpip.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import pe.ccpip.ia.AnalistaEvidencias.Analisis;
import pe.ccpip.ia.AnalistaEvidencias.Evidencia;
import pe.ccpip.ia.AnalistaEvidencias.Solicitud;
import pe.ccpip.ia.AsistenteCatalogo.Interpretacion;
import pe.ccpip.ia.AsistenteCatalogo.Opcion;
import pe.ccpip.ia.AsistenteRedaccion.DatosHistoria;
import pe.ccpip.ia.AsistenteRedaccion.Propuesta;
import pe.ccpip.ia.AsistenteRedaccion.Variante;

/**
 * CCPIP-48, CCPIP-50 y CCPIP-51 con OpenAI, contra un servidor HTTP local que imita la API: no se
 * llama a OpenAI en las pruebas. Verifica que la clave viaje en la cabecera, que el esquema se pida
 * en modo estricto, que toda falla termine en "IA no disponible" y que una saturacion se reintente
 * una vez dentro de la misma espera.
 */
class OpenAiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLAVE = "sk-clave-de-prueba";

    private record Respuesta(int estado, String cuerpo, Map<String, String> cabeceras) {
    }

    private HttpServer servidor;
    /** Respuestas en orden; la ultima se repite. */
    private final Deque<Respuesta> respuestas = new ArrayDeque<>();
    private final AtomicReference<Long> demora = new AtomicReference<>(0L);
    private final List<JsonNode> pedidos = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> ruta = new AtomicReference<>();
    private final AtomicReference<String> autorizacion = new AtomicReference<>();

    @BeforeEach
    void levantar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            ruta.set(intercambio.getRequestURI().toString());
            autorizacion.set(intercambio.getRequestHeaders().getFirst("Authorization"));
            pedidos.add(JSON.readTree(intercambio.getRequestBody()));
            try {
                Thread.sleep(demora.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Respuesta r;
            synchronized (respuestas) {
                r = respuestas.size() > 1 ? respuestas.poll() : respuestas.peek();
            }
            byte[] cuerpo = r.cuerpo().getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            r.cabeceras().forEach((k, v) -> intercambio.getResponseHeaders().add(k, v));
            intercambio.sendResponseHeaders(r.estado(), cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop(0);
    }

    private OpenAiCliente cliente(Duration espera) {
        return new OpenAiCliente("http://127.0.0.1:" + servidor.getAddress().getPort(), CLAVE, "modelo-x", espera);
    }

    /** Respuesta de chat/completions cuyo contenido es el JSON indicado. */
    private static String completado(String jsonDelModelo) throws IOException {
        return JSON.writeValueAsString(JSON.createObjectNode()
                .set("choices", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("finish_reason", "stop")
                        .set("message", JSON.createObjectNode()
                                .put("role", "assistant")
                                .put("content", jsonDelModelo)
                                .putNull("refusal")))));
    }

    private void responde(String jsonDelModelo) throws IOException {
        encola(200, completado(jsonDelModelo));
    }

    private void encola(int estado, String cuerpo) {
        encola(estado, cuerpo, Map.of());
    }

    private void encola(int estado, String cuerpo, Map<String, String> cabeceras) {
        synchronized (respuestas) {
            respuestas.add(new Respuesta(estado, cuerpo, cabeceras));
        }
    }

    private final DatosHistoria historia = new DatosHistoria("Aceite de sacha inchi", "Asoc. Nii Joi",
            "34 mujeres shipibo", "prensamos en frio", "nos permite ganar sin talar", "no quemamos ni talamos", Variante.NORMAL);

    private static final String HISTORIA_OK = "{\"titulo\": \"Aceite que cuida el bosque\", \"texto\": \"Prensado en frío.\"}";

    @Test
    void laHistoriaSePideConLaClaveEnLaCabeceraYConEsquemaEstricto() throws IOException {
        responde(HISTORIA_OK);
        Propuesta p = new AsistenteGemini(cliente(Duration.ofSeconds(5))).proponerHistoria(historia);

        assertThat(p.titulo()).isEqualTo("Aceite que cuida el bosque");
        assertThat(p.texto()).isEqualTo("Prensado en frío.");
        assertThat(p.proveedor()).isEqualTo("openai");
        assertThat(ruta.get()).isEqualTo("/v1/chat/completions").doesNotContain(CLAVE);
        assertThat(autorizacion.get()).isEqualTo("Bearer " + CLAVE);
        JsonNode cuerpo = pedidos.get(0);
        assertThat(cuerpo.path("model").asText()).isEqualTo("modelo-x");
        assertThat(cuerpo.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(cuerpo.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(cuerpo.at("/messages/0/content").asText()).contains("No inventes origen");
        assertThat(cuerpo.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(cuerpo.at("/messages/1/content").asText()).contains("34 mujeres shipibo", "Asoc. Nii Joi");
        JsonNode formato = cuerpo.path("response_format");
        assertThat(formato.path("type").asText()).isEqualTo("json_schema");
        assertThat(formato.at("/json_schema/strict").asBoolean()).isTrue();
        JsonNode esquema = formato.at("/json_schema/schema");
        assertThat(esquema.path("type").asText()).isEqualTo("object");
        assertThat(esquema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(esquema.at("/properties/texto/type").asText()).isEqualTo("string");
        assertThat(esquema.path("required").toString()).contains("titulo", "texto");
    }

    @Test
    void elEsquemaEstrictoVuelveRequeridoLoOpcionalYAdmiteNull() {
        Map<String, Object> esquema = OpenAiCliente.esquemaEstricto(Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "recomendacion", Map.of("type", "STRING", "enum", List.of("SI", "NO")),
                        "hallazgos", Map.of("type", "ARRAY", "items", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "id", Map.of("type", "STRING", "nullable", true),
                                        "detalle", Map.of("type", "STRING")),
                                "required", List.of("detalle"))),
                        "precioMax", Map.of("type", "NUMBER", "nullable", true),
                        "modalidad", Map.of("type", "STRING", "enum", List.of("A", "B"))),
                "required", List.of("recomendacion", "hallazgos")));
        JsonNode e = JSON.valueToTree(esquema);

        assertThat(e.path("required")).hasSize(4);
        assertThat(e.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(e.at("/properties/recomendacion/type").asText()).isEqualTo("string");
        assertThat(e.at("/properties/recomendacion/enum").toString()).isEqualTo("[\"SI\",\"NO\"]");
        assertThat(e.at("/properties/precioMax/type").toString()).isEqualTo("[\"number\",\"null\"]");
        // Opcional en Gemini: en modo estricto pasa a requerida y admite null, tambien en su enum
        assertThat(e.at("/properties/modalidad/type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(e.at("/properties/modalidad/enum").toString()).isEqualTo("[\"A\",\"B\",null]");
        JsonNode hallazgo = e.at("/properties/hallazgos/items");
        assertThat(hallazgo.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(hallazgo.path("required").toString()).contains("id", "detalle");
        assertThat(hallazgo.at("/properties/id/type").toString()).isEqualTo("[\"string\",\"null\"]");
        assertThat(hallazgo.at("/properties/detalle/type").asText()).isEqualTo("string");
    }

    @Test
    void elAnalisisYElCatalogoFuncionanIgualConOpenAi() throws IOException {
        UUID certificado = UUID.randomUUID();
        Solicitud solicitud = new Solicitud("Café orgánico", "Coop. Río Ene", LocalDate.of(2026, 10, 22), List.of(
                new Evidencia(certificado, "CERTIFICADO", "Certificado orgánico", "Control Union",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "PDF")));
        responde("""
                {"recomendacion": "REVISAR", "resumen": "Falta el proceso.", "hallazgos": [
                  {"evidencia_id": null, "tipo": "FALTANTE", "detalle": "Faltan fotos del proceso."}]}""");
        Analisis a = new AnalistaGemini(cliente(Duration.ofSeconds(5))).analizar(solicitud);
        assertThat(a.hallazgos()).hasSize(1);
        assertThat(new AnalistaGemini(cliente(Duration.ofSeconds(5))).proveedor()).isEqualTo("openai");
        assertThat(pedidos.get(0).at("/messages/1/content").asText()).contains(certificado.toString());

        respuestas.clear();
        responde("""
                {"fueraDeAlcance": false, "terminos": ["café"], "lineas": ["CAFE"], "regiones": [],
                 "modalidad": null, "precioMax": null}""");
        Interpretacion i = new AsistenteCatalogoGemini(cliente(Duration.ofSeconds(5)))
                .interpretar("café", List.of(new Opcion("CAFE", "Café")), List.of());
        assertThat(i.lineas()).containsExactly("CAFE");
        assertThat(i.modalidad()).isNull();
    }

    @Test
    void siElModeloNoAceptaTemperaturaSeRepiteSinElla() throws IOException {
        encola(400, """
                {"error": {"message": "Unsupported value: 'temperature' does not support 0.7 with this model.",
                 "type": "invalid_request_error", "param": "temperature", "code": "unsupported_value"}}""");
        responde(HISTORIA_OK);
        Propuesta p = new AsistenteGemini(cliente(Duration.ofSeconds(5))).proponerHistoria(historia);

        assertThat(p.texto()).isEqualTo("Prensado en frío.");
        assertThat(pedidos).hasSize(2);
        assertThat(pedidos.get(0).has("temperature")).isTrue();
        assertThat(pedidos.get(1).has("temperature")).isFalse();
    }

    @Test
    void unaSaturacionSeReintentaUnaVezDentroDeLaEspera() throws IOException {
        encola(503, "{\"error\": {\"message\": \"overloaded\"}}");
        responde(HISTORIA_OK);
        Propuesta p = new AsistenteGemini(cliente(Duration.ofSeconds(5))).proponerHistoria(historia);
        assertThat(p.texto()).isEqualTo("Prensado en frío.");
        assertThat(pedidos).hasSize(2);
    }

    @Test
    void siLaSaturacionSigueOElTiempoNoAlcanzaLaIaNoEstaDisponible() {
        encola(429, "{\"error\": {\"message\": \"cuota\"}}");
        AsistenteGemini asistente = new AsistenteGemini(cliente(Duration.ofSeconds(5)));
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);
        assertThat(pedidos).hasSize(2);

        // Retry-After mas largo que la espera que queda: no se reintenta
        pedidos.clear();
        respuestas.clear();
        encola(503, "{}", Map.of("Retry-After", "30"));
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);
        assertThat(pedidos).hasSize(1);
    }

    @Test
    void siOpenAiNoRespondeATiempoLaIaNoEstaDisponible() throws IOException {
        responde(HISTORIA_OK);
        demora.set(1500L);
        AsistenteGemini asistente = new AsistenteGemini(cliente(Duration.ofMillis(300)));
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);
    }

    @Test
    void unRechazoUnaRespuestaCortadaOMalFormadaDejanLaIaNoDisponible() throws IOException {
        AsistenteGemini asistente = new AsistenteGemini(cliente(Duration.ofSeconds(5)));

        encola(401, "{\"error\": {\"message\": \"Incorrect API key\"}}");
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);

        respuestas.clear();
        encola(200, JSON.writeValueAsString(JSON.createObjectNode().set("choices", JSON.createArrayNode().add(
                JSON.createObjectNode().put("finish_reason", "stop").set("message",
                        JSON.createObjectNode().putNull("content").put("refusal", "No puedo ayudar con eso."))))));
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);

        respuestas.clear();
        encola(200, completado(HISTORIA_OK).replace("\"stop\"", "\"length\""));
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);

        respuestas.clear();
        responde("esto no es json");
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);
    }

    @Test
    void elProveedorSeEligeConLaConfiguracionYSinClaveQuedaNinguno() {
        IaConfig conClave = new IaConfig("", "g", "http://127.0.0.1:1", Duration.ofSeconds(1),
                "sk-abc", "modelo-y", "http://127.0.0.1:1", Duration.ofSeconds(1));
        assertThat(conClave.asistenteRedaccion("openai").proveedor()).isEqualTo("openai");
        assertThat(conClave.analistaEvidencias("openai").proveedor()).isEqualTo("openai");
        assertThat(conClave.asistenteCatalogo("openai").proveedor()).isEqualTo("openai");
        // La clave de OpenAI no habilita Gemini
        assertThat(conClave.asistenteRedaccion("gemini").proveedor()).isEqualTo("ninguno");
        // OpenAI es solo de texto: las imagenes usan el modelo propio
        assertThat(conClave.validadorImagen("openai").proveedor()).isEqualTo("ninguno");
        assertThat(conClave.mejoradorImagen("openai").proveedor()).isEqualTo("ninguno");

        IaConfig sinClave = new IaConfig("", "g", "http://127.0.0.1:1", Duration.ofSeconds(1),
                " ", "modelo-y", "http://127.0.0.1:1", Duration.ofSeconds(1));
        assertThat(sinClave.asistenteRedaccion("openai").proveedor()).isEqualTo("ninguno");
        assertThat(sinClave.analistaEvidencias("openai").proveedor()).isEqualTo("ninguno");
    }
}
