package pe.ccpip.fidelizacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CCPIP-86: el calculo de puntos, con los ejemplos de la decision del 13/11. */
class ReglaPuntosTest {

    @ParameterizedTest(name = "S/ {0} da {1} puntos con la regla base")
    @CsvSource({"85.50, 8", "9.90, 0", "120, 12", "10, 1", "0, 0"})
    void laReglaBaseDaUnPuntoPorCadaDiezSolesRedondeandoHaciaAbajo(String monto, int puntos) {
        assertThat(ReglaPuntos.BASE.puntosDe(new BigDecimal(monto))).isEqualTo(puntos);
    }

    @ParameterizedTest(name = "S/ {0} da {1} puntos con 10 puntos por cada S/ 1 y tope {2}")
    @CsvSource(value = {"85.50, 850, NULL", "85.50, 500, 500", "0.99, 0, NULL"}, nullValues = "NULL")
    void unaCampanaCuentaCadaTramoCompletoYRespetaSuTope(String monto, int puntos, Integer tope) {
        ReglaPuntos campana = new ReglaPuntos(10, BigDecimal.ONE, tope);
        assertThat(campana.puntosDe(new BigDecimal(monto))).isEqualTo(puntos);
    }

    @ParameterizedTest(name = "{0} puntos por cada S/ {1} mejora la base: {2}")
    @CsvSource({"1, 10, false", "1, 11, false", "2, 10, true", "10, 1, true"})
    void seComparaPorPuntosPorSol(int puntos, String cadaSoles, boolean mejora) {
        ReglaPuntos regla = new ReglaPuntos(puntos, new BigDecimal(cadaSoles), null);
        assertThat(regla.porSol().compareTo(ReglaPuntos.BASE.porSol()) > 0).isEqualTo(mejora);
    }
}
