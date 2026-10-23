package pe.ccpip.ia;

import java.math.BigDecimal;
import java.util.List;

/**
 * CCPIP-51 y CCPIP-175 (RF-75, RF-167): asistente de catalogo. Trabaja en dos pasos para que nunca
 * responda con productos inventados:
 * <ol>
 *   <li>{@link #interpretar}: convierte la pregunta en criterios de la busqueda normal (terminos,
 *       lineas, regiones, modalidad, precio). La busqueda la hace el catalogo, no la IA.</li>
 *   <li>{@link #responder}: redacta la respuesta usando SOLO los productos reales que encontro la
 *       busqueda y dice cuales cita. Los ids que no esten entre los candidatos se descartan.</li>
 * </ol>
 */
public interface AsistenteCatalogo {

    /** Una linea o region que existe en el catalogo, para que la IA solo elija entre ellas. */
    record Opcion(String codigo, String nombre) {
    }

    /**
     * Criterios para la busqueda normal. {@code fueraDeAlcance}: la pregunta no es sobre productos
     * del catalogo (tareas, otros temas); entonces no se busca nada.
     */
    record Interpretacion(List<String> terminos, List<String> lineas, List<String> regiones, String modalidad,
                          BigDecimal precioMax, boolean fueraDeAlcance) {

        public static Interpretacion fuera() {
            return new Interpretacion(List.of(), List.of(), List.of(), null, null, true);
        }
    }

    /** Lo que la IA puede saber de cada producto encontrado: solo datos publicos del catalogo. */
    record Candidato(long id, String nombre, String linea, String comunidad, String region, String modalidad,
                     BigDecimal precio, String presentacion, boolean disponible) {
    }

    /** {@code citados}: ids de los candidatos que la respuesta recomienda, en cualquier orden. */
    record Respuesta(String texto, List<Long> citados) {
    }

    Interpretacion interpretar(String pregunta, List<Opcion> lineas, List<Opcion> regiones);

    Respuesta responder(String pregunta, List<Candidato> candidatos, boolean ingles);

    String proveedor();
}
