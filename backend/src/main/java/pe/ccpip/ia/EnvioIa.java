package pe.ccpip.ia;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Envio HTTP comun de los proveedores de texto. Toda la consulta, reintentos incluidos, respeta un
 * solo limite de tiempo (RNF-48). Si el proveedor esta saturado (429 o 503) se reintenta una vez,
 * solo si alcanza el tiempo: asi una saturacion de un segundo no deja al productor sin IA.
 */
final class EnvioIa {

    private static final Logger log = LoggerFactory.getLogger(EnvioIa.class);

    /** Pausa antes del reintento cuando el proveedor no indica Retry-After. */
    static final Duration PAUSA = Duration.ofSeconds(1);
    /** Por debajo de esto no vale la pena reintentar: la respuesta no llegaria a tiempo. */
    static final Duration MINIMO_PARA_REINTENTAR = Duration.ofSeconds(1);

    private EnvioIa() {
    }

    /**
     * @param nombre nombre del proveedor para los mensajes, p. ej. "Gemini"
     * @return la respuesta, con cualquier codigo HTTP; quien llama decide si es usable
     */
    static HttpResponse<String> enviar(HttpClient http, HttpRequest.Builder pedido, Instant limite, String nombre) {
        HttpResponse<String> respuesta = una(http, pedido, limite, nombre);
        int estado = respuesta.statusCode();
        if (estado != 429 && estado != 503) {
            return respuesta;
        }
        Duration pausa = pausa(respuesta);
        if (Duration.between(Instant.now().plus(pausa), limite).compareTo(MINIMO_PARA_REINTENTAR) < 0) {
            return respuesta;
        }
        log.info("{} respondió {}: se reintenta una vez", nombre, estado);
        try {
            Thread.sleep(pausa);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fallo("Consulta a " + nombre + " interrumpida");
        }
        return una(http, pedido, limite, nombre);
    }

    private static HttpResponse<String> una(HttpClient http, HttpRequest.Builder pedido, Instant limite, String nombre) {
        Duration resta = Duration.between(Instant.now(), limite);
        if (resta.isNegative() || resta.isZero()) {
            throw fallo(nombre + " no respondió a tiempo");
        }
        try {
            return http.send(pedido.timeout(resta).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw fallo(nombre + " no respondió a tiempo");
        } catch (IOException e) {
            throw fallo("No se pudo conectar con " + nombre + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fallo("Consulta a " + nombre + " interrumpida");
        }
    }

    /** Retry-After en segundos si el proveedor lo envia; si no, la pausa por defecto. */
    private static Duration pausa(HttpResponse<String> respuesta) {
        return respuesta.headers().firstValue("Retry-After")
                .map(String::strip)
                .filter(s -> s.matches("\\d{1,4}"))
                .map(s -> Duration.ofSeconds(Long.parseLong(s)))
                .orElse(PAUSA);
    }

    static ModeloTexto.Falla fallo(String mensaje) {
        log.warn("IA no disponible: {}", mensaje);
        return new ModeloTexto.Falla(mensaje);
    }
}
