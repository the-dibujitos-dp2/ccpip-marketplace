package pe.ccpip.traduccion;

import java.util.Map;

import pe.ccpip.comun.Idioma;

/**
 * CCPIP-147 (RF-168): traduccion automatica del contenido del productor. Quien traduce aun no esta
 * definido con el cliente ("Pendiente de definicion" en el prototipo), asi que no se impone un
 * proveedor: esta interfaz permite conectar el que se apruebe sin tocar el resto de la plataforma.
 * Lo que devuelve es una PROPUESTA: se guarda pendiente y solo se publica cuando el productor la
 * revisa (RNF-33).
 */
public interface TraductorAutomatico {

    /**
     * Traduce cada texto (clave → texto) del idioma de origen al de destino y devuelve las mismas
     * claves. Si el servicio no esta disponible lanza {@link TraduccionNoDisponible}.
     */
    Map<String, String> traducir(Map<String, String> textos, Idioma origen, Idioma destino);

    /** Nombre del proveedor configurado, para registrar de donde salio cada traduccion. */
    String proveedor();

    /** Falso cuando no hay proveedor: la pantalla avisa sin intentar traducir. */
    default boolean disponible() {
        return true;
    }
}
