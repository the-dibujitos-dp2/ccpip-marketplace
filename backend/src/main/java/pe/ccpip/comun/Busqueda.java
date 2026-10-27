package pe.ccpip.comun;

/**
 * Texto de busqueda para un LIKE con ESCAPE '\': lo escrito por el usuario se busca tal cual, sin que
 * sus % o _ funcionen como comodines.
 */
public final class Busqueda {

    private Busqueda() {
    }

    /** "%texto%" con sus comodines escapados, o nulo si no hay nada que buscar. */
    public static String patron(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return "%" + texto.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
