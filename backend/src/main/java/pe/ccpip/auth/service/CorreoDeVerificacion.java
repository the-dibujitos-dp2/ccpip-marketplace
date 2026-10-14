package pe.ccpip.auth.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import pe.ccpip.auth.service.RegistroService.EnlaceEmitido;
import pe.ccpip.aviso.Correo;
import pe.ccpip.aviso.EnvioCorreo;

/**
 * Arma y envia el correo con el enlace de CCPIP-328, en el idioma de la pantalla desde la que
 * la persona se registro (cabecera Accept-Language, CCPIP-98).
 */
@Component
public class CorreoDeVerificacion {

    private static final Logger LOG = LoggerFactory.getLogger(CorreoDeVerificacion.class);

    private final EnvioCorreo envio;
    private final String urlApp;
    private final long horas;

    public CorreoDeVerificacion(EnvioCorreo envio, RegistroService registro,
                                @Value("${ccpip.app.url}") String urlApp) {
        this.envio = envio;
        this.urlApp = urlApp.endsWith("/") ? urlApp.substring(0, urlApp.length() - 1) : urlApp;
        this.horas = registro.vigencia().toHours();
    }

    /**
     * Envia el enlace. Devuelve false si el servidor de correo fallo: la cuenta ya existe y la
     * persona puede pedir otro enlace desde CW-14, asi que el error no deshace el registro.
     */
    public boolean enviar(EnlaceEmitido enlace, String acceptLanguage) {
        String url = urlApp + "/verificar-correo?token=" + URLEncoder.encode(enlace.token(), StandardCharsets.UTF_8);
        Correo correo = ingles(acceptLanguage) ? enIngles(enlace, url) : enEspanol(enlace, url);
        try {
            envio.enviar(correo);
            return true;
        } catch (MailException e) {
            LOG.error("No se pudo enviar el correo de verificacion: {}", e.getMessage());
            return false;
        }
    }

    private Correo enEspanol(EnlaceEmitido enlace, String url) {
        return new Correo(enlace.correo(), "Verifica tu correo en Mercado CCPIP", """
                Hola, %s:

                Gracias por crear tu cuenta en Mercado CCPIP. Para activarla, abre este enlace:

                %s

                El enlace sirve una sola vez y vence en %d horas. Si no creaste esta cuenta, ignora este correo.

                Cámara de Comercio de los Pueblos Indígenas del Perú
                """.formatted(enlace.nombre(), url, horas));
    }

    private Correo enIngles(EnlaceEmitido enlace, String url) {
        return new Correo(enlace.correo(), "Verify your email for Mercado CCPIP", """
                Hello, %s:

                Thank you for creating your Mercado CCPIP account. To activate it, open this link:

                %s

                The link works only once and expires in %d hours. If you did not create this account, ignore this email.

                Chamber of Commerce of the Indigenous Peoples of Peru
                """.formatted(enlace.nombre(), url, horas));
    }

    /** Ingles solo si es el idioma preferido entre los que ofrece la plataforma; si no, espanol. */
    static boolean ingles(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return false;
        }
        try {
            List<Locale.LanguageRange> rangos = Locale.LanguageRange.parse(acceptLanguage);
            for (Locale.LanguageRange rango : rangos) {
                String idioma = rango.getRange().split("-")[0].toLowerCase(Locale.ROOT);
                if (rango.getWeight() > 0 && (idioma.equals("es") || idioma.equals("en"))) {
                    return idioma.equals("en");
                }
            }
        } catch (IllegalArgumentException e) {
            // Cabecera mal formada: se usa espanol
        }
        return false;
    }
}
