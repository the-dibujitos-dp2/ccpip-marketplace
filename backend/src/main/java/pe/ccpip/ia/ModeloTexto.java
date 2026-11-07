package pe.ccpip.ia;

import java.util.Base64;
import java.util.List;
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

    /**
     * CCPIP-491: lo mismo, con archivos que el modelo lee (por ahora, los PDF de las evidencias). Cada
     * adjunto va precedido de su {@code etiqueta}, para que el modelo sepa a que se refiere. Sin adjuntos
     * es la consulta de siempre.
     */
    default JsonNode generar(String instrucciones, String datos, List<Adjunto> adjuntos, Map<String, Object> esquema,
                             double temperatura) {
        if (adjuntos.isEmpty()) {
            return generar(instrucciones, datos, esquema, temperatura);
        }
        throw new Falla("El proveedor " + proveedor() + " no lee archivos adjuntos.");
    }

    /** Un archivo para el modelo, con la etiqueta que lo presenta y su tipo (application/pdf). */
    record Adjunto(String etiqueta, String nombre, String mime, byte[] contenido) {

        public String base64() {
            return Base64.getEncoder().encodeToString(contenido);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Adjunto a && etiqueta.equals(a.etiqueta) && java.util.Arrays.equals(contenido, a.contenido);
        }

        @Override
        public int hashCode() {
            return 31 * etiqueta.hashCode() + java.util.Arrays.hashCode(contenido);
        }

        @Override
        public String toString() {
            return "Adjunto[" + etiqueta + ", " + mime + ", " + contenido.length + " bytes]";
        }
    }

    /** Nombre que se guarda junto a cada resultado: "gemini" u "openai". */
    String proveedor();

    /** La llamada no produjo un resultado usable. El mensaje nunca incluye la clave. */
    class Falla extends RuntimeException {
        public Falla(String mensaje) {
            super(mensaje);
        }
    }
}
