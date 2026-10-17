package pe.ccpip.ia;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SIMULADOR de la validacion de correspondencia (CCPIP-144), solo para pruebas automatizadas y
 * para la demo. NO es el modelo propio ni reconoce productos: compara el color medio de la foto
 * con un color de referencia fijo por linea y marca como sospechosa la foto cuyo color queda
 * claramente mas cerca de otra linea. Es determinista y no depende de ningun servicio externo.
 * La interfaz muestra un aviso de "validacion de prueba" cuando este es el proveedor.
 */
public class ValidadorImagenSimulado implements ValidadorImagen {

    /** Colores de referencia (RGB) del simulador; no salen de ningun entrenamiento. */
    static final Map<String, int[]> REFERENCIAS = referencias();

    /** Cuanto mas cerca (distancia RGB) debe estar otra linea para marcar la foto como sospechosa. */
    static final double MARGEN = 60;

    private volatile boolean falla;

    @Override
    public Resultado validar(BufferedImage imagen, List<String> lineas) {
        if (falla) {
            throw IaNoDisponible.imagenes();
        }
        double[] media = colorMedio(imagen);
        String esperada = lineas.get(0);
        double distanciaEsperada = Double.MAX_VALUE;
        for (String linea : lineas) {
            double d = distancia(media, linea);
            if (d < distanciaEsperada) {
                distanciaEsperada = d;
                esperada = linea;
            }
        }
        String cercana = esperada;
        double distanciaCercana = distanciaEsperada;
        for (String linea : REFERENCIAS.keySet()) {
            double d = distancia(media, linea);
            if (d < distanciaCercana) {
                distanciaCercana = d;
                cercana = linea;
            }
        }
        if (!lineas.contains(cercana) && distanciaEsperada - distanciaCercana > MARGEN) {
            return new Resultado(Veredicto.SOSPECHOSA, cercana, "Simulacion: el color medio se parece mas a " + cercana);
        }
        return new Resultado(Veredicto.VALIDA, esperada, "Simulacion: el color medio es compatible con " + esperada);
    }

    @Override
    public String proveedor() {
        return "simulado";
    }

    /** Solo pruebas: hace que el simulador responda como un servicio caido (503). */
    public void simularFalla(boolean falla) {
        this.falla = falla;
    }

    private static double distancia(double[] media, String linea) {
        int[] ref = REFERENCIAS.get(linea);
        if (ref == null) {
            return Double.MAX_VALUE;
        }
        double r = media[0] - ref[0];
        double g = media[1] - ref[1];
        double b = media[2] - ref[2];
        return Math.sqrt(r * r + g * g + b * b);
    }

    /** Color medio muestreando una grilla: basta para una miniatura y es estable. */
    static double[] colorMedio(BufferedImage imagen) {
        int paso = Math.max(1, Math.max(imagen.getWidth(), imagen.getHeight()) / 64);
        double r = 0;
        double g = 0;
        double b = 0;
        long n = 0;
        for (int y = 0; y < imagen.getHeight(); y += paso) {
            for (int x = 0; x < imagen.getWidth(); x += paso) {
                int rgb = imagen.getRGB(x, y);
                r += (rgb >> 16) & 0xff;
                g += (rgb >> 8) & 0xff;
                b += rgb & 0xff;
                n++;
            }
        }
        return new double[] {r / n, g / n, b / n};
    }

    private static Map<String, int[]> referencias() {
        Map<String, int[]> m = new LinkedHashMap<>();
        m.put("CACAO", new int[] {95, 60, 40});
        m.put("CAFE", new int[] {70, 45, 35});
        m.put("SUPERFRUTOS", new int[] {170, 50, 70});
        m.put("ACEITES", new int[] {200, 150, 50});
        m.put("INFUSIONES", new int[] {100, 140, 70});
        m.put("ARTESANIA", new int[] {60, 100, 170});
        m.put("TEXTIL", new int[] {180, 70, 150});
        // Orden fijo: ante un empate gana siempre la misma linea
        return java.util.Collections.unmodifiableMap(m);
    }
}
