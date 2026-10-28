package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pe.ccpip.autorizacion.AutorizacionModelos.VinculoCertificadoLote;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.support.IntegrationTest;

/** Transacciones reales y barrera en el seam JDBC, no una carrera contra la actualización de sesión. */
@TestPropertySource(properties = "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///ccpip_336_concurrency")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CertificadoLoteConcurrenciaTest extends IntegrationTest {
    @Autowired private EvidenciasService service;
    @Autowired private PlatformTransactionManager manager;
    @MockitoSpyBean private JdbcTemplate pausado;
    private long producto, productor, usuario, lote;
    private UUID certificado;
    private record Resultado(VinculoCertificadoLote vinculo, ErrorNegocio error) {}

    @BeforeEach void prepararFixtureCommitida() throws Exception {
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(pausado).isSameAs(jdbc);
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));
        producto = productoConLote("QA336 carrera " + System.nanoTime(), "PUBLICADO", "PUNTO_ACOPIO", hoy.plusMonths(5));
        jdbc.update("UPDATE autorizacion_producto SET estado='SOLICITADA', decidido_en=NULL, decidido_por=NULL, vigente_hasta=NULL WHERE producto_id=?", producto);
        productor = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id=?", Long.class, producto);
        usuario = jdbc.queryForObject("SELECT usuario_id FROM productor WHERE id=?", Long.class, productor);
        lote = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id=?", Long.class, producto);
        byte[] pdf = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        var response = mockMvc.perform(multipart("/api/productor/productos/{id}/evidencias", producto)
                .file(new MockMultipartFile("archivo", "cert.pdf", "application/octet-stream", pdf))
                .param("tipo", "CERTIFICADO").param("titulo", "Certificado carrera336")
                .param("emisor", "Emisor carrera336").param("vigenteDesde", hoy.minusDays(1).toString())
                .param("vigenteHasta", hoy.plusMonths(4).toString()).cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isCreated()).andReturn();
        certificado = UUID.fromString(objectMapper.readTree(response.getResponse().getContentAsString()).get("id").asText());
    }

    @Test void lecturaDelVinculoSerializaRetiroAntesDeLaEscritura() throws Exception {
        var leido = new CountDownLatch(1);
        var continuar = new CountDownLatch(1);
        var unaVez = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object valor = invocation.callRealMethod();
            if (unaVez.compareAndSet(true, false)) { leido.countDown(); esperar(continuar); }
            return valor;
        }).when(pausado).query(startsWith("SELECT vigente_desde, vigente_hasta, emisor, tipo, lote_id"), any(RowMapper.class), any(Object[].class));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var vincular = pool.submit(this::vincular);
            assertThat(leido.await(15, TimeUnit.SECONDS)).isTrue();
            var iniciado = new CountDownLatch(1);
            var retirar = pool.submit(() -> { iniciado.countDown(); service.retirar(productor, usuario, producto, certificado); return true; });
            assertThat(iniciado.await(5, TimeUnit.SECONDS)).isTrue();
            boolean retiroYaCommitido;
            try { retirar.get(2, TimeUnit.SECONDS); retiroYaCommitido = true; }
            catch (TimeoutException expected) { esperarBloqueoReal(); retiroYaCommitido = false; }
            continuar.countDown();
            Resultado resultado = vincular.get(15, TimeUnit.SECONDS);
            assertThat(retirar.get(15, TimeUnit.SECONDS)).isTrue();
            if (retiroYaCommitido) {
                // Antes del fix, el SELECT sin lock permite retiro commitido y luego escribe lote: RED.
                assertThat(resultado.error()).as("no escribir tras un retiro ya commitido").isNotNull();
                assertThat(resultado.error().code()).isEqualTo("EVIDENCIA_NO_ENCONTRADA");
                assertThat(loteRegistrado()).isNull();
            } else {
                // El nuevo SELECT mantiene el lock mientras está pausado, por eso retiro espera.
                assertThat(resultado.error()).isNull();
                assertThat(resultado.vinculo().loteId()).isEqualTo(lote);
                assertThat(loteRegistrado()).isEqualTo(lote);
            }
            assertThat(jdbc.queryForObject("SELECT retirada_en IS NOT NULL FROM evidencia_producto WHERE id=?", Boolean.class, certificado)).isTrue();
        } finally { continuar.countDown(); pool.shutdownNow(); }
    }

    @Test void retiroEnTransaccionAbiertaGanaAntesDelVinculoQueEspera() throws Exception {
        var retiradoSinCommit = new CountDownLatch(1);
        var confirmar = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var retirar = pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                service.retirar(productor, usuario, producto, certificado);
                retiradoSinCommit.countDown(); esperar(confirmar); return true;
            }));
            assertThat(retiradoSinCommit.await(15, TimeUnit.SECONDS)).isTrue();
            var vincular = pool.submit(this::vincular);
            esperarBloqueoReal();
            assertThat(vincular.isDone()).isFalse();
            confirmar.countDown();
            assertThat(retirar.get(15, TimeUnit.SECONDS)).isTrue();
            Resultado resultado = vincular.get(15, TimeUnit.SECONDS);
            assertThat(resultado.error()).isNotNull();
            assertThat(resultado.error().code()).isEqualTo("EVIDENCIA_NO_ENCONTRADA");
            assertThat(loteRegistrado()).isNull();
        } finally { confirmar.countDown(); pool.shutdownNow(); }
    }

    private Resultado vincular() {
        try { return new Resultado(service.vincularLote(productor, producto, certificado, lote), null); }
        catch (ErrorNegocio error) { return new Resultado(null, error); }
    }
    private Long loteRegistrado() { return jdbc.queryForObject("SELECT lote_id FROM evidencia_producto WHERE id=?", Long.class, certificado); }
    private static void esperar(CountDownLatch gate) {
        try { if (!gate.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Barrera agotada"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private void esperarBloqueoReal() throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int bloqueos = 0;
        while (System.nanoTime() < limite) {
            bloqueos = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted AND locktype='transactionid'", Integer.class);
            if (bloqueos > 0) break;
            Thread.sleep(20);
        }
        assertThat(bloqueos).as("espera PostgreSQL real, no sólo latencia de hilo").isPositive();
    }
}
