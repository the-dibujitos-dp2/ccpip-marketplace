package pe.ccpip.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-118 (RNF-25): el lector de formato y duracion, sin Spring. Ademas de videos reales, prueba archivos
 * armados a proposito (tamanos desconocidos, miles de elementos vacios, duracion invalida): se rechazan
 * rapido, sin girar sin fin.
 */
@Timeout(10)
class FormatoVideoTest {

    private static final byte[] DESCONOCIDO = {0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
        (byte) 0xFF, (byte) 0xFF};

    @TempDir
    Path carpeta;

    @Test
    void leeLaDuracionDeVideosReales() throws IOException {
        assertThat(FormatoVideo.leer(recurso("cosecha.mp4")))
                .isEqualTo(new FormatoVideo.Leido(FormatoVideo.Formato.MP4, new BigDecimal("4.00")));
        assertThat(FormatoVideo.leer(recurso("telar.webm")))
                .isEqualTo(new FormatoVideo.Leido(FormatoVideo.Formato.WEBM, new BigDecimal("3.00")));
        assertThat(FormatoVideo.leer(recurso("largo.mp4")).duracionSegundos()).isEqualByComparingTo("200");
    }

    @Test
    void leeUnWebmArmadoConSuDuracion() throws IOException {
        byte[] info = concatenar(elemento(0x2AD7B1, 3, entero(1_000_000, 3)), elemento(0x4489, 2, flotante(30_000f)));
        Path webm = escribir(concatenar(cabeceraWebm(), id(0x18538067, 4), DESCONOCIDO, elemento(0x1549A966, 4, info)));
        assertThat(FormatoVideo.leer(webm).duracionSegundos()).isEqualByComparingTo("30");
    }

    @Test
    void rechazaLoQueNoEsMp4NiWebm() throws IOException {
        assertCodigo(escribir("no soy un video".getBytes(StandardCharsets.UTF_8)), "VIDEO_NO_RECONOCIDO");
        // QuickTime (.mov) no se reproduce en todos los navegadores
        assertCodigo(escribir(concatenar(ByteBuffer.allocate(4).putInt(16).array(), ascii("ftypqt  "), new byte[4])),
                "VIDEO_NO_RECONOCIDO");
    }

    @Test
    void unElementoDeTamanoDesconocidoNoLoDejaGirando() throws IOException {
        byte[] info = concatenar(id(0xEC, 1), DESCONOCIDO, new byte[64]);
        Path webm = escribir(concatenar(cabeceraWebm(), id(0x18538067, 4), DESCONOCIDO, elemento(0x1549A966, 4, info)));
        assertCodigo(webm, "VIDEO_NO_RECONOCIDO");
    }

    @Test
    void milesDeElementosVaciosSeCortanPronto() throws IOException {
        ByteArrayOutputStream vacios = new ByteArrayOutputStream();
        for (int i = 0; i < 60_000; i++) {
            vacios.write(0xEC);
            vacios.write(0x80);
        }
        Path webm = escribir(concatenar(cabeceraWebm(), id(0x18538067, 4), DESCONOCIDO,
                elemento(0x1549A966, 4, vacios.toByteArray())));
        long inicio = System.nanoTime();
        assertCodigo(webm, "VIDEO_NO_RECONOCIDO");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(5));

        ByteArrayOutputStream cajas = new ByteArrayOutputStream();
        cajas.write(concatenar(ByteBuffer.allocate(4).putInt(16).array(), ascii("ftypisom"), new byte[4]));
        for (int i = 0; i < 200_000; i++) {
            cajas.write(concatenar(ByteBuffer.allocate(4).putInt(8).array(), ascii("free")));
        }
        assertCodigo(escribir(cajas.toByteArray()), "VIDEO_NO_RECONOCIDO");
    }

    @Test
    void unaDuracionInvalidaNoPasa() throws IOException {
        byte[] info = elemento(0x4489, 2, ByteBuffer.allocate(8).putDouble(Double.NaN).array());
        Path webm = escribir(concatenar(cabeceraWebm(), id(0x18538067, 4), DESCONOCIDO, elemento(0x1549A966, 4, info)));
        assertCodigo(webm, "VIDEO_SIN_DURACION");
    }

    // ---------- Armado de archivos ----------

    private static void assertCodigo(Path archivo, String codigo) {
        assertThatThrownBy(() -> FormatoVideo.leer(archivo)).isInstanceOf(ErrorNegocio.class)
                .extracting("code").isEqualTo(codigo);
    }

    private static byte[] cabeceraWebm() {
        return elemento(0x1A45DFA3, 4, elemento(0x4282, 2, ascii("webm")));
    }

    /** Un elemento EBML con su tamano en 8 bytes. */
    private static byte[] elemento(int id, int bytesId, byte[] datos) {
        byte[] tamano = ByteBuffer.allocate(8).putLong(datos.length).array();
        tamano[0] = 0x01;
        return concatenar(id(id, bytesId), tamano, datos);
    }

    private static byte[] id(int id, int bytes) {
        return entero(id, bytes);
    }

    private static byte[] entero(long valor, int bytes) {
        byte[] b = new byte[bytes];
        for (int i = bytes - 1; i >= 0; i--) {
            b[i] = (byte) valor;
            valor >>= 8;
        }
        return b;
    }

    private static byte[] flotante(float valor) {
        return ByteBuffer.allocate(4).putFloat(valor).array();
    }

    private static byte[] ascii(String texto) {
        return texto.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concatenar(byte[]... partes) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] p : partes) {
            salida.writeBytes(p);
        }
        return salida.toByteArray();
    }

    private Path escribir(byte[] contenido) throws IOException {
        return Files.write(Files.createTempFile(carpeta, "video", ".bin"), contenido);
    }

    private Path recurso(String nombre) throws IOException {
        return escribir(VideosTest.recurso(nombre));
    }
}
