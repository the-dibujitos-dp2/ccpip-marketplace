package pe.ccpip.aviso;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;

/**
 * Sin servidor SMTP configurado el correo no sale: queda en el registro de la aplicacion.
 * Solo se escribe el contenido si se permite (desarrollo), porque trae enlaces que dan acceso;
 * en el ambiente desplegado queda solo el aviso de que falta configurar el correo, y el envio se
 * reporta como fallido para que la pantalla no diga que el enlace salio (y los avisos queden FALLIDO).
 */
class CorreoEnRegistro implements EnvioCorreo {

    private static final Logger LOG = LoggerFactory.getLogger(CorreoEnRegistro.class);

    private final boolean mostrarContenido;

    CorreoEnRegistro(boolean mostrarContenido) {
        this.mostrarContenido = mostrarContenido;
    }

    @Override
    public void enviar(Correo correo) {
        if (mostrarContenido) {
            LOG.info("Correo no enviado (sin SMTP configurado). Para: {} | Asunto: {}\n{}",
                    correo.para(), correo.asunto(), correo.texto());
        } else {
            LOG.warn("Correo no enviado: falta configurar el servidor SMTP (ccpip.correo.host). Asunto: {}",
                    correo.asunto());
            throw new MailSendException("Falta configurar el servidor SMTP (ccpip.correo.host)");
        }
    }
}
