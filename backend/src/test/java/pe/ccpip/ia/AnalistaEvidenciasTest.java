package pe.ccpip.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import pe.ccpip.ia.AnalistaEvidencias.Evidencia;
import pe.ccpip.ia.AnalistaEvidencias.Recomendacion;
import pe.ccpip.ia.AnalistaEvidencias.Solicitud;
import pe.ccpip.ia.AnalistaEvidencias.TipoHallazgo;

/** CCPIP-50: selector del proveedor y reglas fijas del analista simulado (resultado de prueba). */
class AnalistaEvidenciasTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 17);

    @Test
    void sinProveedorConfiguradoSeDerivaARevisionManual() {
        AnalistaEvidencias analista = new IaConfig().analistaEvidencias("ninguno");
        assertThat(analista.simulado()).isFalse();
        assertThatThrownBy(() -> analista.analizar(new Solicitud("Miel", "Asoc", HOY, List.of())))
                .isInstanceOfSatisfying(IaNoDisponible.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("revisión manual");
                });
        assertThatThrownBy(() -> new IaConfig().analistaEvidencias("otro")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elSimuladoSeIdentificaComoResultadoDePrueba() {
        AnalistaEvidencias simulado = new IaConfig().analistaEvidencias("simulado");
        assertThat(simulado.simulado()).isTrue();
        assertThat(simulado.proveedor()).isEqualTo("simulado");
        assertThat(simulado.analizar(new Solicitud("Miel", "Asoc", HOY, List.of())).resumen())
                .startsWith("Resultado de prueba").contains("no es un análisis real");
    }

    @Test
    void sinObservacionesCuandoElCertificadoEsDelProductoYHayEvidenciaDelProceso() {
        Evidencia certificado = new Evidencia(UUID.randomUUID(), "CERTIFICADO", "Certificado orgánico de Café", "Control Unión",
                HOY.minusYears(1), HOY.plusYears(1), "PDF");
        Evidencia fotos = new Evidencia(UUID.randomUUID(), "EVIDENCIA", "Fotos", null, null, null, "JPG");

        AnalistaEvidencias.Analisis r = new AnalistaSimulado().analizar(
                new Solicitud("Cafe molido 250 g", "Coop", HOY, List.of(certificado, fotos)));

        assertThat(r.recomendacion()).isEqualTo(Recomendacion.SIN_OBSERVACIONES);
        assertThat(r.hallazgos()).allMatch(h -> h.tipo() == TipoHallazgo.CONFORME);
        assertThat(r.hallazgos()).extracting(AnalistaEvidencias.Hallazgo::evidenciaId)
                .containsExactly(certificado.id(), fotos.id());
    }

    @Test
    void cadaObservacionExplicaQueEvidenciaLaOriginaYPorQue() {
        Evidencia vencido = new Evidencia(UUID.randomUUID(), "CERTIFICADO", "Certificado de miel", "X", null, HOY.minusDays(1), "PDF");
        Evidencia futuro = new Evidencia(UUID.randomUUID(), "DOCUMENTO", "Acta", null, HOY.plusDays(5), null, "PDF");
        Evidencia otroProducto = new Evidencia(UUID.randomUUID(), "CERTIFICADO", "Certificado de cacao", "X", null, HOY.plusYears(1), "PDF");
        Evidencia sinEmisor = new Evidencia(UUID.randomUUID(), "CERTIFICADO", "Registro de miel", " ", null, HOY.plusYears(1), "PNG");

        AnalistaEvidencias.Analisis r = new AnalistaSimulado().analizar(
                new Solicitud("Miel nativa", "Asoc", HOY, List.of(vencido, futuro, otroProducto, sinEmisor)));

        assertThat(r.recomendacion()).isEqualTo(Recomendacion.REVISAR);
        assertThat(r.hallazgos()).filteredOn(h -> vencido.id().equals(h.evidenciaId())).singleElement()
                .satisfies(h -> assertThat(h.detalle()).contains("Venció el 16/10/2026"));
        assertThat(r.hallazgos()).filteredOn(h -> futuro.id().equals(h.evidenciaId())).singleElement()
                .satisfies(h -> assertThat(h.detalle()).contains("22/10/2026"));
        assertThat(r.hallazgos()).filteredOn(h -> otroProducto.id().equals(h.evidenciaId())).singleElement()
                .satisfies(h -> assertThat(h.detalle()).contains("no menciona «Miel nativa»"));
        assertThat(r.hallazgos()).filteredOn(h -> sinEmisor.id().equals(h.evidenciaId())).singleElement()
                .satisfies(h -> assertThat(h.detalle()).contains("quién emite"));
        // Con un certificado vigente del producto (aunque observado) solo falta la evidencia del proceso
        assertThat(r.hallazgos()).filteredOn(h -> h.tipo() == TipoHallazgo.FALTANTE).singleElement()
                .satisfies(h -> assertThat(h.evidenciaId()).isNull());
        assertThat(r.resumen()).contains("5 observaciones en 4 evidencias");
    }
}
