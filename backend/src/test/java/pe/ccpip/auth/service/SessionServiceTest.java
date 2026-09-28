package pe.ccpip.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private MutableClock clock;
    private SessionService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
        service = new SessionService(Duration.ofMinutes(30), clock);
    }

    @Test
    void sesionNuevaEsValida() {
        SessionService.Session session = service.create("u-1");
        assertThat(service.validateAndTouch(session.token()))
                .hasValueSatisfying(s -> assertThat(s.userId()).isEqualTo("u-1"));
    }

    @Test
    void expiraTrasTreintaMinutosDeInactividad() {
        String token = service.create("u-1").token();
        clock.advance(Duration.ofMinutes(30));
        assertThat(service.validateAndTouch(token)).isEmpty();
    }

    @Test
    void cadaRequestValidoRenuevaElPlazo() {
        String token = service.create("u-1").token();
        clock.advance(Duration.ofMinutes(20));
        assertThat(service.validateAndTouch(token)).isPresent();
        clock.advance(Duration.ofMinutes(20));
        assertThat(service.validateAndTouch(token)).isPresent();
    }

    @Test
    void invalidarEliminaLaSesion() {
        String token = service.create("u-1").token();
        service.invalidate(token);
        assertThat(service.validateAndTouch(token)).isEmpty();
    }

    @Test
    void tokenDesconocidoONuloNoEsValido() {
        assertThat(service.validateAndTouch("no-existe")).isEmpty();
        assertThat(service.validateAndTouch(null)).isEmpty();
    }

    @Test
    void purgaEliminaSesionesVencidas() {
        String vencida = service.create("u-1").token();
        clock.advance(Duration.ofMinutes(25));
        String vigente = service.create("u-2").token();
        clock.advance(Duration.ofMinutes(10));

        service.purgeExpired();

        assertThat(service.validateAndTouch(vencida)).isEmpty();
        assertThat(service.validateAndTouch(vigente)).isPresent();
    }
}
