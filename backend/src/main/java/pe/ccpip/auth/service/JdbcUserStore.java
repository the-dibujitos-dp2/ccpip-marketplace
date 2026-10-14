package pe.ccpip.auth.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.ccpip.auth.model.AuthUser;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.Idioma;

@Repository
public class JdbcUserStore implements UserStore {

    private static final String SELECT_USER = """
            SELECT u.id, u.correo, u.contrasena_hash, u.nombre, r.codigo AS rol_codigo,
                   r.nombre AS rol_nombre, u.activo, u.correo_confirmado,
                   u.intentos_fallidos, u.bloqueado_hasta, u.idioma
            FROM usuario u
            JOIN rol r ON r.id = u.rol_id
            """;

    private final JdbcTemplate jdbc;

    public JdbcUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AuthUser> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        // lower(correo) usa el indice usuario_correo_lower_idx
        return jdbc.query(SELECT_USER + " WHERE lower(u.correo) = lower(?)",
                JdbcUserStore::map, email.trim()).stream().findFirst();
    }

    @Override
    public Optional<AuthUser> findById(long id) {
        return jdbc.query(SELECT_USER + " WHERE u.id = ?", JdbcUserStore::map, id).stream().findFirst();
    }

    @Override
    public void recordFailedAttempts(long userId, int failedAttempts, Instant lockedUntil) {
        jdbc.update("""
                UPDATE usuario SET intentos_fallidos = ?, bloqueado_hasta = ?, actualizado_en = now()
                WHERE id = ?""",
                failedAttempts, lockedUntil == null ? null : OffsetDateTime.ofInstant(lockedUntil, ZoneOffset.UTC),
                userId);
    }

    @Override
    public void resetFailedAttempts(long userId) {
        jdbc.update("""
                UPDATE usuario SET intentos_fallidos = 0, bloqueado_hasta = NULL, actualizado_en = now()
                WHERE id = ?""", userId);
    }

    @Override
    public void updateLanguage(long userId, Idioma language) {
        jdbc.update("UPDATE usuario SET idioma = ?, actualizado_en = now() WHERE id = ?", language.codigo(), userId);
    }

    private static AuthUser map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime lockedUntil = rs.getObject("bloqueado_hasta", OffsetDateTime.class);
        return new AuthUser(
                rs.getLong("id"),
                rs.getString("correo"),
                rs.getString("contrasena_hash"),
                rs.getString("nombre"),
                Rol.valueOf(rs.getString("rol_codigo")),
                rs.getString("rol_nombre"),
                rs.getBoolean("activo"),
                rs.getBoolean("correo_confirmado"),
                rs.getInt("intentos_fallidos"),
                lockedUntil == null ? null : lockedUntil.toInstant(),
                Idioma.deCodigo(rs.getString("idioma")).orElse(Idioma.ES));
    }
}
