package pe.ccpip.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sesiones con token opaco (UUID) guardadas en el servidor. Expiran por inactividad
 * con expiracion deslizante: cada request valido renueva el plazo.
 */
@Service
public class SessionService {

    public record Session(String token, String userId, Instant lastAccess) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final Clock clock;

    public SessionService(@Value("${ccpip.auth.session-timeout:30m}") Duration timeout, Clock clock) {
        this.timeout = timeout;
        this.clock = clock;
    }

    public Session create(String userId) {
        Session session = new Session(UUID.randomUUID().toString(), userId, clock.instant());
        sessions.put(session.token(), session);
        return session;
    }

    /** Valida el token y, si sigue vigente, renueva su ultimo acceso. */
    public Optional<Session> validateAndTouch(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Session refreshed = sessions.computeIfPresent(token, (key, session) ->
                isExpired(session, now) ? null : new Session(key, session.userId(), now));
        return Optional.ofNullable(refreshed);
    }

    public void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public Duration timeout() {
        return timeout;
    }

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpired() {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> isExpired(session, now));
    }

    private boolean isExpired(Session session, Instant now) {
        return !now.isBefore(session.lastAccess().plus(timeout));
    }
}
