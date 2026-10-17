package pe.ccpip.ia;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * CCPIP-176 (RF-145): mejora opcional de la calidad de una foto. Solo devuelve una VARIANTE: la
 * original no se toca y la variante no se publica hasta que el productor la acepta (RF-182,
 * RNF-24). Una implementacion solo puede hacer ajustes globales (luz, contraste, nitidez): no
 * agrega objetos, textos, sellos ni certificados, para conservar la autenticidad (RNF-46).
 *
 * Aqui se conectara el modelo definitivo que elija el equipo, como otra implementacion
 * seleccionada con {@code ccpip.ia.imagenes.proveedor}.
 */
public interface MejoradorImagen {

    /** @param ajustes codigos de lo que se aplico, p. ej. AUTOCONTRASTE y NITIDEZ */
    record Mejora(BufferedImage imagen, List<String> ajustes) {
    }

    Mejora mejorar(BufferedImage original);

    String proveedor();
}
