package pe.ccpip.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pe.ccpip.ia.ModeloImagenes.Prediccion;
import pe.ccpip.ia.ValidadorImagen.Resultado;
import pe.ccpip.ia.ValidadorImagen.Veredicto;

/**
 * CCPIP-144 y CCPIP-176 con el modelo propio. La red se compara con la referencia de ONNX Runtime
 * (ia/modelo-imagenes) sobre una entrada fija; la regla de decision y la mejora se prueban sin
 * depender de fotos.
 */
class ModeloPropioTest {

    private static ModeloImagenes modelo;

    @BeforeAll
    static void cargar() {
        modelo = ModeloImagenes.cargar();
    }

    /** Misma entrada que genera la referencia en Python: sin(i*0.001)*1.5 + cos(i*0.0007). */
    private static float[] entradaDeReferencia() {
        float[] x = new float[3 * 224 * 224];
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) (Math.sin(i * 0.001) * 1.5 + Math.cos(i * 0.0007));
        }
        return x;
    }

    @Test
    void laRedEnJavaDaLoMismoQueOnnxRuntime() {
        float[] caracteristicas = modelo.caracteristicas(entradaDeReferencia());
        assertThat(caracteristicas).hasSize(1280);
        Prediccion p = modelo.clasificar(caracteristicas);
        // ONNX Runtime con los pesos originales en float32: clase 636 (mailbag) con 0.0699
        assertThat(p.etiqueta()).isEqualTo("mailbag");
        assertThat(p.probabilidadEtiqueta()).isCloseTo(0.0699, within(0.003));
        assertThat(p.porLinea().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-6));
        assertThat(modelo.version()).isEqualTo("0-imagenet");
    }

    @Test
    void cualquierFotoSePuedeClasificarSinImportarSuTamano() {
        BufferedImage alargada = new BufferedImage(600, 90, BufferedImage.TYPE_INT_RGB);
        Prediccion p = modelo.predecir(alargada);
        assertThat(p.porLinea()).containsKeys("OTRO", "CAFE", "TEXTIL");
        assertThat(new ValidadorImagenPropio(modelo).validar(alargada, List.of("CAFE")).linea()).isNotBlank();
    }

    private static Prediccion prediccion(Object... pares) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            m.put((String) pares[i], (Double) pares[i + 1]);
        }
        return new Prediccion(m, "poncho", 0.8);
    }

    @Test
    void esSospechosaSoloSiOtraLineaSeVeConClaridadYLaEsperadaCasiNo() {
        Resultado r = ValidadorImagenPropio.decidir(prediccion("TEXTIL", 0.85, "CAFE", 0.01, "OTRO", 0.14), List.of("CAFE"));
        assertThat(r.veredicto()).isEqualTo(Veredicto.SOSPECHOSA);
        assertThat(r.linea()).isEqualTo("TEXTIL");
        assertThat(r.motivo()).contains("TEXTIL (85 %)", "CAFE (1 %)");
    }

    @Test
    void anteLaDudaLaFotoSeConsideraValida() {
        // El modelo no reconoce nada concreto (un empaque, por ejemplo)
        assertThat(ValidadorImagenPropio.decidir(prediccion("OTRO", 0.95, "TEXTIL", 0.05, "CAFE", 0.0), List.of("CAFE"))
                .veredicto()).isEqualTo(Veredicto.VALIDA);
        // Otra linea se ve, pero la esperada tambien
        assertThat(ValidadorImagenPropio.decidir(prediccion("TEXTIL", 0.5, "CAFE", 0.3, "OTRO", 0.2), List.of("CAFE"))
                .veredicto()).isEqualTo(Veredicto.VALIDA);
    }

    @Test
    void conVariasLineasCuentaLaQueMasSeVe() {
        Resultado r = ValidadorImagenPropio.decidir(prediccion("TEXTIL", 0.7, "ARTESANIA", 0.2, "OTRO", 0.1),
                List.of("ARTESANIA", "TEXTIL"));
        assertThat(r.veredicto()).isEqualTo(Veredicto.VALIDA);
        assertThat(r.linea()).isEqualTo("TEXTIL");
    }

    @Test
    void laMejoraCorrigeUnaDominanteDeColorSinTocarUnaFotoNeutra() {
        BufferedImage amarilla = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        BufferedImage neutra = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                int v = 60 + x * 3;
                amarilla.setRGB(x, y, (Math.min(255, v + 60) << 16) | (Math.min(255, v + 40) << 8) | v);
                neutra.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        MejoradorImagenPropio mejorador = new MejoradorImagenPropio();
        MejoradorImagen.Mejora m = mejorador.mejorar(amarilla);
        assertThat(m.ajustes()).containsExactly("BALANCE_BLANCOS", "AUTOCONTRASTE", "NITIDEZ");
        assertThat(m.imagen().getWidth()).isEqualTo(40);
        double[] antes = ValidadorImagenSimulado.colorMedio(amarilla);
        double[] balanceada = ValidadorImagenSimulado.colorMedio(MejoradorImagenPropio.balanceDeBlancos(amarilla));
        assertThat(Math.abs(balanceada[0] - balanceada[2])).isLessThan(Math.abs(antes[0] - antes[2]));

        assertThat(mejorador.mejorar(neutra).ajustes()).containsExactly("AUTOCONTRASTE", "NITIDEZ");
        assertThat(mejorador.proveedor()).isEqualTo("propio");
    }

    @Test
    void elProveedorPropioSeEligeConLaConfiguracion() {
        IaConfig config = new IaConfig();
        assertThat(config.validadorImagen("propio")).isInstanceOf(ValidadorImagenPropio.class);
        assertThat(config.validadorImagen("propio").proveedor()).isEqualTo("propio");
        assertThat(config.mejoradorImagen("propio")).isInstanceOf(MejoradorImagenPropio.class);
    }
}
