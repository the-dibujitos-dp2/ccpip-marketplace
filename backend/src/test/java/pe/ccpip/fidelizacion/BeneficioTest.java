package pe.ccpip.fidelizacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import pe.ccpip.fidelizacion.Beneficio.Tipo;

/** CCPIP-88/89: el calculo de un descuento y de los puntos con el multiplicador del nivel. */
class BeneficioTest {

    @ParameterizedTest(name = "{0} {1} (minimo {2}, tope {3}) sobre S/ {4} descuenta S/ {5}")
    @CsvSource(value = {
            "MONTO, 10, 0, NULL, 50, 10.00",
            "MONTO, 10, 60, NULL, 50, 0.00",
            "PORCENTAJE, 10, 0, NULL, 85.50, 8.55",
            "PORCENTAJE, 10, 0, 4, 85.50, 4.00",
            // Nunca deja el pedido gratis: queda al menos S/ 1 por pagar
            "MONTO, 30, 0, NULL, 20, 19.00",
            "PORCENTAJE, 90, 0, NULL, 1, 0.00",
    }, nullValues = "NULL")
    void calculaElDescuentoSobreLosProductos(Tipo tipo, String valor, String minimo, String tope, String productos, String esperado) {
        Beneficio b = new Beneficio(tipo, new BigDecimal(valor), new BigDecimal(minimo), tope == null ? null : new BigDecimal(tope));
        assertThat(b.descuentoPara(new BigDecimal(productos))).isEqualByComparingTo(esperado);
    }

    @ParameterizedTest(name = "S/ {0} con multiplicador {1} da {2} puntos (tope {3})")
    @CsvSource(value = {"100, 1.5, 15, NULL", "85.50, 1.5, 12, NULL", "100, 2, 18, 18"}, nullValues = "NULL")
    void elNivelMultiplicaLosPuntosYElTopeLimitaElResultado(String monto, String multiplicador, int puntos, Integer tope) {
        ReglaPuntos regla = new ReglaPuntos(1, BigDecimal.TEN, tope);
        assertThat(regla.puntosDe(new BigDecimal(monto), new BigDecimal(multiplicador))).isEqualTo(puntos);
    }
}
