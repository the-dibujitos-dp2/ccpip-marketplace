package pe.ccpip.ia;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cliente minimo de la API de OpenAI (chat/completions), alternativa a {@link GeminiCliente} para
 * la IA de texto. Pide la respuesta con Structured Outputs en modo estricto: el modelo solo puede
 * devolver un objeto que cumple el esquema, igual que con Gemini.
 *
 * La clave vive solo en el servidor (variable CCPIP_OPENAI_API_KEY) y viaja en la cabecera
 * Authorization, nunca en la URL. Toda falla (sin respuesta a tiempo, error HTTP, respuesta
 * rechazada, cortada o mal formada) se reporta como {@link ModeloTexto.Falla} y la persona sigue a
 * mano (RNF-48).
 */
public class OpenAiCliente implements ModeloTexto {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCliente.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI endpoint;
    private final String clave;
    private final String modelo;
    private final Duration espera;

    /**
     * @param urlBase normalmente https://api.openai.com; configurable para pruebas
     * @param espera  tiempo maximo de una llamada completa (RNF-48)
     */
    public OpenAiCliente(String urlBase, String clave, String modelo, Duration espera) {
        String base = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.endpoint = URI.create(base + "/v1/chat/completions");
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
        return "openai";
    }

    @Override
    public JsonNode generar(String instrucciones, String datos, Map<String, Object> esquema, double temperatura) {
        Instant limite = Instant.now().plus(espera);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("model", modelo);
        cuerpo.put("messages", List.of(
                Map.of("role", "system", "content", instrucciones),
                Map.of("role", "user", "content", datos)));
        cuerpo.put("temperature", temperatura);
        cuerpo.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of("name", "respuesta", "strict", true, "schema", esquemaEstricto(esquema))));
        HttpResponse<String> respuesta = EnvioIa.enviar(http, pedido(cuerpo), limite, "OpenAI");
        // Algunos modelos (los de razonamiento) solo aceptan la temperatura por defecto
        if (respuesta.statusCode() == 400 && "temperature".equals(parametroConError(respuesta.body()))) {
            log.info("El modelo {} no acepta temperatura: se repite la consulta sin ella", modelo);
            cuerpo.remove("temperature");
            respuesta = EnvioIa.enviar(http, pedido(cuerpo), limite, "OpenAI");
        }
        if (respuesta.statusCode() / 100 != 2) {
            throw EnvioIa.fallo("OpenAI respondió " + respuesta.statusCode() + ": "
                    + GeminiCliente.recortar(respuesta.body(), 300));
        }
        return extraer(respuesta.body());
    }

    private HttpRequest.Builder pedido(Map<String, Object> cuerpo) {
        try {
            return HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + clave)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cuerpo), StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new Falla("No se pudo armar la consulta: " + e.getOriginalMessage());
        }
    }

    private static String parametroConError(String cuerpo) {
        try {
            return JSON.readTree(cuerpo).path("error").path("param").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /** Saca el JSON que el modelo escribio en choices[0].message.content. */
    static JsonNode extraer(String cuerpo) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(cuerpo);
        } catch (JsonProcessingException e) {
            throw EnvioIa.fallo("Respuesta de OpenAI que no es JSON");
        }
        JsonNode opcion = raiz.path("choices").path(0);
        JsonNode mensaje = opcion.path("message");
        String rechazo = mensaje.path("refusal").asText("");
        if (!rechazo.isBlank()) {
            throw EnvioIa.fallo("OpenAI rechazó la consulta: " + GeminiCliente.recortar(rechazo, 200));
        }
        String fin = opcion.path("finish_reason").asText("");
        if (!fin.isEmpty() && !"stop".equals(fin)) {
            throw EnvioIa.fallo("OpenAI terminó sin completar la respuesta: " + fin);
        }
        String texto = mensaje.path("content").asText("");
        if (texto.isBlank()) {
            throw EnvioIa.fallo("OpenAI no devolvió texto");
        }
        try {
            JsonNode resultado = JSON.readTree(texto);
            if (!resultado.isObject()) {
                throw EnvioIa.fallo("OpenAI no devolvió un objeto JSON");
            }
            return resultado;
        } catch (JsonProcessingException e) {
            throw EnvioIa.fallo("OpenAI devolvió un JSON mal formado");
        }
    }

    /**
     * Traduce un esquema en el formato de Gemini al JSON Schema estricto de OpenAI: tipos en
     * minuscula, "nullable" como ["tipo", "null"] y objetos cerrados. El modo estricto exige que
     * toda propiedad sea requerida, asi que la que era opcional pasa a admitir null.
     */
    static Map<String, Object> esquemaEstricto(Map<String, ?> esquema) {
        return convertir(esquema, false);
    }

    private static Map<String, Object> convertir(Map<?, ?> esquema, boolean opcional) {
        Map<String, Object> r = new LinkedHashMap<>();
        String tipo = String.valueOf(esquema.get("type")).toLowerCase(Locale.ROOT);
        boolean admiteNull = opcional || Boolean.TRUE.equals(esquema.get("nullable"));
        r.put("type", admiteNull ? List.of(tipo, "null") : tipo);
        if (esquema.get("enum") instanceof List<?> valores) {
            List<Object> enumerados = new ArrayList<>(valores);
            if (admiteNull) {
                enumerados.add(null);
            }
            r.put("enum", enumerados);
        }
        if (esquema.get("properties") instanceof Map<?, ?> propiedades) {
            List<?> requeridas = esquema.get("required") instanceof List<?> l ? l : List.of();
            Map<String, Object> convertidas = new LinkedHashMap<>();
            propiedades.forEach((nombre, sub) ->
                    convertidas.put(String.valueOf(nombre), convertir((Map<?, ?>) sub, !requeridas.contains(nombre))));
            r.put("properties", convertidas);
            r.put("required", List.copyOf(convertidas.keySet()));
            r.put("additionalProperties", false);
        }
        if (esquema.get("items") instanceof Map<?, ?> items) {
            r.put("items", convertir(items, false));
        }
        return r;
    }
}
