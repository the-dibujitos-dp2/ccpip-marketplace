package pe.ccpip.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

    private static final String EMAIL = "consumidor@ccpip.test";

    private MutableClock clock;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
        service = new LoginAttemptService(5, Duration.ofMinutes(15), clock);
    }

    @Test
    void noBloqueaAntesDelQuintoIntento() {
        failTimes(4);
        assertThat(service.lockedUntil(EMAIL)).isEmpty();
    }

    @Test
    void bloqueaQuinceMinutosTrasCincoIntentos() {
        failTimes(5);
        assertThat(service.lockedUntil(EMAIL)).contains(clock.instant().plus(Duration.ofMinutes(15)));

        clock.advance(Duration.ofMinutes(14));
        assertThat(service.lockedUntil(EMAIL)).isPresent();

        clock.advance(Duration.ofMinutes(1));
        assertThat(service.lockedUntil(EMAIL)).isEmpty();
    }

    @Test
    void trasVencerElBloqueoElConteoEmpiezaDeCero() {
        failTimes(5);
        clock.advance(Duration.ofMinutes(15));

        service.loginFailed(EMAIL);
        assertThat(service.lockedUntil(EMAIL)).isEmpty();
    }

    @Test
    void loginExitosoReiniciaElConteo() {
        failTimes(4);
        service.loginSucceeded(EMAIL);
        service.loginFailed(EMAIL);
        assertThat(service.lockedUntil(EMAIL)).isEmpty();
    }

    @Test
    void elCorreoNoDistingueMayusculas() {
        for (int i = 0; i < 5; i++) {
            service.loginFailed(i % 2 == 0 ? EMAIL.toUpperCase() : EMAIL);
        }
        assertThat(service.lockedUntil(" " + EMAIL + " ")).isPresent();
    }

    private void failTimes(int times) {
        for (int i = 0; i < times; i++) {
            service.loginFailed(EMAIL);
        }
    }
}
