package pe.ccpip.ia;

import java.awt.image.BufferedImage;

/** Sin mejorador configurado: 503 y se conserva la foto original (CCPIP-176). */
public class MejoradorImagenNoConfigurado implements MejoradorImagen {

    @Override
    public Mejora mejorar(BufferedImage original) {
        throw IaNoDisponible.imagenes();
    }

    @Override
    public String proveedor() {
        return "ninguno";
    }
}
