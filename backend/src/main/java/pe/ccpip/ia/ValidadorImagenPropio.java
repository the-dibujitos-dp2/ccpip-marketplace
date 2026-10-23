package pe.ccpip.ia;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CCPIP-144 (RF-113): validacion de correspondencia con el modelo propio del equipo (RNF-48),
 * ejecutado dentro del backend: la foto no sale del servidor.
 *
 * Es una recomendacion conservadora (RF-137, RNF-39): marca la foto como sospechosa solo cuando el
 * modelo ve con claridad otra linea y casi nada de la esperada. Si el modelo no reconoce nada
 * concreto (por ejemplo, un empaque), la foto se considera valida: una alarma falsa molesta mas al
 * productor que una omision, y la revision humana sigue existiendo.
 */
public class ValidadorImagenPropio implements ValidadorImagen {

    /** Probabilidad minima de otra linea para sospechar. */
    static final double UMBRAL_OTRA = 0.40;
    /** Cuantas veces debe superar la otra linea a la esperada. */
    static final double VENTAJA = 2.0;

    private final ModeloImagenes modelo;

    public ValidadorImagenPropio(ModeloImagenes modelo) {
        this.modelo = modelo;
    }

    @Override
    public Resultado validar(BufferedImage imagen, List<String> lineas) {
        return decidir(modelo.predecir(imagen), lineas);
    }

    /** La regla de decision, separada del modelo para poder probarla con cualquier prediccion. */
    static Resultado decidir(ModeloImagenes.Prediccion p, List<String> lineas) {
        Map<String, Double> porLinea = p.porLinea();
        String esperada = lineas.get(0);
        double pEsperada = 0;
        for (String linea : lineas) {
            double v = porLinea.getOrDefault(linea, 0.0);
            if (v > pEsperada) {
                pEsperada = v;
                esperada = linea;
            }
        }
        String otra = null;
        double pOtra = 0;
        for (Map.Entry<String, Double> e : porLinea.entrySet()) {
            if (!ModeloImagenes.OTRO.equals(e.getKey()) && !lineas.contains(e.getKey()) && e.getValue() > pOtra) {
                otra = e.getKey();
                pOtra = e.getValue();
            }
        }
        String visto = "Se parece a «" + p.etiqueta() + "» (" + porcentaje(p.probabilidadEtiqueta()) + ")";
        if (otra != null && pOtra >= UMBRAL_OTRA && pOtra >= VENTAJA * pEsperada) {
            return new Resultado(Veredicto.SOSPECHOSA, otra, visto + ": corresponde más a " + otra + " ("
                    + porcentaje(pOtra) + ") que a " + esperada + " (" + porcentaje(pEsperada) + ").");
        }
        return new Resultado(Veredicto.VALIDA, esperada, visto + ". Nada indica otra línea; " + esperada
                + " " + porcentaje(pEsperada) + ".");
    }

    @Override
    public String proveedor() {
        return "propio";
    }

    private static String porcentaje(double p) {
        return String.format(Locale.ROOT, "%.0f %%", p * 100);
    }
}
