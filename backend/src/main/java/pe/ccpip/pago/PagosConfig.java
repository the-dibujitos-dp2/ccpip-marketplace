package pe.ccpip.pago;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige la pasarela de pagos con {@code ccpip.pagos.pasarela} (CCPIP-62). Para conectar Culqi o
 * Niubiz en modo pruebas: agregar su {@link PasarelaPago}, un caso aqui y sus llaves en variables
 * de entorno del servidor.
 */
@Configuration
public class PagosConfig {

    private static final Logger log = LoggerFactory.getLogger(PagosConfig.class);

    @Bean
    PasarelaPago pasarelaPago(@Value("${ccpip.pagos.pasarela:simulada}") String pasarela) {
        PasarelaPago elegida = switch (pasarela) {
            case "simulada" -> new PasarelaSimulada();
            default -> throw new IllegalStateException("Pasarela de pagos desconocida: " + pasarela);
        };
        log.info("Pagos: pasarela '{}'", elegida.nombre());
        return elegida;
    }
}
