package pe.ccpip.traduccion;

import java.util.Map;

import pe.ccpip.comun.Idioma;

/**
 * Proveedor por defecto mientras el cliente no apruebe una estrategia de traduccion (CCPIP-147):
 * responde que no esta disponible (503) y el contenido se conserva en su original (RNF-34).
 */
public class TraductorNoConfigurado implements TraductorAutomatico {

    @Override
    public Map<String, String> traducir(Map<String, String> textos, Idioma origen, Idioma destino) {
        throw new TraduccionNoDisponible();
    }

    @Override
    public String proveedor() {
        return "ninguno";
    }

    @Override
    public boolean disponible() {
        return false;
    }
}
