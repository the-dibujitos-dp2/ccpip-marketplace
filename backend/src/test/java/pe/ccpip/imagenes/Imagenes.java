package pe.ccpip.imagenes;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import javax.imageio.ImageIO;

/** Imagenes generadas para las pruebas, sin archivos binarios en el repositorio. */
public final class Imagenes {

    /** WebP con perdida de 1x1 pixel (el mas pequeno valido). */
    public static final byte[] WEBP = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

    private Imagenes() {
    }

    public static byte[] jpg(int ancho, int alto) {
        return codificar(lienzo(ancho, alto, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    public static byte[] png(int ancho, int alto) {
        return codificar(lienzo(ancho, alto, BufferedImage.TYPE_INT_ARGB), "png");
    }

    /** Mitad izquierda roja y derecha azul: permite verificar hacia donde se giro la imagen. */
    static BufferedImage lienzo(int ancho, int alto, int tipo) {
        BufferedImage img = new BufferedImage(ancho, alto, tipo);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, ancho / 2, alto);
        g.setColor(Color.BLUE);
        g.fillRect(ancho / 2, 0, ancho - ancho / 2, alto);
        g.dispose();
        return img;
    }

    static byte[] codificar(BufferedImage img, String formato) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, formato, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
