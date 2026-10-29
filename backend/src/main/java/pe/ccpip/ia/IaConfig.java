package pe.ccpip.ia;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el proveedor de IA de texto con {@code ccpip.ia.proveedor}: "gemini" o "openai" (redaccion,
 * analisis de evidencias y asistente de catalogo con la API de cada uno), "simulado" (solo pruebas)
 * o "ninguno". Las claves van en las variables de entorno del servidor CCPIP_GEMINI_API_KEY y
 * CCPIP_OPENAI_API_KEY, nunca en el navegador (CCPIP-48). Sin la clave del proveedor elegido, este
 * se comporta como "ninguno": la plataforma arranca y se escribe a mano.
 */
@Configuration
public class IaConfig {

    private static final Logger log = LoggerFactory.getLogger(IaConfig.class);

    /** Clientes de cada proveedor, o nulos si no hay clave configurada. */
    private final ModeloTexto gemini;
    private final ModeloTexto openai;

    /** Sin modelos de texto: lo usan las pruebas unitarias de los proveedores simulado y ninguno. */
    public IaConfig() {
        this(null, null);
    }

    public IaConfig(ModeloTexto gemini, ModeloTexto openai) {
        this.gemini = gemini;
        this.openai = openai;
    }

    @Autowired
    public IaConfig(@Value("${ccpip.ia.gemini.clave:}") String claveGemini,
                    @Value("${ccpip.ia.gemini.modelo:gemini-flash-latest}") String modeloGemini,
                    @Value("${ccpip.ia.gemini.url:https://generativelanguage.googleapis.com}") String urlGemini,
                    @Value("${ccpip.ia.gemini.espera:20s}") Duration esperaGemini,
                    @Value("${ccpip.ia.openai.clave:}") String claveOpenai,
                    @Value("${ccpip.ia.openai.modelo:gpt-4.1-mini}") String modeloOpenai,
                    @Value("${ccpip.ia.openai.url:https://api.openai.com}") String urlOpenai,
                    @Value("${ccpip.ia.openai.espera:20s}") Duration esperaOpenai) {
        this(sinTexto(claveGemini) ? null : new GeminiCliente(urlGemini, claveGemini.strip(), modeloGemini, esperaGemini),
                sinTexto(claveOpenai) ? null : new OpenAiCliente(urlOpenai, claveOpenai.strip(), modeloOpenai, esperaOpenai));
    }

    @Bean
    AsistenteRedaccion asistenteRedaccion(@Value("${ccpip.ia.proveedor:ninguno}") String proveedor) {
        AsistenteRedaccion asistente = switch (proveedor) {
            case "gemini", "openai" -> conModelo(proveedor, AsistenteGemini::new, AsistenteNoConfigurado::new);
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
            // Gemini y OpenAI son solo para texto: la validacion de imagenes usa el modelo propio
            case "ninguno", "gemini", "openai" -> new ValidadorImagenNoConfigurado();
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
            case "ninguno", "gemini", "openai" -> new MejoradorImagenNoConfigurado();
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
            case "gemini", "openai" -> conModelo(proveedor, AnalistaGemini::new, AnalistaNoConfigurado::new);
            case "simulado" -> new AnalistaSimulado();
            case "ninguno" -> new AnalistaNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA desconocido: " + proveedor);
        };
        log.info("Análisis preliminar de evidencias: proveedor '{}'", analista.proveedor());
        return analista;
    }

    /** CCPIP-51 y CCPIP-175: asistente de catalogo, con el mismo selector que la redaccion. */
    @Bean
    AsistenteCatalogo asistenteCatalogo(@Value("${ccpip.ia.proveedor:ninguno}") String proveedor) {
        AsistenteCatalogo asistente = switch (proveedor) {
            case "gemini", "openai" -> conModelo(proveedor, AsistenteCatalogoGemini::new, AsistenteCatalogoNoConfigurado::new);
            case "simulado" -> new AsistenteCatalogoSimulado();
            case "ninguno" -> new AsistenteCatalogoNoConfigurado();
            default -> throw new IllegalStateException("Proveedor de IA desconocido: " + proveedor);
        };
        log.info("Asistente de catálogo: proveedor '{}'", asistente.proveedor());
        return asistente;
    }

    /** Arma el componente con el modelo del proveedor elegido; sin su clave queda no disponible. */
    private <T> T conModelo(String proveedor, Function<ModeloTexto, T> conIa, Supplier<T> sinIa) {
        ModeloTexto modelo = "openai".equals(proveedor) ? openai : gemini;
        if (modelo != null) {
            return conIa.apply(modelo);
        }
        log.warn("ccpip.ia.proveedor={} pero falta {}: la IA queda no disponible", proveedor,
                "openai".equals(proveedor) ? "CCPIP_OPENAI_API_KEY" : "CCPIP_GEMINI_API_KEY");
        return sinIa.get();
    }

    private static boolean sinTexto(String clave) {
        return clave == null || clave.isBlank();
    }
}
