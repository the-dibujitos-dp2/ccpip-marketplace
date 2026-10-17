package pe.ccpip.ia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el proveedor de IA con {@code ccpip.ia.proveedor}. La clave de un proveedor real ira en
 * una variable de entorno del servidor, nunca en el navegador (CCPIP-48).
 */
@Configuration
public class IaConfig {

    private static final Logger log = LoggerFactory.getLogger(IaConfig.class);

    @Bean
    AsistenteRedaccion asistenteRedaccion(@Value("${ccpip.ia.proveedor:ninguno}") String proveedor) {
        AsistenteRedaccion asistente = switch (proveedor) {
            case "simulado" -> new AsistenteSimulado();
            case "ninguno" -> new AsistenteNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA desconocido: " + proveedor);
        };
        log.info("Redacción asistida por IA: proveedor '{}'", asistente.proveedor());
        return asistente;
    }

    /**
     * CCPIP-144 y CCPIP-176: IA de imagenes. Por defecto sigue a {@code ccpip.ia.proveedor}; el
     * modelo definitivo se agregara aqui como otro valor.
     */
    @Bean
    ValidadorImagen validadorImagen(@Value("${ccpip.ia.imagenes.proveedor:${ccpip.ia.proveedor:ninguno}}") String proveedor) {
        ValidadorImagen validador = switch (proveedor) {
            case "simulado" -> new ValidadorImagenSimulado();
            case "ninguno" -> new ValidadorImagenNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA de imágenes desconocido: " + proveedor);
        };
        log.info("Validación de imágenes por IA: proveedor '{}'", validador.proveedor());
        return validador;
    }

    @Bean
    MejoradorImagen mejoradorImagen(@Value("${ccpip.ia.imagenes.proveedor:${ccpip.ia.proveedor:ninguno}}") String proveedor) {
        return switch (proveedor) {
            case "simulado" -> new MejoradorImagenSimulado();
            case "ninguno" -> new MejoradorImagenNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA de imágenes desconocido: " + proveedor);
        };
    }

    /**
     * CCPIP-50: mismo selector para el analisis preliminar de evidencias. "simulado" es un resultado
     * de prueba y se marca como tal; "ninguno" deriva cada solicitud a revision manual.
     */
    @Bean
    AnalistaEvidencias analistaEvidencias(@Value("${ccpip.ia.proveedor:ninguno}") String proveedor) {
        AnalistaEvidencias analista = switch (proveedor) {
            case "simulado" -> new AnalistaSimulado();
            case "ninguno" -> new AnalistaNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA desconocido: " + proveedor);
        };
        log.info("Análisis preliminar de evidencias: proveedor '{}'", analista.proveedor());
        return analista;
    }
}
