package pe.ccpip.ia;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Lo comun de los clientes HTTP de IA de texto: el endpoint del proveedor, la clave, el modelo y
 * el tope de espera de cada consulta (RNF-48). Cada proveedor arma su pedido y lee su respuesta.
 */
abstract class ClienteIaHttp implements ModeloTexto {

    protected final HttpClient http;
    protected final URI endpoint;
    protected final String clave;
    protected final String modelo;
    protected final Duration espera;

    /**
     * @param urlBase direccion del proveedor; configurable para pruebas
     * @param ruta    ruta de la API dentro de esa direccion
     * @param espera  tiempo maximo de una llamada completa (RNF-48)
     */
    protected ClienteIaHttp(String urlBase, String ruta, String clave, String modelo, Duration espera) {
        String base = urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase;
        this.endpoint = URI.create(base + ruta);
        this.clave = clave;
        this.modelo = modelo;
        this.espera = espera;
        this.http = HttpClient.newBuilder().connectTimeout(espera).build();
    }

    public String modelo() {
        return modelo;
    }
}
