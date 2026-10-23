package pe.ccpip.ia;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * CCPIP-176 (RF-145): mejora propia de la calidad de una foto, dentro del backend. A proposito no
 * es generativa: solo aplica ajustes globales (balance de blancos, autocontraste por canal y una
 * nitidez leve), asi que no puede agregar, quitar ni cambiar nada del producto y conserva su
 * autenticidad (RNF-46). La original no se toca y la variante solo se publica si el productor la
 * acepta (RF-182).
 */
public class MejoradorImagenPropio implements MejoradorImagen {

    /** Cuanto se corrige el tono hacia el gris medio (0 nada, 1 por completo). */
    static final double FUERZA_BALANCE = 0.6;
    /** Por debajo de esta diferencia entre canales la foto ya esta balanceada. */
    static final double TOLERANCIA = 0.03;

    private final MejoradorImagenSimulado ajustesBase = new MejoradorImagenSimulado();

    @Override
    public Mejora mejorar(BufferedImage original) {
        List<String> ajustes = new ArrayList<>();
        BufferedImage balanceada = balanceDeBlancos(original);
        if (balanceada != original) {
            ajustes.add("BALANCE_BLANCOS");
        }
        Mejora base = ajustesBase.mejorar(balanceada);
        ajustes.addAll(base.ajustes());
        return new Mejora(base.imagen(), List.copyOf(ajustes));
    }

    @Override
    public String proveedor() {
        return "propio";
    }

    /**
     * Mundo gris: el promedio de una foto suele ser neutro, asi que una dominante de color (la luz
     * amarilla de un foco, la sombra azulada) se corrige escalando cada canal. Devuelve la misma
     * imagen si no hay dominante.
     */
    static BufferedImage balanceDeBlancos(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        double r = 0;
        double g = 0;
        double b = 0;
        for (int p : px) {
            r += (p >> 16) & 0xff;
            g += (p >> 8) & 0xff;
            b += p & 0xff;
        }
        double gris = (r + g + b) / 3;
        if (gris == 0) {
            return img;
        }
        double[] factor = {gris / Math.max(r, 1), gris / Math.max(g, 1), gris / Math.max(b, 1)};
        boolean balanceada = true;
        for (int c = 0; c < 3; c++) {
            balanceada &= Math.abs(factor[c] - 1) < TOLERANCIA;
            factor[c] = 1 + FUERZA_BALANCE * (factor[c] - 1);
        }
        if (balanceada) {
            return img;
        }
        BufferedImage destino = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int nr = Math.clamp(Math.round(((p >> 16) & 0xff) * factor[0]), 0, 255);
            int ng = Math.clamp(Math.round(((p >> 8) & 0xff) * factor[1]), 0, 255);
            int nb = Math.clamp(Math.round((p & 0xff) * factor[2]), 0, 255);
            px[i] = (nr << 16) | (ng << 8) | nb;
        }
        destino.setRGB(0, 0, w, h, px, 0, w);
        return destino;
    }
}
