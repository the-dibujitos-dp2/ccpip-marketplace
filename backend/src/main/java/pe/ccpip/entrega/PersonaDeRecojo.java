package pe.ccpip.entrega;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Como se comparan los datos de quien recoge (CCPIP-71): el administrador los escribe a mano en el
 * mostrador, asi que no distinguen mayusculas, tildes ni espacios sobrantes, y el celular admite
 * "+51", espacios y guiones.
 */
public final class PersonaDeRecojo {

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACIOS = Pattern.compile("\\s+");
    private static final Pattern NO_DIGITOS = Pattern.compile("\\D");
    /** Celular peruano: 9 digitos que empiezan con 9. */
    private static final Pattern CELULAR = Pattern.compile("9\\d{8}");

    private PersonaDeRecojo() {
    }

    /** "  María  QUISPE " y "maria quispe" son la misma persona. */
    static boolean mismoNombre(String a, String b) {
        return a != null && b != null && !clave(a).isEmpty() && clave(a).equals(clave(b));
    }

    private static String clave(String nombre) {
        String sinTildes = MARCAS.matcher(Normalizer.normalize(nombre, Normalizer.Form.NFD)).replaceAll("");
        return ESPACIOS.matcher(sinTildes.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** Los 9 digitos del celular, o vacio si no es un celular valido. */
    public static Optional<String> celular(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String digitos = NO_DIGITOS.matcher(texto).replaceAll("");
        if (digitos.length() == 11 && digitos.startsWith("51")) {
            digitos = digitos.substring(2);
        }
        return CELULAR.matcher(digitos).matches() ? Optional.of(digitos) : Optional.empty();
    }
}
