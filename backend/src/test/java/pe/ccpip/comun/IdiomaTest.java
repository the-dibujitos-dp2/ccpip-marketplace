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
}
