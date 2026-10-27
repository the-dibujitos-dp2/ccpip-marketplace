package pe.ccpip.imagenes;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Portada de las comunidades sembradas en R__02: una foto real de cada comunidad, tomada de
 * Wikimedia Commons (autores y licencias en {@code semilla/portadas/CREDITOS.md}). Las fotos
 * viven en el bucket, no en la base, asi que se suben al arrancar, despues de Flyway.
 * Solo a quien aun no tiene portada: la que el productor subio desde la plataforma no se
 * toca y volver a arrancar no repite nada (RNF-28).
 */
@Component
@ConditionalOnProperty(name = "ccpip.semilla.portadas", havingValue = "true", matchIfMissing = true)
class PortadasSembradas implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PortadasSembradas.class);

    static final Map<String, String> PORTADAS = new LinkedHashMap<>();

    static {
        PORTADAS.put("productor@ccpip.test", "shipibo-san-francisco.jpg");
        PORTADAS.put("productor2@ccpip.test", "awajun-umukai.jpg");
        PORTADAS.put("productor3@ccpip.test", "ashaninka-pampa-michi.jpg");
        PORTADAS.put("productor_nuevo@ccpip.test", "kukama-nauta.jpg");
        PORTADAS.put("productor4@ccpip.test", "quechua-chinchero.jpg");
        PORTADAS.put("productor5@ccpip.test", "quechua-taquile.jpg");
    }

    private record SinPortada(long productorId, long usuarioId) {
    }

    private final JdbcTemplate jdbc;
    private final ImagenService imagenes;

    PortadasSembradas(JdbcTemplate jdbc, ImagenService imagenes) {
        this.jdbc = jdbc;
        this.imagenes = imagenes;
    }

    @Override
    public void run(ApplicationArguments args) {
        PORTADAS.forEach(this::sembrar);
    }

    private void sembrar(String correo, String archivo) {
        SinPortada destino = jdbc.query("""
                        SELECT p.id, u.id FROM productor p JOIN usuario u ON u.id = p.usuario_id
                        WHERE u.correo = ?
                          AND NOT EXISTS (SELECT 1 FROM imagen i WHERE i.productor_id = p.id AND i.tipo = 'PORTADA')""",
                        (rs, row) -> new SinPortada(rs.getLong(1), rs.getLong(2)), correo)
                .stream().findFirst().orElse(null);
        if (destino == null) {
            return;
        }
        try {
            byte[] foto = new ClassPathResource("semilla/portadas/" + archivo).getContentAsByteArray();
            imagenes.subir(destino.productorId(), destino.usuarioId(), ImagenService.Tipo.PORTADA, null, null, foto);
            log.info("Portada sembrada para {}", correo);
        } catch (IOException | RuntimeException e) {
            // Sin portada la tienda se sigue mostrando: no se detiene el arranque
            log.warn("No se pudo sembrar la portada de {}: {}", correo, e.getMessage());
        }
    }
}
