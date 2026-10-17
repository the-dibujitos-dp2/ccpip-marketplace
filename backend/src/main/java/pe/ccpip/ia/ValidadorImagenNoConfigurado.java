package pe.ccpip.ia;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Proveedor por defecto mientras no se conecte el modelo definitivo: responde 503 y el productor
 * sigue con su foto original, que nunca se bloquea ni se toca (CCPIP-168).
 */
public class ValidadorImagenNoConfigurado implements ValidadorImagen {

    @Override
    public Resultado validar(BufferedImage imagen, List<String> lineas) {
        throw IaNoDisponible.imagenes();
    }

    @Override
    public String proveedor() {
        return "ninguno";
    }
}
