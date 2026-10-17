package pe.ccpip.carrito;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pe.ccpip.carrito.Tarifas.Resolucion;
import pe.ccpip.carrito.Tarifas.Tarifa;

/** CCPIP-134 (RF-108): la tarifa segun la modalidad de la oferta y la cantidad pedida. */
class TarifasTest {

    private static final BigDecimal MINORISTA = new BigDecimal("18.00");
    private static final BigDecimal MAYORISTA = new BigDecimal("14.50");

    private static Resolucion resolver(String modalidad, int cantidad) {
        return Tarifas.resolver(modalidad, "MAYORISTA".equals(modalidad) ? null : MINORISTA,
                "MINORISTA".equals(modalidad) ? null : MAYORISTA, "MINORISTA".equals(modalidad) ? null : 24,
                BigDecimal.valueOf(cantidad));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 24, 500})
    void unaOfertaMinoristaSiempreUsaElPrecioMinoristaSinFaltante(int cantidad) {
        assertThat(resolver("MINORISTA", cantidad)).isEqualTo(new Resolucion(Tarifa.MINORISTA, MINORISTA, true, null));
    }

    @Test
    void unaOfertaMinoristaIgnoraUnPrecioMayoristaQueHayaQuedadoGuardado() {
        assertThat(Tarifas.resolver("MINORISTA", MINORISTA, MAYORISTA, 24, BigDecimal.valueOf(30)))
                .isEqualTo(new Resolucion(Tarifa.MINORISTA, MINORISTA, true, null));
    }

    @Test
    void conAmbasBajoElMinimoSeAplicaElMinoristaYSeInformaCuantoFalta() {
        assertThat(resolver("AMBAS", 12))
                .isEqualTo(new Resolucion(Tarifa.MINORISTA, MINORISTA, true, BigDecimal.valueOf(12)));
        assertThat(resolver("AMBAS", 23).faltante()).isEqualByComparingTo("1");
    }

    @ParameterizedTest
    @ValueSource(ints = {24, 30})
    void conAmbasDesdeElMinimoSeAplicaElMayoristaAutomaticamente(int cantidad) {
        assertThat(resolver("AMBAS", cantidad)).isEqualTo(new Resolucion(Tarifa.MAYORISTA, MAYORISTA, true, BigDecimal.ZERO));
    }

    @Test
    void soloMayoristaBajoElMinimoNoSePuedeComprarYDiceCuantoFalta() {
        Resolucion r = resolver("MAYORISTA", 12);
        assertThat(r.comprable()).isFalse();
        assertThat(r.tarifa()).isEqualTo(Tarifa.MAYORISTA);
        assertThat(r.precio()).isEqualTo(MAYORISTA);
        assertThat(r.faltante()).isEqualByComparingTo("12");
    }

    @Test
    void soloMayoristaDesdeElMinimoSeCompraAPrecioMayorista() {
        assertThat(resolver("MAYORISTA", 24)).isEqualTo(new Resolucion(Tarifa.MAYORISTA, MAYORISTA, true, BigDecimal.ZERO));
    }

    @Test
    void ambasSinPrecioMinoristaSeVendeSoloPorMayor() {
        Resolucion r = Tarifas.resolver("AMBAS", null, MAYORISTA, 24, BigDecimal.valueOf(5));
        assertThat(r.comprable()).isFalse();
        assertThat(r.faltante()).isEqualByComparingTo("19");
    }

    @Test
    void sinPrecioAplicableNoHayTarifa() {
        Resolucion sinPrecio = new Resolucion(null, null, false, null);
        assertThat(Tarifas.resolver("MINORISTA", null, null, null, BigDecimal.ONE)).isEqualTo(sinPrecio);
        // Un mayorista sin minimo no puede aplicarse nunca
        assertThat(Tarifas.resolver("MAYORISTA", null, MAYORISTA, null, BigDecimal.TEN)).isEqualTo(sinPrecio);
    }
}
