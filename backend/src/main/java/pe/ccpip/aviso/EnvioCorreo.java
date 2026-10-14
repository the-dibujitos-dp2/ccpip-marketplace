package pe.ccpip.aviso;

/**
 * Modulo Avisos (ADR-14): los demas modulos piden el envio aqui y no conocen el proveedor.
 * Cambiar de Gmail a otro servidor SMTP solo cambia la configuracion de {@link CorreoConfig}.
 */
public interface EnvioCorreo {

    /**
     * Envia el correo o lanza {@link org.springframework.mail.MailException} si el servidor lo rechaza.
     * Quien llama decide si el fallo detiene la operacion.
     */
    void enviar(Correo correo);
}
