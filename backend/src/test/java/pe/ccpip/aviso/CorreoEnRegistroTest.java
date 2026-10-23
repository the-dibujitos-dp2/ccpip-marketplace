package pe.ccpip.aviso;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailException;

/** Sin SMTP, el ambiente desplegado no puede dar por enviado un correo que no salio. */
class CorreoEnRegistroTest {

    private static final Correo CORREO = new Correo("lucia@correo.pe", "Verifica tu correo", "Abre el enlace");

    @Test
    void enDesarrolloElCorreoQuedaEnElRegistroYSeConsideraEntregado() {
        assertThatCode(() -> new CorreoEnRegistro(true).enviar(CORREO)).doesNotThrowAnyException();
    }

    @Test
    void enElAmbienteDesplegadoSinSmtpElEnvioFalla() {
        assertThatThrownBy(() -> new CorreoEnRegistro(false).enviar(CORREO))
                .isInstanceOf(MailException.class)
                .hasMessageContaining("SMTP");
    }
}
