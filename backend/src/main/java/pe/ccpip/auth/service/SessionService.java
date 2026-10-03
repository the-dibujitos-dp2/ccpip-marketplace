package pe.ccpip.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import pe.ccpip.auth.model.AuthenticatedSession;

/**
 * Sesiones con token opaco aleatorio (ADR-05). En la base solo se guarda el SHA-256 del token,
 * asi quien lea la tabla sesion no puede reutilizar una sesion. Expiran por inactividad con
 * plazo deslizante: cada peticion valida renueva el ultimo acceso.
 */
@Service
public class SessionService {

    /** Resultado de validar un token. {@code session} solo viene cuando el estado es VALIDA. */
    public record Validation(Status status, AuthenticatedSession session) {
        public enum Status { VALIDA, EXPIRADA, INVALIDA }

        static Validation of(Status status) {
            return new Validation(status, null);
        }
    }

    /** Para no escribir en la base en cada peticion: el ultimo acceso se renueva como mucho una vez por minuto. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionStore store;
    private final Duration timeout;
    private final Clock clock;

    public SessionService(SessionStore store,
                          @Value("${ccpip.auth.session-timeout:30m}") Duration timeout,
                          Clock clock) {
        this.store = store;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** Crea la sesion y devuelve el token en claro, que solo viaja en la cookie. */
    public String create(long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.save(hash(token), userId, clock.instant());
        return token;
    }

    public Validation validate(String token) {
        if (token == null || token.isBlank()) {
            return Validation.of(Validation.Status.INVALIDA);
        }
        String tokenHash = hash(token);
        Optional<SessionStore.StoredSession> found = store.find(tokenHash);
        if (found.isEmpty()) {
            return Validation.of(Validation.Status.INVALIDA);
        }
        SessionStore.StoredSession session = found.get();
        Instant now = clock.instant();
        if (!session.userActive()) {
            store.delete(tokenHash);
            return Validation.of(Validation.Status.INVALIDA);
        }
        if (!now.isBefore(session.lastAccess().plus(timeout))) {
            store.delete(tokenHash);
            return Validation.of(Validation.Status.EXPIRADA);
        }
        if (!now.isBefore(session.lastAccess().plus(TOUCH_INTERVAL))) {
            store.touch(tokenHash, now);
        }
        return new Validation(Validation.Status.VALIDA, new AuthenticatedSession(session.userId(), session.role()));
    }

    /** Cierre manual: borra la fila, el token deja de servir de inmediato (RNF-03). */
    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            store.delete(hash(token));
        }
    }

    public Duration timeout() {
        return timeout;
    }

    @Scheduled(fixedDelay = 300_000)
    public void purgeExpired() {
        store.deleteInactiveSince(clock.instant().minus(timeout));
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
