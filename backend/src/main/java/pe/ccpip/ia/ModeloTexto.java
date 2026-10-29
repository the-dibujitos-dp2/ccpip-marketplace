package pe.ccpip.ia;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Modelo de lenguaje que responde un objeto JSON con un esquema fijo. La redaccion asistida
 * (CCPIP-48), el analisis de evidencias (CCPIP-50) y el asistente de catalogo (CCPIP-51) dependen
 * solo de esta interfaz: el proveedor (Gemini u OpenAI) se elige con {@code ccpip.ia.proveedor}
 * sin tocarlos.
 */
public interface ModeloTexto {

    /**
     * Envia una consulta y devuelve el objeto JSON que respondio el modelo.
     *
     * @param instrucciones reglas del sistema
     * @param datos         lo que escribio la persona; se envia como datos, no como instrucciones
     * @param esquema       esquema de la respuesta en el formato de Gemini (OBJECT, STRING, nullable);
     *                      cada proveedor lo traduce al suyo
     */
    JsonNode generar(String instrucciones, String datos, Map<String, Object> esquema, double temperatura);

    /** Nombre que se guarda junto a cada resultado: "gemini" u "openai". */
    String proveedor();

    /** La llamada no produjo un resultado usable. El mensaje nunca incluye la clave. */
    class Falla extends RuntimeException {
        public Falla(String mensaje) {
            super(mensaje);
        }
    }
}
