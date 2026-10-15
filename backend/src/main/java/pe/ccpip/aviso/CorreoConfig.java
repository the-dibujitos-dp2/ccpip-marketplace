package pe.ccpip.aviso;

import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Elige como sale el correo segun ccpip.correo.*:
 * con host, por SMTP (Gmail con contrasena de aplicacion en el ambiente desplegado, ADR-14);
 * sin host, al registro de la aplicacion, para que el sistema arranque aunque falte el secreto.
 */
@Configuration
public class CorreoConfig {

    @Bean
    EnvioCorreo envioCorreo(@Value("${ccpip.correo.host:}") String host,
                            @Value("${ccpip.correo.puerto:587}") int puerto,
                            @Value("${ccpip.correo.usuario:}") String usuario,
                            @Value("${ccpip.correo.clave:}") String clave,
                            @Value("${ccpip.correo.starttls:true}") boolean starttls,
                            @Value("${ccpip.correo.remitente:}") String remitente,
                            @Value("${ccpip.correo.mostrar-en-registro:false}") boolean mostrarEnRegistro) {
        if (host.isBlank()) {
            return new CorreoEnRegistro(mostrarEnRegistro);
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(puerto);
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        if (!usuario.isBlank()) {
            sender.setUsername(usuario);
            sender.setPassword(clave);
            props.put("mail.smtp.auth", "true");
        }
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        props.put("mail.smtp.starttls.required", String.valueOf(starttls));
        // Que un SMTP caido no deje colgada la peticion del usuario
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        // Gmail exige que el remitente sea la propia cuenta; si no se indica otro, se usa esa
        String de = remitente.isBlank() ? "Mercado CCPIP <" + usuario + ">" : remitente;
        return new SmtpEnvioCorreo(sender, de);
    }
}
