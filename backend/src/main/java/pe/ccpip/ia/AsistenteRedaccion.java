package pe.ccpip.ia;

import java.util.List;

/**
 * CCPIP-48: redaccion asistida. Interfaz propia para que el proveedor se pueda reemplazar sin
 * tocar el resto de la plataforma. Todo lo que devuelve es un BORRADOR: nunca se publica sin
 * que el productor lo revise y lo apruebe (RNF-29).
 */
public interface AsistenteRedaccion {

    /** Datos minimos del producto para proponer una descripcion. */
    record DatosDescripcion(String nombre, List<String> lineas, String contenido, String comunidad, String notas) {
    }

    /** Las cuatro preguntas de PW-05. */
    record DatosHistoria(String producto, String comunidad, String quienes, String como, String significado,
                         String cuidado, Variante variante) {
    }

    enum Variante { NORMAL, OTRA, CORTA }

    record Propuesta(String titulo, String texto, String proveedor) {
    }

    Propuesta proponerDescripcion(DatosDescripcion datos);

    Propuesta proponerHistoria(DatosHistoria datos);

    /** Nombre del proveedor configurado, para registrar de donde salio cada texto. */
    String proveedor();
}
