package pe.ccpip.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import pe.ccpip.ia.AsistenteRedaccion.DatosHistoria;
import pe.ccpip.ia.AsistenteRedaccion.Variante;

class AsistentesTest {

    private final DatosHistoria historia = new DatosHistoria("Aceite de sacha inchi", "Asoc. Nii Joi",
            "34 mujeres shipibo", "prensamos en frio", "nos permite ganar sin talar", "no quemamos ni talamos", Variante.NORMAL);

    @Test
    void sinProveedorConfiguradoLaIaNoEstaDisponibleYSeEscribeAMano() {
        AsistenteRedaccion asistente = new IaConfig().asistenteRedaccion("ninguno");
        assertThat(asistente.proveedor()).isEqualTo("ninguno");
        assertThatThrownBy(() -> asistente.proponerHistoria(historia))
                .isInstanceOfSatisfying(IaNoDisponible.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("IA_NO_DISPONIBLE");
                });
        assertThatThrownBy(() -> asistente.proponerDescripcion(
                new AsistenteRedaccion.DatosDescripcion("x", List.of(), null, "c", null))).isInstanceOf(IaNoDisponible.class);
    }

    @Test
    void unProveedorDesconocidoDetieneElArranque() {
        assertThatThrownBy(() -> new IaConfig().asistenteRedaccion("otro")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elSimuladoSoloUsaLasPalabrasDelProductor() {
        AsistenteRedaccion simulado = new IaConfig().asistenteRedaccion("simulado");
        AsistenteRedaccion.Propuesta normal = simulado.proponerHistoria(historia);
        assertThat(normal.titulo()).isEqualTo("La historia de Aceite de sacha inchi");
        assertThat(normal.texto()).startsWith("34 mujeres shipibo. Prensamos en frio.");
        AsistenteRedaccion.Propuesta corta = simulado.proponerHistoria(new DatosHistoria("p", "c", "uno", null, null, "dos", Variante.CORTA));
        assertThat(corta.texto()).isEqualTo("Uno. Dos.");
        assertThat(simulado.proponerHistoria(new DatosHistoria("p", "c", "a", "b", "c", "d", Variante.OTRA)).texto())
                .startsWith("C. A.");
        assertThat(simulado.proponerDescripcion(new AsistenteRedaccion.DatosDescripcion(
                "Cafe", List.of("Café"), "250 g", "Coop. Río Ene", "Tueste medio")).texto())
                .isEqualTo("Cafe de la línea café en presentación de 250 g, elaborado por Coop. Río Ene. Tueste medio");
    }
}
