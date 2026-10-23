package pe.ccpip.ia;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el proveedor de IA con {@code ccpip.ia.proveedor}: "gemini" (redaccion y analisis de
 * evidencias con la API de Gemini), "simulado" (solo pruebas) o "ninguno". La clave de Gemini va en
 * la variable de entorno del servidor CCPIP_GEMINI_API_KEY, nunca en el navegador (CCPIP-48). Sin
 * clave, "gemini" se comporta como "ninguno": la plataforma arranca y se escribe a mano.
 */
@Configuration
public class IaConfig {

    private static final Logger log = LoggerFactory.getLogger(IaConfig.class);

    /** Cliente de Gemini, o nulo si no hay clave configurada. */
    private final GeminiCliente gemini;

    /** Sin Gemini: lo usan las pruebas unitarias de los proveedores simulado y ninguno. */
    public IaConfig() {
        this((GeminiCliente) null);
    }

    public IaConfig(GeminiCliente gemini) {
        this.gemini = gemini;
    }

    @Autowired
    public IaConfig(@Value("${ccpip.ia.gemini.clave:}") String clave,
                    @Value("${ccpip.ia.gemini.modelo:gemini-flash-latest}") String modelo,
                    @Value("${ccpip.ia.gemini.url:https://generativelanguage.googleapis.com}") String url,
                    @Value("${ccpip.ia.gemini.espera:20s}") Duration espera) {
        this(clave == null || clave.isBlank() ? null : new GeminiCliente(url, clave.strip(), modelo, espera));
    }

    @Bean
    AsistenteRedaccion asistenteRedaccion(@Value("${ccpip.ia.proveedor:ninguno}") String proveedor) {
        AsistenteRedaccion asistente = switch (proveedor) {
            case "gemini" -> gemini == null ? sinClave(new AsistenteNoConfigurado()) : new AsistenteGemini(gemini);
            case "simulado" -> new AsistenteSimulado();
            case "ninguno" -> new AsistenteNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA desconocido: " + proveedor);
        };
        log.info("Redacción asistida por IA: proveedor '{}'", asistente.proveedor());
        return asistente;
    }

    /**
     * CCPIP-144 y CCPIP-176: IA de imagenes con {@code ccpip.ia.imagenes.proveedor}: "propio" (modelo
     * del equipo, ver ia/modelo-imagenes), "simulado" (pruebas) o "ninguno". Por defecto sigue a
     * {@code ccpip.ia.proveedor}.
     */
    @Bean
    ValidadorImagen validadorImagen(@Value("${ccpip.ia.imagenes.proveedor:${ccpip.ia.proveedor:ninguno}}") String proveedor) {
        ValidadorImagen validador = switch (proveedor) {
            // CCPIP-144: modelo propio del equipo, ejecutado dentro del backend (RNF-48)
            case "propio" -> new ValidadorImagenPropio(ModeloImagenes.cargar());
            case "simulado" -> new ValidadorImagenSimulado();
            // Gemini es solo para texto: la validacion de imagenes usa el modelo propio
            case "ninguno", "gemini" -> new ValidadorImagenNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA de imágenes desconocido: " + proveedor);
        };
        log.info("Validación de imágenes por IA: proveedor '{}'", validador.proveedor());
        return validador;
    }

    @Bean
    MejoradorImagen mejoradorImagen(@Value("${ccpip.ia.imagenes.proveedor:${ccpip.ia.proveedor:ninguno}}") String proveedor) {
        return switch (proveedor) {
            case "propio" -> new MejoradorImagenPropio();
            case "simulado" -> new MejoradorImagenSimulado();
            case "ninguno", "gemini" -> new MejoradorImagenNoConfigurado();
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
            case "gemini" -> gemini == null ? sinClave(new AnalistaNoConfigurado()) : new AnalistaGemini(gemini);
            case "simulado" -> new AnalistaSimulado();
            case "ninguno" -> new AnalistaNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA desconocido: " + proveedor);
        };
        log.info("Análisis preliminar de evidencias: proveedor '{}'", analista.proveedor());
        return analista;
    }

    private static <T> T sinClave(T noConfigurado) {
        log.warn("ccpip.ia.proveedor=gemini pero falta CCPIP_GEMINI_API_KEY: la IA queda no disponible");
        return noConfigurado;
    }
}
