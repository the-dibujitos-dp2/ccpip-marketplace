package pe.ccpip.auth.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import pe.ccpip.auth.service.RegistroService.EnlaceEmitido;
import pe.ccpip.aviso.Correo;
import pe.ccpip.aviso.EnvioCorreo;

/** CCPIP-33: el correo con el enlace para restablecer la contrasena, en el idioma de la pantalla. */
@Component
public class CorreoDeRecuperacion {

    private static final Logger LOG = LoggerFactory.getLogger(CorreoDeRecuperacion.class);

    private final EnvioCorreo envio;
    private final String urlApp;
    private final long minutos;

    public CorreoDeRecuperacion(EnvioCorreo envio, RecuperacionService recuperacion,
                                @Value("${ccpip.app.url}") String urlApp) {
        this.envio = envio;
        this.urlApp = urlApp.endsWith("/") ? urlApp.substring(0, urlApp.length() - 1) : urlApp;
        this.minutos = recuperacion.vigencia().toMinutes();
    }

    /** Un fallo del servidor de correo queda en el registro: la respuesta a la pantalla no cambia. */
    public void enviar(EnlaceEmitido enlace, String acceptLanguage) {
        String url = urlApp + "/restablecer-contrasena?token=" + URLEncoder.encode(enlace.token(), StandardCharsets.UTF_8);
        Correo correo = CorreoDeVerificacion.ingles(acceptLanguage) ? enIngles(enlace, url) : enEspanol(enlace, url);
        try {
            envio.enviar(correo);
        } catch (MailException e) {
            LOG.error("No se pudo enviar el correo de recuperacion: {}", e.getMessage());
        }
    }

    private Correo enEspanol(EnlaceEmitido enlace, String url) {
        return new Correo(enlace.correo(), "Restablece tu contraseña de Mercado CCPIP", """
                Hola, %s:

                Recibimos un pedido para restablecer la contraseña de tu cuenta en Mercado CCPIP. Para elegir una nueva, abre este enlace:

                %s

                El enlace sirve una sola vez y vence en %d minutos. Si no lo pediste, ignora este correo: tu contraseña no cambia.

                Cámara de Comercio de los Pueblos Indígenas del Perú
                """.formatted(enlace.nombre(), url, minutos));
    }

    private Correo enIngles(EnlaceEmitido enlace, String url) {
        return new Correo(enlace.correo(), "Reset your Mercado CCPIP password", """
                Hello, %s:

                We received a request to reset the password of your Mercado CCPIP account. To choose a new one, open this link:

                %s

                The link works only once and expires in %d minutes. If you did not request it, ignore this email: your password stays the same.

                Chamber of Commerce of the Indigenous Peoples of Peru
                """.formatted(enlace.nombre(), url, minutos));
    }
}
