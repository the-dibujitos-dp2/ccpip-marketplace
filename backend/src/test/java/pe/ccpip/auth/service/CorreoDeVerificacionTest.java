package pe.ccpip.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class CorreoDeVerificacionTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "en | true",
            "en-GB,en;q=0.9 | true",
            "es | false",
            "es-PE,es;q=0.9,en;q=0.8 | false",
            "fr,en;q=0.5 | true",
            "fr,de | false",
            "en;q=0,es | false",
            ";;; | false"
    })
    void eligeInglesSoloSiEsElPreferidoEntreLosOfrecidos(String cabecera, boolean ingles) {
        assertThat(CorreoDeVerificacion.ingles(cabecera)).isEqualTo(ingles);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void sinCabeceraEsEspanol(String cabecera) {
        assertThat(CorreoDeVerificacion.ingles(cabecera)).isFalse();
    }
}
