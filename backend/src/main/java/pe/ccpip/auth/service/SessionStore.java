package pe.ccpip.auth.service;

import java.time.Instant;
import java.util.Optional;

import pe.ccpip.auth.model.Rol;

/** Persistencia de sesiones por hash del token. La implementacion real es {@link JdbcSessionStore}. */
public interface SessionStore {

    record StoredSession(String tokenHash, long userId, Rol role, boolean userActive, Instant lastAccess) {
    }

    void save(String tokenHash, long userId, Instant now);

    Optional<StoredSession> find(String tokenHash);

    void touch(String tokenHash, Instant now);

    void delete(String tokenHash);

    /** Borra las sesiones sin actividad desde antes de {@code threshold}. Devuelve cuantas borro. */
    int deleteInactiveSince(Instant threshold);
}
