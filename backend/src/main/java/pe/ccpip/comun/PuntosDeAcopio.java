package pe.ccpip.comun;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Los puntos de acopio que administra un usuario (usuario_ubicacion). Las pantallas del punto
 * filtran por ellos en el servidor (RNF-05); sin ninguno asignado no hay nada que mostrar.
 */
@Component
public class PuntosDeAcopio {

    private final JdbcTemplate jdbc;

    public PuntosDeAcopio(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Nombres de sus puntos, separados por coma. 409 ACOPIO_SIN_ASIGNAR si no tiene ninguno. */
    public String nombres(long usuarioId) {
        List<String> puntos = jdbc.queryForList("""
                SELECT ub.nombre FROM usuario_ubicacion uu JOIN ubicacion ub ON ub.id = uu.ubicacion_id
                WHERE uu.usuario_id = ? ORDER BY ub.nombre""", String.class, usuarioId);
        if (puntos.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "ACOPIO_SIN_ASIGNAR",
                    "Tu cuenta aún no tiene un punto de acopio asignado.");
        }
        return String.join(", ", puntos);
    }
}
