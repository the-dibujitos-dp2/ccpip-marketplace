package pe.ccpip.productor;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.comun.ErrorNegocio;

/**
 * RNF-05: de que productor es la sesion. Toda operacion del productor filtra por este id en
 * el servidor; nunca se confia en un id de productor enviado por la pantalla.
 */
@Component
public class ProductorActual {

    private final JdbcTemplate jdbc;

    public ProductorActual(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long id(AuthenticatedSession session) {
        return jdbc.query("SELECT id FROM productor WHERE usuario_id = ? AND activo",
                        (rs, row) -> rs.getLong("id"), session.userId())
                .stream().findFirst()
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT, "PRODUCTOR_SIN_REGISTRO",
                        "Tu cuenta aún no está asociada a una comunidad productora. Comunícate con la CCPIP."));
    }
}
