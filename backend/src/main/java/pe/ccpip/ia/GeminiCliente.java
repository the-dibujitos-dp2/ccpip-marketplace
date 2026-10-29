package pe.ccpip.ia;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cliente minimo de la API de Gemini (generateContent) para la redaccion asistida (CCPIP-48) y el
 * analisis de evidencias (CCPIP-50). Pide la respuesta en JSON con un esquema fijo, para que la
 * plataforma nunca dependa de interpretar texto libre.
 *
 * La clave vive solo en el servidor (variable CCPIP_GEMINI_API_KEY) y viaja en la cabecera
 * x-goog-api-key, nunca en la URL, para que no quede en registros de acceso. Toda falla (sin
 * respuesta dentro de la espera, error HTTP, respuesta bloqueada o mal formada) se reporta como
 * {@link ModeloTexto.Falla}; quien llama la convierte en {@link IaNoDisponible} y la persona sigue a
 * mano (RNF-48).
 */
public class GeminiCliente implements ModeloTexto {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final String clave;
    private final String modelo;
    private final Duration espera;

    /**
     * @param urlBase normalmente https://generativelanguage.googleapis.com; configurable para pruebas
     * @param espera  tiempo maximo de una llamada completa (RNF-48)
     */
    public GeminiCliente(String urlBase, String clave, String modelo, Duration espera) {
        String base = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.endpoint = URI.create(base + "/v1beta/models/" + modelo + ":generateContent");
        this.clave = clave;
        this.modelo = modelo;
        this.espera = espera;
        this.http = HttpClient.newBuilder().connectTimeout(espera).build();
    }

    public String modelo() {
        return modelo;
    }

    @Override
    public String proveedor() {
        return "gemini";
    }

    @Override
    public JsonNode generar(String instrucciones, String datos, Map<String, Object> esquema, double temperatura) {
        Map<String, Object> cuerpo = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", instrucciones))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", datos)))),
                "generationConfig", Map.of(
                        "temperature", temperatura,
                        "responseMimeType", "application/json",
                        "responseSchema", esquema));
        HttpRequest.Builder pedido;
        try {
            pedido = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", clave)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cuerpo), StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new Falla("No se pudo armar la consulta: " + e.getOriginalMessage());
        }
        HttpResponse<String> respuesta = EnvioIa.enviar(http, pedido, Instant.now().plus(espera), "Gemini");
        if (respuesta.statusCode() / 100 != 2) {
            throw EnvioIa.fallo("Gemini respondió " + respuesta.statusCode() + ": " + recortar(respuesta.body(), 300));
        }
        return extraer(respuesta.body());
    }

    /** Saca el JSON que el modelo escribio en candidates[0].content.parts[*].text. */
    static JsonNode extraer(String cuerpo) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(cuerpo);
        } catch (JsonProcessingException e) {
            throw EnvioIa.fallo("Respuesta de Gemini que no es JSON");
        }
        JsonNode bloqueo = raiz.path("promptFeedback").path("blockReason");
        if (!bloqueo.isMissingNode()) {
            throw EnvioIa.fallo("Gemini bloqueó la consulta: " + bloqueo.asText());
        }
        JsonNode candidato = raiz.path("candidates").path(0);
        String fin = candidato.path("finishReason").asText("");
        if (!fin.isEmpty() && !"STOP".equals(fin)) {
            throw EnvioIa.fallo("Gemini terminó sin completar la respuesta: " + fin);
        }
        StringBuilder texto = new StringBuilder();
        for (JsonNode parte : candidato.path("content").path("parts")) {
            texto.append(parte.path("text").asText(""));
        }
        if (texto.isEmpty()) {
            throw EnvioIa.fallo("Gemini no devolvió texto");
        }
        try {
            JsonNode resultado = JSON.readTree(texto.toString());
            if (!resultado.isObject()) {
                throw EnvioIa.fallo("Gemini no devolvió un objeto JSON");
            }
            return resultado;
        } catch (JsonProcessingException e) {
            throw EnvioIa.fallo("Gemini devolvió un JSON mal formado");
        }
    }

    /** Arma el texto de datos de la persona como JSON, para que no se lea como instrucciones. */
    static String comoDatos(Map<String, ?> datos) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(datos);
        } catch (JsonProcessingException e) {
            throw new Falla("No se pudo armar la consulta: " + e.getOriginalMessage());
        }
    }

    static String recortar(String texto, int maximo) {
        if (texto == null) {
            return "";
        }
        String t = texto.strip();
        return t.length() <= maximo ? t : t.substring(0, maximo).strip();
    }
}
