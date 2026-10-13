package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CCPIP-71: el administrador escribe a mano los datos de quien recoge; se comparan sin sus descuidos. */
class PersonaDeRecojoTest {

    @Test
    void elNombreNoDistingueMayusculasTildesNiEspaciosSobrantes() {
        assertThat(PersonaDeRecojo.mismoNombre("  maria   QUISPE ", "María Quispe")).isTrue();
        assertThat(PersonaDeRecojo.mismoNombre("Rosa Ancon Perez", "Rosa Ancón Pérez")).isTrue();
        assertThat(PersonaDeRecojo.mismoNombre("Niño Núñez", "nino nunez")).isTrue();
    }

    @Test
    void unNombreDistintoOIncompletoNoCoincide() {
        assertThat(PersonaDeRecojo.mismoNombre("María", "María Quispe")).isFalse();
        assertThat(PersonaDeRecojo.mismoNombre("Quispe María", "María Quispe")).isFalse();
        assertThat(PersonaDeRecojo.mismoNombre("Mario Quispe", "María Quispe")).isFalse();
    }

    @Test
    void sinNombreNadaCoincide() {
        assertThat(PersonaDeRecojo.mismoNombre("   ", "   ")).isFalse();
        assertThat(PersonaDeRecojo.mismoNombre("María", null)).isFalse();
        assertThat(PersonaDeRecojo.mismoNombre(null, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"987654321", "987 654 321", "987-654-321", "+51 987 654 321", "51987654321", "(987) 654321"})
    void elCelularSeNormalizaANueveDigitos(String escrito) {
        assertThat(PersonaDeRecojo.celular(escrito)).isEqualTo(Optional.of("987654321"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "12345", "887654321", "9876543210", "+52 987 654 321", "abc", "98765432a"})
    void loQueNoEsUnCelularPeruanoSeRechaza(String escrito) {
        assertThat(PersonaDeRecojo.celular(escrito)).isEmpty();
    }

    @Test
    void sinCelularNoHayCelular() {
        assertThat(PersonaDeRecojo.celular(null)).isEmpty();
    }
}
