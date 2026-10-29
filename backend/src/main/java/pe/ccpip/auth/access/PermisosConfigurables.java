package pe.ccpip.auth.access;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import pe.ccpip.auth.model.Rol;

/** CCPIP-296: si el superadministrador revoco un permiso configurable a un rol. Se consulta en cada peticion. */
@Component
public class PermisosConfigurables {

    private final JdbcTemplate jdbc;

    public PermisosConfigurables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Verdadero solo si el rol tiene el permiso registrado y revocado; sin fila, lo conserva. */
    public boolean revocado(Rol rol, Permiso permiso) {
        List<Boolean> concedido = jdbc.queryForList("""
                SELECT pc.concedido FROM permiso_configurable pc JOIN rol r ON r.id = pc.rol_id
                WHERE r.codigo = ? AND pc.codigo = ?""", Boolean.class, rol.name(), permiso.name());
        return !concedido.isEmpty() && !Boolean.TRUE.equals(concedido.get(0));
    }
}
