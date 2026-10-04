package pe.ccpip.ia;

import java.util.Locale;

/**
 * Proveedor determinista SOLO para pruebas automatizadas y la suite de punta a punta: arma el
 * texto con las respuestas del productor, sin inventar nada. Permite probar toda la pantalla
 * de IA (generar, otra version, mas corto, aprobar) sin depender de un servicio externo.
 * Nunca se configura en el ambiente desplegado.
 */
public class AsistenteSimulado implements AsistenteRedaccion {

    @Override
    public Propuesta proponerDescripcion(DatosDescripcion datos) {
        String contenido = vacio(datos.contenido()) ? "" : " en presentación de " + datos.contenido();
        String lineas = datos.lineas().isEmpty() ? "" : " de la línea " + String.join(" y ", datos.lineas()).toLowerCase(Locale.ROOT);
        String texto = datos.nombre() + lineas + contenido + ", elaborado por " + datos.comunidad() + "."
                + (vacio(datos.notas()) ? "" : " " + datos.notas().trim());
        return new Propuesta(null, texto, proveedor());
    }

    @Override
    public Propuesta proponerHistoria(DatosHistoria datos) {
        String texto = switch (datos.variante()) {
            case CORTA -> oracion(datos.quienes()) + " " + oracion(datos.cuidado());
            case OTRA -> oracion(datos.significado()) + " " + oracion(datos.quienes()) + " " + oracion(datos.como())
                    + " " + oracion(datos.cuidado());
            case NORMAL -> oracion(datos.quienes()) + " " + oracion(datos.como()) + " " + oracion(datos.cuidado())
                    + " " + oracion(datos.significado());
        };
        return new Propuesta("La historia de " + datos.producto(), texto.trim().replaceAll("\\s+", " "), proveedor());
    }

    @Override
    public String proveedor() {
        return "simulado";
    }

    private static String oracion(String texto) {
        if (vacio(texto)) {
            return "";
        }
        String t = texto.trim();
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return t.endsWith(".") ? t : t + ".";
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }
}
