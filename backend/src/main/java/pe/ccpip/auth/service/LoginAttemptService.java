package pe.ccpip.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Bloqueo temporal de cuenta tras varios intentos fallidos de inicio de sesion.
 * Los intentos se cuentan por correo, exista o no la cuenta, para no revelar
 * que correos estan registrados.
 */
@Service
public class LoginAttemptService {

    private record Attempts(int failures, Instant lockedUntil) {
    }

    private final Map<String, Attempts> attemptsByEmail = new ConcurrentHashMap<>();
    private final int maxFailedAttempts;
    private final Duration lockDuration;
    private final Clock clock;

    public LoginAttemptService(
            @Value("${ccpip.auth.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${ccpip.auth.lock-duration:15m}") Duration lockDuration,
            Clock clock) {
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    /** Si la cuenta esta bloqueada, devuelve hasta cuando. */
    public Optional<Instant> lockedUntil(String email) {
        Attempts attempts = attemptsByEmail.get(InMemoryUserStore.normalize(email));
        if (attempts == null || attempts.lockedUntil() == null) {
            return Optional.empty();
        }
        if (clock.instant().isBefore(attempts.lockedUntil())) {
            return Optional.of(attempts.lockedUntil());
        }
        return Optional.empty();
    }

    public void loginFailed(String email) {
        Instant now = clock.instant();
        attemptsByEmail.compute(InMemoryUserStore.normalize(email), (key, current) -> {
            // Un bloqueo vencido reinicia el conteo
            int failures = (current == null || isLockExpired(current, now)) ? 1 : current.failures() + 1;
            Instant lockedUntil = failures >= maxFailedAttempts ? now.plus(lockDuration) : null;
            return new Attempts(failures, lockedUntil);
        });
    }

    public void loginSucceeded(String email) {
        attemptsByEmail.remove(InMemoryUserStore.normalize(email));
    }

    private static boolean isLockExpired(Attempts attempts, Instant now) {
        return attempts.lockedUntil() != null && !now.isBefore(attempts.lockedUntil());
    }
}
