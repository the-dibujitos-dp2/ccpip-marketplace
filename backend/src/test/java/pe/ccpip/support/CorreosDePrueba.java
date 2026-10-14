package pe.ccpip.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailSendException;

import pe.ccpip.aviso.Correo;
import pe.ccpip.aviso.EnvioCorreo;

/**
 * Reemplaza el envio real de correos en las pruebas: los guarda en memoria para leer el enlace.
 * Con {@link #fallar(boolean)} simula un servidor SMTP caido.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CorreosDePrueba {

    /** Bandeja compartida por la suite (un solo contexto); cada prueba la vacia al empezar. */
    public static final class Bandeja implements EnvioCorreo {

        private final List<Correo> enviados = new ArrayList<>();
        private boolean fallar;

        @Override
        public synchronized void enviar(Correo correo) {
            if (fallar) {
                throw new MailSendException("SMTP simulado caido");
            }
            enviados.add(correo);
        }

        public synchronized List<Correo> enviados() {
            return List.copyOf(enviados);
        }

        public synchronized Optional<Correo> ultimoPara(String para) {
            for (int i = enviados.size() - 1; i >= 0; i--) {
                if (enviados.get(i).para().equalsIgnoreCase(para)) {
                    return Optional.of(enviados.get(i));
                }
            }
            return Optional.empty();
        }

        public synchronized void fallar(boolean fallar) {
            this.fallar = fallar;
        }

        public synchronized void vaciar() {
            enviados.clear();
            fallar = false;
        }
    }

    @Bean
    @Primary
    Bandeja bandejaDePrueba() {
        return new Bandeja();
    }
}
