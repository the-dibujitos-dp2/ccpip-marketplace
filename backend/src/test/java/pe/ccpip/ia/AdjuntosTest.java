package pe.ccpip.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import pe.ccpip.ia.AnalistaEvidencias.Evidencia;
import pe.ccpip.ia.ModeloTexto.Adjunto;

/** CCPIP-491: los archivos que se envian al modelo se comparan y se muestran por su contenido, sin volcarlo. */
class AdjuntosTest {

    private static final byte[] PDF = "%PDF-1.4 x".getBytes(StandardCharsets.UTF_8);

    @Test
    void unAdjuntoSeComparaPorContenidoYNoSeVuelcaAlRegistro() {
        Adjunto a = new Adjunto("Archivo de la evidencia 1:", "1.pdf", "application/pdf", PDF);
        Adjunto igual = new Adjunto("Archivo de la evidencia 1:", "1.pdf", "application/pdf", PDF.clone());
        Adjunto otro = new Adjunto("Archivo de la evidencia 2:", "2.pdf", "application/pdf", PDF);

        assertThat(a).isEqualTo(igual).hasSameHashCodeAs(igual).isNotEqualTo(otro).isNotEqualTo("x");
        assertThat(a.toString()).isEqualTo("Adjunto[Archivo de la evidencia 1:, application/pdf, " + PDF.length + " bytes]");
        assertThat(java.util.Base64.getDecoder().decode(a.base64())).isEqualTo(PDF);
    }

    @Test
    void unaEvidenciaSinArchivoNoTraePdfYSeComparaPorIdYContenido() {
        UUID id = UUID.randomUUID();
        Evidencia sinPdf = new Evidencia(id, "CERTIFICADO", "Certificado", "X", null, LocalDate.of(2027, 1, 1), "PDF");
        Evidencia conPdf = new Evidencia(id, "CERTIFICADO", "Certificado", "X", null, LocalDate.of(2027, 1, 1), "PDF", PDF);
        Evidencia mismoPdf = new Evidencia(id, "CERTIFICADO", "Otro título", "Y", null, null, "PDF", PDF.clone());

        assertThat(sinPdf.pdf()).isNull();
        assertThat(conPdf).isEqualTo(mismoPdf).hasSameHashCodeAs(sinPdf).isNotEqualTo(sinPdf).isNotEqualTo("x");
        assertThat(conPdf.toString()).contains(id.toString(), "pdf=" + PDF.length + " bytes").doesNotContain("%PDF");
        assertThat(sinPdf.toString()).contains("pdf=0 bytes");
    }

    @Test
    void unProveedorQueNoLeeArchivosSoloAtiendeConsultasSinAdjuntos() {
        JsonNode vacio = JsonNodeFactory.instance.objectNode();
        ModeloTexto soloTexto = new ModeloTexto() {
            @Override
            public JsonNode generar(String instrucciones, String datos, Map<String, Object> esquema, double temperatura) {
                return vacio;
            }

            @Override
            public String proveedor() {
                return "texto";
            }
        };
        assertThat(soloTexto.generar("i", "d", List.of(), Map.of(), 0.2)).isSameAs(vacio);
        List<Adjunto> uno = List.of(new Adjunto("e", "1.pdf", "application/pdf", PDF));
        assertThatThrownBy(() -> soloTexto.generar("i", "d", uno, Map.of(), 0.2))
                .isInstanceOf(ModeloTexto.Falla.class).hasMessageContaining("texto");
    }
}
