package pe.ccpip.comun;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdiomaTest {

    @Test
    void reconoceLosCodigosSoportadosSinDistinguirMayusculas() {
        assertThat(Idioma.deCodigo("es")).contains(Idioma.ES);
        assertThat(Idioma.deCodigo(" EN ")).contains(Idioma.EN);
        assertThat(Idioma.EN.codigo()).isEqualTo("en");
    }

    @Test
    void rechazaLoQueNoEsUnIdiomaDeLaPlataforma() {
        assertThat(Idioma.deCodigo("fr")).isEmpty();
        assertThat(Idioma.deCodigo("")).isEmpty();
        assertThat(Idioma.deCodigo(null)).isEmpty();
    }

    @Test
    void tomaElPrimerIdiomaSoportadoDeAcceptLanguage() {
        assertThat(Idioma.deCabecera("en-US,en;q=0.9,es;q=0.8")).isEqualTo(Idioma.EN);
        assertThat(Idioma.deCabecera("fr-FR,fr;q=0.9,en;q=0.5")).isEqualTo(Idioma.EN);
        assertThat(Idioma.deCabecera("es-PE")).isEqualTo(Idioma.ES);
    }

    @Test
    void sinCabeceraOConUnaInvalidaElIdiomaEsEspanol() {
        assertThat(Idioma.deCabecera(null)).isEqualTo(Idioma.ES);
        assertThat(Idioma.deCabecera("")).isEqualTo(Idioma.ES);
        assertThat(Idioma.deCabecera("fr")).isEqualTo(Idioma.ES);
        assertThat(Idioma.deCabecera("en;q=0")).isEqualTo(Idioma.ES);
        assertThat(Idioma.deCabecera("no es una cabecera ;;;")).isEqualTo(Idioma.ES);
    }
}
