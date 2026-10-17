package pe.ccpip.traduccion;

import java.util.LinkedHashMap;
import java.util.Map;

import pe.ccpip.comun.Idioma;

/**
 * Proveedor determinista SOLO para pruebas automatizadas y la suite de punta a punta: no traduce,
 * antepone una marca visible al texto original para que nunca se confunda con una traduccion real.
 * Permite probar el flujo completo (traducir, revisar, publicar, invalidar) sin un servicio externo.
 * Nunca se configura en el ambiente desplegado (CCPIP-147).
 */
public class TraductorSimulado implements TraductorAutomatico {

    @Override
    public Map<String, String> traducir(Map<String, String> textos, Idioma origen, Idioma destino) {
        Map<String, String> traducidos = new LinkedHashMap<>();
        textos.forEach((clave, texto) -> traducidos.put(clave, marca(destino) + texto.trim()));
        return traducidos;
    }

    @Override
    public String proveedor() {
        return "simulado";
    }

    /** "[simulado en] ": la marca que lleva todo texto de este proveedor. */
    public static String marca(Idioma destino) {
        return "[simulado " + destino.codigo() + "] ";
    }
}
