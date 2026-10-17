package pe.ccpip.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import pe.ccpip.ia.ValidadorImagen.Veredicto;

/** CCPIP-144 y CCPIP-176: proveedores de IA de imagenes (simulado y no configurado). */
class IaImagenesSimuladaTest {

    private final IaConfig config = new IaConfig();

    @Test
    void sinProveedorConfiguradoNoHayIaDeImagenes() {
        ValidadorImagen validador = config.validadorImagen("ninguno");
        MejoradorImagen mejorador = config.mejoradorImagen("ninguno");
        assertThat(validador.proveedor()).isEqualTo("ninguno");
        assertThat(mejorador.proveedor()).isEqualTo("ninguno");
        assertThatThrownBy(() -> validador.validar(solido(Color.GRAY), List.of("ACEITES")))
                .isInstanceOfSatisfying(IaNoDisponible.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("foto original");
                });
        assertThatThrownBy(() -> mejorador.mejorar(solido(Color.GRAY))).isInstanceOf(IaNoDisponible.class);
        assertThatThrownBy(() -> config.validadorImagen("otro")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.mejoradorImagen("otro")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elValidadorSimuladoComparaElColorMedioConLaLinea() {
        ValidadorImagen validador = config.validadorImagen("simulado");
        assertThat(validador.proveedor()).isEqualTo("simulado");

        ValidadorImagen.Resultado aceite = validador.validar(solido(new Color(200, 150, 50)), List.of("ACEITES"));
        assertThat(aceite.veredicto()).isEqualTo(Veredicto.VALIDA);
        assertThat(aceite.linea()).isEqualTo("ACEITES");
        assertThat(aceite.motivo()).startsWith("Simulacion");

        ValidadorImagen.Resultado azul = validador.validar(solido(new Color(60, 100, 170)), List.of("ACEITES"));
        assertThat(azul.veredicto()).isEqualTo(Veredicto.SOSPECHOSA);
        assertThat(azul.linea()).isEqualTo("ARTESANIA");

        // Si el producto tambien es artesania, la misma foto es valida y coincide con esa linea
        ValidadorImagen.Resultado mixto = validador.validar(solido(new Color(60, 100, 170)), List.of("ACEITES", "ARTESANIA"));
        assertThat(mixto.veredicto()).isEqualTo(Veredicto.VALIDA);
        assertThat(mixto.linea()).isEqualTo("ARTESANIA");

        // Lo que no se distingue con claridad no se marca: es solo una recomendacion
        assertThat(validador.validar(solido(new Color(90, 55, 38)), List.of("CAFE")).veredicto()).isEqualTo(Veredicto.VALIDA);
    }

    @Test
    void laMejoraSimuladaSoloEstiraElContrasteSinCambiarElTamano() {
        BufferedImage apagada = new BufferedImage(100, 40, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 100; x++) {
            int v = 100 + x / 2;
            for (int y = 0; y < 40; y++) {
                apagada.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        MejoradorImagen.Mejora mejora = config.mejoradorImagen("simulado").mejorar(apagada);
        assertThat(mejora.ajustes()).containsExactly("AUTOCONTRASTE", "NITIDEZ");
        assertThat(mejora.imagen().getWidth()).isEqualTo(100);
        assertThat(mejora.imagen().getHeight()).isEqualTo(40);
        assertThat(mejora.imagen().getRGB(1, 20) & 0xff).isLessThan(20);
        assertThat(mejora.imagen().getRGB(98, 20) & 0xff).isGreaterThan(235);

        // Un solo color no tiene contraste que estirar: queda igual
        BufferedImage plana = solido(new Color(120, 120, 120));
        assertThat(MejoradorImagenSimulado.autocontraste(plana).getRGB(5, 5)).isEqualTo(plana.getRGB(5, 5));
    }

    @Test
    void losSimuladoresPuedenFallarComoUnServicioCaido() {
        ValidadorImagenSimulado validador = new ValidadorImagenSimulado();
        MejoradorImagenSimulado mejorador = new MejoradorImagenSimulado();
        validador.simularFalla(true);
        mejorador.simularFalla(true);
        assertThatThrownBy(() -> validador.validar(solido(Color.GRAY), List.of("CAFE"))).isInstanceOf(IaNoDisponible.class);
        assertThatThrownBy(() -> mejorador.mejorar(solido(Color.GRAY))).isInstanceOf(IaNoDisponible.class);
    }

    private static BufferedImage solido(Color color) {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        return img;
    }
}
