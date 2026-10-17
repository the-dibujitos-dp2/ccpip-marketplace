package pe.ccpip.traduccion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el proveedor de traduccion automatica con {@code ccpip.traduccion.proveedor} (CCPIP-147).
 * No hay proveedor real hasta que el cliente apruebe la estrategia; su clave ira en una variable de
 * entorno del servidor, nunca en el navegador.
 */
@Configuration
public class TraduccionConfig {

    private static final Logger log = LoggerFactory.getLogger(TraduccionConfig.class);

    @Bean
    TraductorAutomatico traductorAutomatico(@Value("${ccpip.traduccion.proveedor:ninguno}") String proveedor) {
        TraductorAutomatico traductor = switch (proveedor) {
            case "simulado" -> new TraductorSimulado();
            case "ninguno" -> new TraductorNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de traduccion desconocido: " + proveedor);
        };
        log.info("Traducción automática: proveedor '{}'", traductor.proveedor());
        return traductor;
    }
}
