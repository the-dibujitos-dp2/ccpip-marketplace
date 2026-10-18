package pe.ccpip.cuentas;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import pe.ccpip.aviso.Correo;
import pe.ccpip.aviso.EnvioCorreo;
import pe.ccpip.comun.Idioma;
import pe.ccpip.cuentas.CuentasModelos.Accion;

/**
 * Correos de la gestion de cuentas, en el idioma de la cuenta: la bienvenida con el enlace para elegir
 * la contrasena (CCPIP-301, 304) y el aviso de suspension, reactivacion o desactivacion con su motivo
 * (CCPIP-303, 306, 307, 308). La contrasena nunca viaja por correo: la persona la elige con el enlace.
 * Un fallo del servidor de correo queda en el registro y no deshace la operacion.
 */
@Component
public class CorreoDeCuenta {

    private static final Logger LOG = LoggerFactory.getLogger(CorreoDeCuenta.class);
    private static final String FIRMA_ES = "Cámara de Comercio de los Pueblos Indígenas del Perú";
    private static final String FIRMA_EN = "Chamber of Commerce of the Indigenous Peoples of Peru";

    /** Destinatario y lo necesario para armar el texto. {@code token} solo en la bienvenida. */
    public record Aviso(String correo, String nombre, Idioma idioma, Accion accion, String motivo, String token) {
    }

    private final EnvioCorreo envio;
    private final String urlApp;
    private final long horas;

    public CorreoDeCuenta(EnvioCorreo envio, @Value("${ccpip.app.url}") String urlApp,
                          @Value("${ccpip.cuentas.enlace-acceso-vigencia:72h}") Duration vigencia) {
        this.envio = envio;
        this.horas = vigencia.toHours();
        this.urlApp = urlApp.endsWith("/") ? urlApp.substring(0, urlApp.length() - 1) : urlApp;
    }

    /** Devuelve si el correo salio, para avisar en la pantalla que hay que reenviarlo. */
    public boolean enviar(Aviso aviso) {
        Correo correo = aviso.idioma() == Idioma.EN ? enIngles(aviso) : enEspanol(aviso);
        try {
            envio.enviar(correo);
            return true;
        } catch (MailException e) {
            LOG.error("No se pudo enviar el aviso de cuenta ({}): {}", aviso.accion(), e.getMessage());
            return false;
        }
    }

    private String enlace(String token) {
        return urlApp + "/restablecer-contrasena?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private Correo enEspanol(Aviso a) {
        return switch (a.accion()) {
            case CREAR, REENVIAR_ACCESO -> new Correo(a.correo(), "Tu cuenta de Mercado CCPIP", """
                    Hola, %s:

                    La Cámara creó tu cuenta de administración en Mercado CCPIP. Para elegir tu contraseña e ingresar, abre este enlace:

                    %s

                    El enlace sirve una sola vez y vence en %d horas. Si vence, pide a la Cámara que te envíe otro.

                    %s
                    """.formatted(a.nombre(), enlace(a.token()), horas, FIRMA_ES));
            case SUSPENDER -> estado(a, "Tu cuenta de Mercado CCPIP fue suspendida",
                    "Tu cuenta fue suspendida y por ahora no puedes ingresar.", "Motivo", FIRMA_ES);
            case REACTIVAR -> estado(a, "Tu cuenta de Mercado CCPIP fue reactivada",
                    "Tu cuenta fue reactivada: ya puedes ingresar de nuevo.", "Motivo", FIRMA_ES);
            case DESACTIVAR -> estado(a, "Tu cuenta de Mercado CCPIP fue desactivada",
                    "Tu cuenta fue desactivada y ya no puedes ingresar. Su historial se conserva.", "Motivo", FIRMA_ES);
            case MODIFICAR -> throw new IllegalArgumentException("Modificar no envia aviso");
        };
    }

    private Correo enIngles(Aviso a) {
        return switch (a.accion()) {
            case CREAR, REENVIAR_ACCESO -> new Correo(a.correo(), "Your Mercado CCPIP account", """
                    Hello, %s:

                    The Chamber created your administration account on Mercado CCPIP. To choose your password and sign in, open this link:

                    %s

                    The link works only once and expires in %d hours. If it expires, ask the Chamber to send you a new one.

                    %s
                    """.formatted(a.nombre(), enlace(a.token()), horas, FIRMA_EN));
            case SUSPENDER -> estado(a, "Your Mercado CCPIP account was suspended",
                    "Your account was suspended and you cannot sign in for now.", "Reason", FIRMA_EN);
            case REACTIVAR -> estado(a, "Your Mercado CCPIP account was reactivated",
                    "Your account was reactivated: you can sign in again.", "Reason", FIRMA_EN);
            case DESACTIVAR -> estado(a, "Your Mercado CCPIP account was deactivated",
                    "Your account was deactivated and you can no longer sign in. Its history is kept.", "Reason", FIRMA_EN);
            case MODIFICAR -> throw new IllegalArgumentException("Modificar no envia aviso");
        };
    }

    private static Correo estado(Aviso a, String asunto, String texto, String etiquetaMotivo, String firma) {
        String saludo = a.idioma() == Idioma.EN ? "Hello" : "Hola";
        return new Correo(a.correo(), asunto, """
                %s, %s:

                %s

                %s: %s

                %s
                """.formatted(saludo, a.nombre(), texto, etiquetaMotivo, a.motivo(), firma));
    }
}
