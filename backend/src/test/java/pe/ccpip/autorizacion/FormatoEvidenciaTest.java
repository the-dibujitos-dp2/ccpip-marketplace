package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.imagenes.Imagenes;

/** CCPIP-36: el formato se reconoce por los primeros bytes, no por la extension. */
class FormatoEvidenciaTest {

    @Test
    void reconocePdfJpgYPng() {
        assertThat(FormatoEvidencia.detectar("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII))).isEqualTo(FormatoEvidencia.PDF);
        assertThat(FormatoEvidencia.detectar(Imagenes.jpg(5, 5))).isEqualTo(FormatoEvidencia.JPG);
        assertThat(FormatoEvidencia.detectar(Imagenes.png(5, 5))).isEqualTo(FormatoEvidencia.PNG);
        assertThat(FormatoEvidencia.deContentType("image/png")).isEqualTo(FormatoEvidencia.PNG);
    }

    @Test
    void rechazaVacioPesadoYDesconocido() {
        assertThatThrownBy(() -> FormatoEvidencia.detectar(new byte[0]))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> assertThat(e.code()).isEqualTo("ARCHIVO_VACIO"));
        assertThatThrownBy(() -> FormatoEvidencia.detectar(new byte[FormatoEvidencia.MAX_BYTES + 1]))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThatThrownBy(() -> FormatoEvidencia.detectar("<html>".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> assertThat(e.code()).isEqualTo("FORMATO_NO_PERMITIDO"));
        assertThatThrownBy(() -> FormatoEvidencia.detectar(new byte[] {(byte) 0xFF}))
                .isInstanceOf(ErrorNegocio.class);
        assertThatThrownBy(() -> FormatoEvidencia.deContentType("text/html")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elNombreDelArchivoNoTraeRutasNiCaracteresDeControl() {
        assertThat(EvidenciasService.nombreSeguro("C:\\docs\\certificado.pdf")).isEqualTo("certificado.pdf");
        assertThat(EvidenciasService.nombreSeguro("../../etc/\"pass\nwd\".pdf")).isEqualTo("passwd.pdf");
        assertThat(EvidenciasService.nombreSeguro(null)).isEqualTo("evidencia");
        assertThat(EvidenciasService.nombreSeguro("a".repeat(250) + ".pdf")).hasSize(200).endsWith(".pdf");
    }
}
