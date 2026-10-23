package pe.ccpip.ia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import pe.ccpip.ia.AnalistaEvidencias.Analisis;
import pe.ccpip.ia.AnalistaEvidencias.Evidencia;
import pe.ccpip.ia.AnalistaEvidencias.Recomendacion;
import pe.ccpip.ia.AnalistaEvidencias.Solicitud;
import pe.ccpip.ia.AnalistaEvidencias.TipoHallazgo;
import pe.ccpip.ia.AsistenteRedaccion.DatosDescripcion;
import pe.ccpip.ia.AsistenteRedaccion.DatosHistoria;
import pe.ccpip.ia.AsistenteRedaccion.Propuesta;
import pe.ccpip.ia.AsistenteRedaccion.Variante;

/**
 * CCPIP-48 y CCPIP-50 con Gemini, contra un servidor HTTP local que imita la API: no se llama a
 * Google en las pruebas. Verifica que la clave viaje en la cabecera, que se pida JSON con esquema,
 * que los datos del productor se envien como datos y que toda falla termine en "IA no disponible".
 */
class GeminiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLAVE = "clave-de-prueba";

    private HttpServer servidor;
    private final AtomicReference<String> respuesta = new AtomicReference<>();
    private final AtomicReference<Integer> estado = new AtomicReference<>(200);
    private final AtomicReference<Long> demora = new AtomicReference<>(0L);
    private final AtomicReference<String> ruta = new AtomicReference<>();
    private final AtomicReference<String> claveRecibida = new AtomicReference<>();
    private final AtomicReference<JsonNode> pedido = new AtomicReference<>();

    @BeforeEach
    void levantar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            ruta.set(intercambio.getRequestURI().toString());
            claveRecibida.set(intercambio.getRequestHeaders().getFirst("x-goog-api-key"));
            pedido.set(JSON.readTree(intercambio.getRequestBody()));
            try {
                Thread.sleep(demora.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] cuerpo = respuesta.get().getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado.get(), cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop(0);
    }

    private GeminiCliente cliente(Duration espera) {
        return new GeminiCliente("http://127.0.0.1:" + servidor.getAddress().getPort(), CLAVE, "modelo-x", espera);
    }

    /** Respuesta de generateContent cuyo texto es el JSON indicado. */
    private void responde(String jsonDelModelo) throws IOException {
        respuesta.set(JSON.writeValueAsString(JSON.createObjectNode()
                .set("candidates", JSON.createArrayNode().add(JSON.createObjectNode()
                        .put("finishReason", "STOP")
                        .set("content", JSON.createObjectNode().set("parts",
                                JSON.createArrayNode().add(JSON.createObjectNode().put("text", jsonDelModelo))))))));
    }

    private final DatosHistoria historia = new DatosHistoria("Aceite de sacha inchi", "Asoc. Nii Joi",
            "34 mujeres shipibo", "prensamos en frio", "nos permite ganar sin talar", "no quemamos ni talamos", Variante.NORMAL);

    @Test
    void laHistoriaSePideConLaClaveEnLaCabeceraYComoJsonConEsquema() throws IOException {
        responde("{\"titulo\": \"Aceite que cuida el bosque\", \"texto\": \"Treinta y cuatro mujeres shipibo prensan en frío.\"}");
        Propuesta p = new AsistenteGemini(cliente(Duration.ofSeconds(5))).proponerHistoria(historia);

        assertThat(p.titulo()).isEqualTo("Aceite que cuida el bosque");
        assertThat(p.texto()).isEqualTo("Treinta y cuatro mujeres shipibo prensan en frío.");
        assertThat(p.proveedor()).isEqualTo("gemini");
        assertThat(ruta.get()).isEqualTo("/v1beta/models/modelo-x:generateContent");
        assertThat(claveRecibida.get()).isEqualTo(CLAVE);
        assertThat(ruta.get()).doesNotContain(CLAVE);
        JsonNode cuerpo = pedido.get();
        assertThat(cuerpo.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(cuerpo.at("/generationConfig/responseSchema/required").toString()).contains("titulo", "texto");
        assertThat(cuerpo.at("/systemInstruction/parts/0/text").asText()).contains("No inventes origen");
        assertThat(cuerpo.at("/contents/0/parts/0/text").asText()).contains("34 mujeres shipibo", "Asoc. Nii Joi");
    }

    @Test
    void laDescripcionNoTraeTituloYUnTituloVacioDeLaHistoriaUsaElDelProducto() throws IOException {
        responde("{\"titulo\": \"\", \"texto\": \"Café de altura de la Coop. Río Ene.\"}");
        AsistenteGemini asistente = new AsistenteGemini(cliente(Duration.ofSeconds(5)));
        Propuesta d = asistente.proponerDescripcion(new DatosDescripcion("Café", List.of("Café"), "250 g", "Coop. Río Ene", null));
        assertThat(d.titulo()).isNull();
        assertThat(d.texto()).isEqualTo("Café de altura de la Coop. Río Ene.");

        Propuesta h = asistente.proponerHistoria(historia);
        assertThat(h.titulo()).isEqualTo("La historia de Aceite de sacha inchi");
    }

    @Test
    void unTextoMasLargoQueElEditorSeRecorta() throws IOException {
        responde("{\"titulo\": \"t\", \"texto\": \"" + "a".repeat(5000) + "\"}");
        Propuesta p = new AsistenteGemini(cliente(Duration.ofSeconds(5))).proponerHistoria(historia);
        assertThat(p.texto()).hasSize(AsistenteGemini.MAX_HISTORIA);
    }

    @Test
    void siGeminiNoRespondeATiempoLaIaNoEstaDisponible() throws IOException {
        responde("{\"titulo\": \"t\", \"texto\": \"x\"}");
        demora.set(1500L);
        AsistenteGemini asistente = new AsistenteGemini(cliente(Duration.ofMillis(300)));
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);
    }

    @Test
    void unErrorHttpUnaRespuestaBloqueadaOMalFormadaDejanLaIaNoDisponible() throws IOException {
        AsistenteGemini asistente = new AsistenteGemini(cliente(Duration.ofSeconds(5)));

        estado.set(429);
        respuesta.set("{\"error\": {\"message\": \"cuota\"}}");
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);

        estado.set(200);
        respuesta.set("{\"promptFeedback\": {\"blockReason\": \"SAFETY\"}}");
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);

        responde("esto no es json");
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);

        responde("{\"titulo\": \"t\", \"texto\": \"\"}");
        assertThatThrownBy(() -> asistente.proponerHistoria(historia)).isInstanceOf(IaNoDisponible.class);
    }

    @Test
    void sinClaveGeminiSeComportaComoNinguno() {
        IaConfig config = new IaConfig("", "modelo-x", "http://127.0.0.1:1", Duration.ofSeconds(1));
        assertThat(config.asistenteRedaccion("gemini").proveedor()).isEqualTo("ninguno");
        assertThat(config.analistaEvidencias("gemini").proveedor()).isEqualTo("ninguno");
        // Gemini es solo de texto: las imagenes siguen sin proveedor hasta conectar el modelo propio
        assertThat(config.validadorImagen("gemini").proveedor()).isEqualTo("ninguno");
    }

    @Test
    void conClaveSeEligeGeminiParaRedactarYAnalizar() {
        IaConfig config = new IaConfig("abc", "modelo-x", "http://127.0.0.1:1", Duration.ofSeconds(1));
        assertThat(config.asistenteRedaccion("gemini")).isInstanceOf(AsistenteGemini.class);
        assertThat(config.analistaEvidencias("gemini")).isInstanceOf(AnalistaGemini.class);
        assertThat(config.analistaEvidencias("gemini").simulado()).isFalse();
    }

    // ---------- CCPIP-50 ----------

    private final UUID certificado = UUID.randomUUID();
    private final UUID foto = UUID.randomUUID();
    private final Solicitud solicitud = new Solicitud("Café orgánico", "Coop. Río Ene", LocalDate.of(2026, 10, 22), List.of(
            new Evidencia(certificado, "CERTIFICADO", "Certificado orgánico del café", "Control Union",
                    LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "PDF"),
            new Evidencia(foto, "EVIDENCIA", "Secado del grano", null, null, null, "JPG")));

    @Test
    void elAnalisisEnviaSoloLoDeclaradoYGuardaHallazgosExplicables() throws IOException {
        responde("""
                {"recomendacion": "SIN_OBSERVACIONES", "resumen": "Todo en orden.", "hallazgos": [
                  {"evidencia_id": "%s", "tipo": "CONFORME", "detalle": "Vigente hasta el 01/01/2027."},
                  {"evidencia_id": "%s", "tipo": "CONFORME", "detalle": "Muestra el proceso."}]}
                """.formatted(certificado, foto));
        Analisis a = new AnalistaGemini(cliente(Duration.ofSeconds(5))).analizar(solicitud);

        assertThat(a.recomendacion()).isEqualTo(Recomendacion.SIN_OBSERVACIONES);
        assertThat(a.resumen()).startsWith(AnalistaGemini.AVISO).endsWith("Todo en orden.");
        assertThat(a.hallazgos()).extracting(AnalistaEvidencias.Hallazgo::evidenciaId).containsExactly(certificado, foto);
        String datos = pedido.get().at("/contents/0/parts/0/text").asText();
        assertThat(datos).contains(certificado.toString(), "Control Union", "2026-10-22");
        assertThat(pedido.get().at("/systemInstruction/parts/0/text").asText()).contains("Nunca apruebes ni rechaces");
    }

    @Test
    void conUnaObservacionLaRecomendacionEsRevisarAunqueElModeloDigaLoContrario() throws IOException {
        responde("""
                {"recomendacion": "SIN_OBSERVACIONES", "resumen": "", "hallazgos": [
                  {"evidencia_id": "%s", "tipo": "INCONSISTENCIA", "detalle": "No indica quién la emite."},
                  {"evidencia_id": null, "tipo": "FALTANTE", "detalle": "Falta el registro sanitario."},
                  {"evidencia_id": "%s", "tipo": "INVENTADO", "detalle": "x"},
                  {"evidencia_id": "no-existe", "tipo": "CONFORME", "detalle": "Evidencia que no está en la solicitud."}]}
                """.formatted(foto, certificado));
        Analisis a = new AnalistaGemini(cliente(Duration.ofSeconds(5))).analizar(solicitud);

        assertThat(a.recomendacion()).isEqualTo(Recomendacion.REVISAR);
        assertThat(a.resumen()).isEqualTo(AnalistaGemini.AVISO);
        assertThat(a.hallazgos()).extracting(AnalistaEvidencias.Hallazgo::tipo)
                .containsExactly(TipoHallazgo.INCONSISTENCIA, TipoHallazgo.FALTANTE);
        assertThat(a.hallazgos().get(1).evidenciaId()).isNull();
    }

    @Test
    void siGeminiFallaLaSolicitudPasaARevisionManual() {
        estado.set(500);
        respuesta.set("{}");
        assertThatThrownBy(() -> new AnalistaGemini(cliente(Duration.ofSeconds(5))).analizar(solicitud))
                .isInstanceOf(IaNoDisponible.class)
                .hasMessageContaining("revisión manual");
    }
}
