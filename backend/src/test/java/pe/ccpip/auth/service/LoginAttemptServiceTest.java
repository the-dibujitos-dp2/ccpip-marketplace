package pe.ccpip.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pe.ccpip.auth.model.AuthUser;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.Idioma;

class LoginAttemptServiceTest {

    private MutableClock clock;
    private FakeUserStore store;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
        store = new FakeUserStore();
        service = new LoginAttemptService(store, 5, Duration.ofMinutes(15), clock);
    }

    @Test
    void noBloqueaAntesDelQuintoIntento() {
        failTimes(4);
        assertThat(service.lockedUntil(store.user)).isEmpty();
        assertThat(store.user.failedAttempts()).isEqualTo(4);
    }

    @Test
    void bloqueaQuinceMinutosTrasCincoIntentos() {
        failTimes(5);
        assertThat(service.lockedUntil(store.user)).contains(clock.instant().plus(Duration.ofMinutes(15)));

        clock.advance(Duration.ofMinutes(14));
        assertThat(service.lockedUntil(store.user)).isPresent();

        clock.advance(Duration.ofMinutes(1));
        assertThat(service.lockedUntil(store.user)).isEmpty();
    }

    @Test
    void trasVencerElBloqueoElConteoEmpiezaDeCero() {
        failTimes(5);
        clock.advance(Duration.ofMinutes(15));

        service.loginFailed(store.user);
        assertThat(store.user.failedAttempts()).isEqualTo(1);
        assertThat(service.lockedUntil(store.user)).isEmpty();
    }

    @Test
    void loginExitosoReiniciaElConteo() {
        failTimes(4);
        service.loginSucceeded(store.user);
        assertThat(store.user.failedAttempts()).isZero();
        service.loginFailed(store.user);
        assertThat(service.lockedUntil(store.user)).isEmpty();
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            service.loginFailed(store.user);
        }
    }

    /** Un solo usuario en memoria que refleja lo que el servicio guarda. */
    private static class FakeUserStore implements UserStore {

        AuthUser user = new AuthUser(1L, "consumidor@ccpip.test", "hash", "Consumidor", Rol.CONSUMIDOR,
                "Consumidor final", true, true, 0, null, Idioma.ES);

        @Override
        public Optional<AuthUser> findByEmail(String email) {
            return Optional.of(user);
        }

        @Override
        public Optional<AuthUser> findById(long id) {
            return Optional.of(user);
        }

        @Override
        public void recordFailedAttempts(long userId, int failedAttempts, Instant lockedUntil) {
            user = copy(failedAttempts, lockedUntil);
        }

        @Override
        public void resetFailedAttempts(long userId) {
            user = copy(0, null);
        }

        private AuthUser copy(int failedAttempts, Instant lockedUntil) {
            return new AuthUser(user.id(), user.email(), user.passwordHash(), user.fullName(), user.role(),
                    user.roleName(), user.active(), user.emailConfirmed(), failedAttempts, lockedUntil, user.language());
        }

        @Override
        public void updateLanguage(long userId, Idioma language) {
            throw new UnsupportedOperationException("El servicio de intentos no cambia el idioma");
        }
    }
}
