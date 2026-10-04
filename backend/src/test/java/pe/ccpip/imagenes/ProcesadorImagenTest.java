package pe.ccpip.imagenes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import pe.ccpip.comun.ErrorNegocio;

class ProcesadorImagenTest {

    private final ProcesadorImagen procesador = new ProcesadorImagen();

    @Test
    void redimensionaAlLadoMaximoYGeneraMiniatura() throws IOException {
        ProcesadorImagen.Procesada r = procesador.procesar(Imagenes.jpg(4000, 2000));
        assertThat(r.ancho()).isEqualTo(1600);
        assertThat(r.alto()).isEqualTo(800);
        BufferedImage mini = ImageIO.read(new ByteArrayInputStream(r.miniatura()));
        assertThat(mini.getWidth()).isEqualTo(480);
        assertThat(mini.getHeight()).isEqualTo(240);
    }

    @Test
    void noAgrandaLasImagenesPequenas() {
        ProcesadorImagen.Procesada r = procesador.procesar(Imagenes.jpg(400, 300));
        assertThat(r.ancho()).isEqualTo(400);
        assertThat(r.alto()).isEqualTo(300);
    }

    @Test
    void comprimeAJpegYUnPngTransparenteQuedaSobreBlanco() throws IOException {
        ProcesadorImagen.Procesada r = procesador.procesar(Imagenes.png(200, 100));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(r.imagen()));
        assertThat(out).isNotNull();
        assertThat(r.imagen()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(r.imagen()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void aceptaWebP() {
        assertThat(procesador.procesar(Imagenes.WEBP).ancho()).isEqualTo(1);
    }

    @Test
    void rechazaArchivosQueNoSonFotos() {
        assertThatThrownBy(() -> procesador.procesar("hola, no soy una foto".getBytes()))
                .isInstanceOf(ErrorNegocio.class).extracting("code").isEqualTo("FORMATO_NO_PERMITIDO");
        assertThatThrownBy(() -> procesador.procesar(Imagenes.codificar(Imagenes.lienzo(10, 10, BufferedImage.TYPE_INT_RGB), "gif")))
                .isInstanceOf(ErrorNegocio.class).extracting("code").isEqualTo("FORMATO_NO_PERMITIDO");
        assertThatThrownBy(() -> procesador.procesar(new byte[0]))
                .isInstanceOf(ErrorNegocio.class).extracting("code").isEqualTo("ARCHIVO_VACIO");
    }

    @Test
    void rechazaArchivosDeMasDeDiezMegas() {
        assertThatThrownBy(() -> procesador.procesar(new byte[ProcesadorImagen.MAX_BYTES + 1]))
                .isInstanceOf(ErrorNegocio.class).extracting("code").isEqualTo("ARCHIVO_MUY_PESADO");
    }

    @Test
    void laOrientacionSeisGiraNoventaGradosAlaDerecha() {
        // La foto del celular viene "acostada": izquierda roja, derecha azul, 200x100
        BufferedImage girada = ProcesadorImagen.orientar(Imagenes.lienzo(200, 100, BufferedImage.TYPE_INT_RGB), 6);
        assertThat(girada.getWidth()).isEqualTo(100);
        assertThat(girada.getHeight()).isEqualTo(200);
        // Tras girar 90 grados a la derecha, lo rojo queda arriba y lo azul abajo
        assertThat(new Color(girada.getRGB(50, 20))).isEqualTo(Color.RED);
        assertThat(new Color(girada.getRGB(50, 180))).isEqualTo(Color.BLUE);
    }

    @Test
    void lasDemasOrientacionesConservanUOrdenanLasDimensiones() {
        BufferedImage base = Imagenes.lienzo(200, 100, BufferedImage.TYPE_INT_RGB);
        for (int o = 1; o <= 8; o++) {
            BufferedImage r = ProcesadorImagen.orientar(base, o);
            int[] esperado = o >= 5 ? new int[] {100, 200} : new int[] {200, 100};
            assertThat(new int[] {r.getWidth(), r.getHeight()}).as("orientacion %d", o).isEqualTo(esperado);
        }
        // Orientacion 3: girada 180 grados, lo rojo pasa a la derecha
        assertThat(new Color(ProcesadorImagen.orientar(base, 3).getRGB(180, 50))).isEqualTo(Color.RED);
    }
}
