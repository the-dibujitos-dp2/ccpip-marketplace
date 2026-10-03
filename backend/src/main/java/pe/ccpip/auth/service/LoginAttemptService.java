package pe.ccpip.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import pe.ccpip.auth.model.AuthUser;

/**
 * Bloqueo temporal de cuenta tras varios intentos fallidos (RNF-02). El conteo vive en
 * usuario.intentos_fallidos y usuario.bloqueado_hasta, asi sobrevive a reinicios y despliegues.
 * Un correo que no existe no se cuenta, pero recibe el mismo error que una clave incorrecta.
 */
@Service
public class LoginAttemptService {

    private final UserStore userStore;
    private final int maxFailedAttempts;
    private final Duration lockDuration;
    private final Clock clock;

    public LoginAttemptService(
            UserStore userStore,
            @Value("${ccpip.auth.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${ccpip.auth.lock-duration:15m}") Duration lockDuration,
            Clock clock) {
        this.userStore = userStore;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    /** Si la cuenta esta bloqueada, devuelve hasta cuando. */
    public Optional<Instant> lockedUntil(AuthUser user) {
        Instant lockedUntil = user.lockedUntil();
        if (lockedUntil != null && clock.instant().isBefore(lockedUntil)) {
            return Optional.of(lockedUntil);
        }
        return Optional.empty();
    }

    public void loginFailed(AuthUser user) {
        Instant now = clock.instant();
        // Un bloqueo vencido reinicia el conteo
        boolean lockExpired = user.lockedUntil() != null && !now.isBefore(user.lockedUntil());
        int failures = lockExpired ? 1 : user.failedAttempts() + 1;
        Instant lockedUntil = failures >= maxFailedAttempts ? now.plus(lockDuration) : null;
        userStore.recordFailedAttempts(user.id(), failures, lockedUntil);
    }

    public void loginSucceeded(AuthUser user) {
        if (user.failedAttempts() > 0 || user.lockedUntil() != null) {
            userStore.resetFailedAttempts(user.id());
        }
    }
}
