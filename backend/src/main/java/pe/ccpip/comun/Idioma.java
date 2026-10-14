package pe.ccpip.comun;

import java.util.Arrays;
import java.util.List;
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

    /**
     * El idioma que pide una cabecera Accept-Language ("en-US,en;q=0.9,es;q=0.8"): el primero
     * soportado segun su preferencia. Sin cabecera, con una mal formada o sin idiomas soportados,
     * espanol, que es el original de todo el contenido (CCPIP-166).
     */
    public static Idioma deCabecera(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return ES;
        }
        List<Locale.LanguageRange> preferidos;
        try {
            preferidos = Locale.LanguageRange.parse(acceptLanguage);
        } catch (IllegalArgumentException e) {
            return ES;
        }
        return preferidos.stream()
                .filter(r -> r.getWeight() > 0)
                .map(r -> r.getRange().split("-")[0])
                .map(Idioma::deCodigo)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(ES);
    }
}
