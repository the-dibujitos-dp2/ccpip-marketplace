package pe.ccpip.aviso;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Envio real por SMTP: Gmail en el ambiente desplegado, Mailpit en desarrollo y en e2e. */
class SmtpEnvioCorreo implements EnvioCorreo {

    private final JavaMailSender sender;
    private final String remitente;

    SmtpEnvioCorreo(JavaMailSender sender, String remitente) {
        this.sender = sender;
        this.remitente = remitente;
    }

    @Override
    public void enviar(Correo correo) {
        SimpleMailMessage mensaje = new SimpleMailMessage();
        mensaje.setFrom(remitente);
        mensaje.setTo(correo.para());
        mensaje.setSubject(correo.asunto());
        mensaje.setText(correo.texto());
        sender.send(mensaje);
    }
}
