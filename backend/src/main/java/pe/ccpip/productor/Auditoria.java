package pe.ccpip.productor;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** CCPIP-47: deja constancia de quien hizo que sobre cada producto y cuando. */
@Component
public class Auditoria {

    /** EVIDENCIA y AUTORIZACION: certificados del producto y la decision sobre ellos (CCPIP-36, 174, 284). */
    public enum Accion { CREAR, EDITAR, PUBLICAR, RETIRAR, REPUBLICAR, HISTORIA, LOTE, IMAGEN, EVIDENCIA, AUTORIZACION }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Auditoria(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void registrar(long productoId, long usuarioId, Accion accion, Map<String, ?> cambios) {
        try {
            jdbc.update("INSERT INTO producto_auditoria (producto_id, usuario_id, accion, cambios) VALUES (?, ?, ?, ?::jsonb)",
                    productoId, usuarioId, accion.name(), cambios == null || cambios.isEmpty() ? null : json.writeValueAsString(cambios));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo registrar la auditoria", e);
        }
    }
}
