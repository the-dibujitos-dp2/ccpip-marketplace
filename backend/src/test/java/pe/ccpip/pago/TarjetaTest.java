package pe.ccpip.pago;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import pe.ccpip.comun.ErrorNegocio;

/** Validacion de la tarjeta antes de cobrar (CCPIP-62): Luhn, marca, vencimiento y CVV. */
class TarjetaTest {

    private static Tarjeta tarjeta(String numero, String cvv) {
        return new Tarjeta(numero, "ANA QUISPE", 12, 2030, cvv);
    }

    @Test
    void reconoceLasMarcasAceptadas() {
        assertThat(tarjeta("4111 1111 1111 1111", "123").validar()).isEqualTo(Tarjeta.Marca.VISA);
        assertThat(tarjeta("5555555555554444", "123").validar()).isEqualTo(Tarjeta.Marca.MASTERCARD);
        assertThat(tarjeta("378282246310005", "1234").validar()).isEqualTo(Tarjeta.Marca.AMEX);
        assertThat(tarjeta("36227206271667", "123").validar()).isEqualTo(Tarjeta.Marca.DINERS);
    }

    @Test
    void rechazaUnNumeroQueNoPasaLuhn() {
        assertThatThrownBy(() -> tarjeta("4111111111111112", "123").validar())
                .isInstanceOf(ErrorNegocio.class)
                .satisfies(e -> assertThat(((ErrorNegocio) e).detalles()).contains("El número de tarjeta no es válido."));
    }

    @Test
    void amexPideCuatroDigitosDeSeguridadYLasDemasTres() {
        assertThatThrownBy(() -> tarjeta("378282246310005", "123").validar()).isInstanceOf(ErrorNegocio.class);
        assertThatThrownBy(() -> tarjeta("4111111111111111", "1234").validar()).isInstanceOf(ErrorNegocio.class);
    }

    @Test
    void rechazaUnaTarjetaVencida() {
        assertThatThrownBy(() -> new Tarjeta("4111111111111111", "ANA", 1, 2020, "123").validar())
                .isInstanceOf(ErrorNegocio.class)
                .satisfies(e -> assertThat(((ErrorNegocio) e).detalles()).contains("La tarjeta está vencida o la fecha no es válida."));
    }

    @Test
    void nuncaMuestraElNumeroCompletoEnLosLogs() {
        assertThat(tarjeta("4111 1111 1111 1111", "123").toString()).isEqualTo("Tarjeta[****1111]").doesNotContain("123");
    }
}
