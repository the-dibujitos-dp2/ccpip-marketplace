package pe.ccpip.auth.service;

import java.time.Instant;
import java.util.Optional;

import pe.ccpip.auth.model.AuthUser;

/** Acceso a las cuentas de usuario. La implementacion real es {@link JdbcUserStore}. */
public interface UserStore {

    Optional<AuthUser> findByEmail(String email);

    Optional<AuthUser> findById(long id);

    /** Guarda el conteo de intentos fallidos y, si corresponde, hasta cuando queda bloqueada. */
    void recordFailedAttempts(long userId, int failedAttempts, Instant lockedUntil);

    void resetFailedAttempts(long userId);
}
