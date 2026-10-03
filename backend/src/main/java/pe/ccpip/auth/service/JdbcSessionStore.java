package pe.ccpip.auth.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.ccpip.auth.model.Rol;

@Repository
public class JdbcSessionStore implements SessionStore {

    private final JdbcTemplate jdbc;

    public JdbcSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String tokenHash, long userId, Instant now) {
        OffsetDateTime at = utc(now);
        jdbc.update("INSERT INTO sesion (token_hash, usuario_id, creado_en, ultimo_acceso) VALUES (?, ?, ?, ?)",
                tokenHash, userId, at, at);
    }

    @Override
    public Optional<StoredSession> find(String tokenHash) {
        return jdbc.query("""
                SELECT s.token_hash, s.usuario_id, s.ultimo_acceso, r.codigo AS rol_codigo, u.activo
                FROM sesion s
                JOIN usuario u ON u.id = s.usuario_id
                JOIN rol r ON r.id = u.rol_id
                WHERE s.token_hash = ?""",
                (rs, row) -> new StoredSession(
                        rs.getString("token_hash"),
                        rs.getLong("usuario_id"),
                        Rol.valueOf(rs.getString("rol_codigo")),
                        rs.getBoolean("activo"),
                        rs.getObject("ultimo_acceso", OffsetDateTime.class).toInstant()),
                tokenHash).stream().findFirst();
    }

    @Override
    public void touch(String tokenHash, Instant now) {
        jdbc.update("UPDATE sesion SET ultimo_acceso = ? WHERE token_hash = ?", utc(now), tokenHash);
    }

    @Override
    public void delete(String tokenHash) {
        jdbc.update("DELETE FROM sesion WHERE token_hash = ?", tokenHash);
    }

    @Override
    public int deleteInactiveSince(Instant threshold) {
        return jdbc.update("DELETE FROM sesion WHERE ultimo_acceso <= ?", utc(threshold));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
