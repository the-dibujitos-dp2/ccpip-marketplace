package pe.ccpip.comun;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Idiomas de la plataforma (RNF-44). El espanol es el original de todo el contenido; el ingles
 * es la traduccion aprobada cuando existe (CCPIP-98, CCPIP-265).
 */
public enum Idioma {
    ES, EN;

    /** Codigo que viaja en la API y se guarda en la base: "es" o "en". */
    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** El idioma de un codigo "es" o "en", sin distinguir mayusculas. Vacio si no es soportado. */
    public static Optional<Idioma> deCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        String limpio = codigo.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(i -> i.codigo().equals(limpio)).findFirst();
    }
}
