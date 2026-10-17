package pe.ccpip.ia;

import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.List;

/**
 * SIMULADOR de la mejora de calidad (CCPIP-176), para pruebas y demo hasta conectar el modelo
 * definitivo. No usa IA: aplica dos ajustes globales clasicos, autocontraste por canal y una
 * nitidez leve. No agrega ni quita nada de la foto, asi que no puede inventar caracteristicas
 * ni certificados (RNF-46). Determinista.
 */
public class MejoradorImagenSimulado implements MejoradorImagen {

    /** Fraccion de pixeles que se ignora en cada extremo al estirar el contraste. */
    static final double RECORTE = 0.005;
    static final float NITIDEZ = 0.15f;

    private volatile boolean falla;

    @Override
    public Mejora mejorar(BufferedImage original) {
        if (falla) {
            throw IaNoDisponible.imagenes();
        }
        BufferedImage contraste = autocontraste(rgb(original));
        float k = NITIDEZ;
        Kernel kernel = new Kernel(3, 3, new float[] {0, -k, 0, -k, 1 + 4 * k, -k, 0, -k, 0});
        BufferedImage nitida = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null).filter(contraste, null);
        return new Mejora(nitida, List.of("AUTOCONTRASTE", "NITIDEZ"));
    }

    @Override
    public String proveedor() {
        return "simulado";
    }

    /** Solo pruebas: hace que el simulador responda como un servicio caido (503). */
    public void simularFalla(boolean falla) {
        this.falla = falla;
    }

    /** Estira cada canal para que ocupe de 0 a 255, ignorando los extremos raros. */
    static BufferedImage autocontraste(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixeles = img.getRGB(0, 0, w, h, null, 0, w);
        int[][] histograma = new int[3][256];
        for (int p : pixeles) {
            histograma[0][(p >> 16) & 0xff]++;
            histograma[1][(p >> 8) & 0xff]++;
            histograma[2][p & 0xff]++;
        }
        int[][] tabla = new int[3][256];
        long recorte = (long) (pixeles.length * RECORTE);
        for (int c = 0; c < 3; c++) {
            int bajo = limite(histograma[c], recorte, true);
            int alto = limite(histograma[c], recorte, false);
            for (int v = 0; v < 256; v++) {
                // Un canal plano (foto de un solo color) se deja como esta
                tabla[c][v] = alto <= bajo ? v : Math.clamp(Math.round((v - bajo) * 255f / (alto - bajo)), 0, 255);
            }
        }
        BufferedImage destino = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < pixeles.length; i++) {
            int p = pixeles[i];
            pixeles[i] = (tabla[0][(p >> 16) & 0xff] << 16) | (tabla[1][(p >> 8) & 0xff] << 8) | tabla[2][p & 0xff];
        }
        destino.setRGB(0, 0, w, h, pixeles, 0, w);
        return destino;
    }

    private static int limite(int[] histograma, long recorte, boolean desdeAbajo) {
        long acumulado = 0;
        for (int i = 0; i < 256; i++) {
            int v = desdeAbajo ? i : 255 - i;
            acumulado += histograma[v];
            if (acumulado > recorte) {
                return v;
            }
        }
        return desdeAbajo ? 0 : 255;
    }

    private static BufferedImage rgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage destino = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        destino.getGraphics().drawImage(img, 0, 0, null);
        return destino;
    }
}
