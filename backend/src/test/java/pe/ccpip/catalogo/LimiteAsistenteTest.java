package pe.ccpip.catalogo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import pe.ccpip.comun.ErrorNegocio;

class LimiteAsistenteTest {

    /** Reloj que se adelanta a mano. */
    private static final class Reloj extends Clock {
        private Instant ahora = Instant.parse("2026-10-23T12:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    @Test
    void limitaPorPersonaYSeLiberaAlPasarLaVentana() {
        Reloj reloj = new Reloj();
        LimiteAsistente limite = new LimiteAsistente(2, 100, reloj);
        limite.registrar("1.1.1.1");
        limite.registrar("1.1.1.1");
        assertThatThrownBy(() -> limite.registrar("1.1.1.1"))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("ASISTENTE_LIMITE"));
        // Otra persona no se ve afectada
        assertThatCode(() -> limite.registrar("2.2.2.2")).doesNotThrowAnyException();
        reloj.ahora = reloj.ahora.plus(Duration.ofSeconds(61));
        assertThatCode(() -> limite.registrar("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void limitaElTotalAunqueSeanPersonasDistintas() {
        LimiteAsistente limite = new LimiteAsistente(10, 3, new Reloj());
        limite.registrar("a");
        limite.registrar("b");
        limite.registrar("c");
        assertThatThrownBy(() -> limite.registrar("d"))
                .isInstanceOfSatisfying(ErrorNegocio.class, e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("ASISTENTE_OCUPADO"));
    }
}
