package pe.ccpip.ia;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * CCPIP-144 (RF-113): evalua si una foto corresponde a la linea del producto. Es distinto de
 * mejorar la calidad (CCPIP-176) y solo recomienda: nunca bloquea la publicacion ni cambia la
 * foto (RF-137, RNF-39).
 *
 * Aqui se conectara el modelo propio entrenado que exige el requisito academico (RNF-48): una
 * implementacion nueva de esta interfaz que cargue el modelo y se elija con
 * {@code ccpip.ia.imagenes.proveedor}. Mientras el equipo lo define solo existen el simulador
 * ({@link ValidadorImagenSimulado}) y el proveedor no configurado (503).
 */
public interface ValidadorImagen {

    enum Veredicto { VALIDA, SOSPECHOSA }

    /**
     * {@code linea}: codigo de la linea que coincide (valida) o de la que parece ser (sospechosa).
     * {@code motivo}: explicacion corta para el registro; nunca se inventan caracteristicas.
     */
    record Resultado(Veredicto veredicto, String linea, String motivo) {
    }

    /** @param lineas codigos de las lineas del producto; la primera es la principal */
    Resultado validar(BufferedImage imagen, List<String> lineas);

    /** Nombre del proveedor configurado, para registrar quien evaluo cada foto. */
    String proveedor();
}
