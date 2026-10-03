package pe.ccpip.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pe.ccpip.auth.service.SessionService.Validation.Status;

class SessionServiceTest {

    private MutableClock clock;
    private InMemorySessionStore store;
    private SessionService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
        store = new InMemorySessionStore();
        service = new SessionService(store, Duration.ofMinutes(30), clock);
    }

    @Test
    void sesionNuevaEsValida() {
        String token = service.create(1L);
        SessionService.Validation validation = service.validate(token);
        assertThat(validation.status()).isEqualTo(Status.VALIDA);
        assertThat(validation.session().userId()).isEqualTo(1L);
    }

    @Test
    void laBaseGuardaElHashYNoElToken() {
        String token = service.create(1L);
        assertThat(store.sessions).doesNotContainKey(token)
                .containsKey(SessionService.hash(token));
        assertThat(SessionService.hash(token)).hasSize(64);
    }

    @Test
    void expiraTrasTreintaMinutosDeInactividadYLoDistingue() {
        String token = service.create(1L);
        clock.advance(Duration.ofMinutes(30));
        assertThat(service.validate(token).status()).isEqualTo(Status.EXPIRADA);
        // La fila se borra: la segunda vez ya no existe
        assertThat(service.validate(token).status()).isEqualTo(Status.INVALIDA);
    }

    @Test
    void cadaRequestValidoRenuevaElPlazo() {
        String token = service.create(1L);
        clock.advance(Duration.ofMinutes(20));
        assertThat(service.validate(token).status()).isEqualTo(Status.VALIDA);
        clock.advance(Duration.ofMinutes(20));
        assertThat(service.validate(token).status()).isEqualTo(Status.VALIDA);
    }

    @Test
    void noEscribeEnLaBaseEnCadaRequest() {
        String token = service.create(1L);
        clock.advance(Duration.ofSeconds(10));
        service.validate(token);
        service.validate(token);
        assertThat(store.touches).isZero();
        clock.advance(Duration.ofMinutes(1));
        service.validate(token);
        assertThat(store.touches).isEqualTo(1);
    }

    @Test
    void cierreManualInvalidaDeInmediato() {
        String token = service.create(1L);
        service.invalidate(token);
        assertThat(service.validate(token).status()).isEqualTo(Status.INVALIDA);
    }

    @Test
    void tokenDesconocidoONuloNoEsValido() {
        assertThat(service.validate("no-existe").status()).isEqualTo(Status.INVALIDA);
        assertThat(service.validate(null).status()).isEqualTo(Status.INVALIDA);
        assertThat(service.validate(" ").status()).isEqualTo(Status.INVALIDA);
    }

    @Test
    void usuarioDesactivadoPierdeLaSesion() {
        String token = service.create(1L);
        store.activeUsers.put(1L, false);
        assertThat(service.validate(token).status()).isEqualTo(Status.INVALIDA);
    }

    @Test
    void purgaEliminaSesionesVencidas() {
        String vencida = service.create(1L);
        clock.advance(Duration.ofMinutes(25));
        String vigente = service.create(2L);
        clock.advance(Duration.ofMinutes(10));

        service.purgeExpired();

        assertThat(store.sessions).doesNotContainKey(SessionService.hash(vencida))
                .containsKey(SessionService.hash(vigente));
    }

    @Test
    void cadaSesionTieneUnTokenDistinto() {
        assertThat(service.create(1L)).isNotEqualTo(service.create(1L));
    }
}
