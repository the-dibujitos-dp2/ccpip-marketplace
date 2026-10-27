package pe.ccpip.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-43 (RF-29): el productor registra un premio vinculado a un producto propio y a uno de sus lotes,
 * con emisor, fecha, vigencia y su archivo de respaldo. CCPIP-169 (RF-138): el visitante ve los premios del
 * producto sin el archivo. Transaccional: lo que arma la prueba no llega a las demas.
 */
@Transactional
class PremiosTest extends IntegrationTest {

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    private static final String PREMIOS = "/api/productor/productos/{id}/premios";
    private static final String PUBLICOS = "/api/productos/{id}/premios";

    private Cookie productor;
    private long miel;
    private long lote;

    @BeforeEach
    void preparar() throws Exception {
        productor = loginAs("productor@ccpip.test");
        miel = productoConLote("Miel de abeja nativa 250 g", "PUBLICADO", "PUNTO_ACOPIO", HOY.plusMonths(6));
        lote = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?", Long.class, miel);
    }

    // --- CCPIP-43: registrar ---

    @Test
    void registraUnPremioConSuLoteEmisorFechaVigenciaYArchivo() throws Exception {
        registrar(productor, miel, PDF, "medalla.pdf", "Medalla de oro", "Salón del Cacao y Chocolate",
                HOY.minusMonths(2), HOY.plusYears(1), lote)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productoId").value(miel))
                .andExpect(jsonPath("$.lote.id").value(lote))
                .andExpect(jsonPath("$.titulo").value("Medalla de oro"))
                .andExpect(jsonPath("$.emisor").value("Salón del Cacao y Chocolate"))
                .andExpect(jsonPath("$.otorgadoEn").value(HOY.minusMonths(2).toString()))
                .andExpect(jsonPath("$.vigente").value(true))
                .andExpect(jsonPath("$.formato").value("PDF"))
                .andExpect(jsonPath("$.url").exists());

        mockMvc.perform(get(PREMIOS, miel).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premios.length()").value(1))
                .andExpect(jsonPath("$.lotes[0].id").value(lote))
                .andExpect(jsonPath("$.puedeRegistrar").value(true))
                .andExpect(jsonPath("$.maximo").value(PremiosService.MAX_PREMIOS));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'PREMIO'", Integer.class, miel))
                .isEqualTo(1);
    }

    @Test
    void sinVencimientoElPremioSiempreEstaVigente() throws Exception {
        registrar(productor, miel, PDF, "reconocimiento.pdf", "Reconocimiento regional", "Gobierno Regional",
                HOY.minusYears(3), null, lote)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vigenteHasta").doesNotExist())
                .andExpect(jsonPath("$.vigente").value(true));
    }

    @Test
    void rechazaDatosIncompletosOInvalidos() throws Exception {
        registrar(productor, miel, PDF, "a.pdf", "Medalla", " ", HOY, null, lote)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PREMIO_EMISOR_REQUERIDO"));
        registrar(productor, miel, PDF, "a.pdf", " ", "Emisor", HOY, null, lote)
                .andExpect(jsonPath("$.code").value("PREMIO_TITULO_REQUERIDO"));
        registrar(productor, miel, PDF, "a.pdf", "Medalla", "Emisor", null, null, lote)
                .andExpect(jsonPath("$.code").value("PREMIO_FECHA_REQUERIDA"));
        registrar(productor, miel, PDF, "a.pdf", "Medalla", "Emisor", HOY.plusDays(1), null, lote)
                .andExpect(jsonPath("$.code").value("PREMIO_FECHA_FUTURA"));
        registrar(productor, miel, PDF, "a.pdf", "Medalla", "Emisor", HOY, HOY.minusDays(1), lote)
                .andExpect(jsonPath("$.code").value("VIGENCIA_INVALIDA"));
        registrar(productor, miel, PDF, "a.pdf", "Medalla", "Emisor", HOY, null, null)
                .andExpect(jsonPath("$.code").value("PREMIO_LOTE_REQUERIDO"));
        registrar(productor, miel, "no es un pdf".getBytes(StandardCharsets.UTF_8), "a.pdf", "Medalla", "Emisor", HOY, null, lote)
                .andExpect(jsonPath("$.code").value("FORMATO_NO_PERMITIDO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM premio_producto WHERE producto_id = ?", Integer.class, miel))
                .isZero();
    }

    @Test
    void elLoteDebeSerDeEsteProducto() throws Exception {
        long otroLote = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id <> ? LIMIT 1", Long.class, miel);
        registrar(productor, miel, PDF, "a.pdf", "Medalla", "Emisor", HOY, null, otroLote)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PREMIO_LOTE_AJENO"));
    }

    @Test
    void unProductoAdmiteHastaDiezPremios() throws Exception {
        for (int i = 0; i < PremiosService.MAX_PREMIOS; i++) {
            registrar(productor, miel, PDF, "p.pdf", "Premio " + i, "Emisor", HOY, null, lote).andExpect(status().isCreated());
        }
        registrar(productor, miel, PDF, "p.pdf", "Uno mas", "Emisor", HOY, null, lote)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LIMITE_DE_PREMIOS"));
        mockMvc.perform(get(PREMIOS, miel).cookie(productor)).andExpect(jsonPath("$.puedeRegistrar").value(false));
    }

    // --- Permisos y archivo privado (RNF-05, RNF-08) ---

    @Test
    void otroProductorNoVeNiRegistraNiDescargaPremiosAjenos() throws Exception {
        long premio = idDe(registrar(productor, miel, PDF, "a.pdf", "Medalla", "Emisor", HOY, null, lote));
        Cookie otro = loginAs("productor2@ccpip.test");
        mockMvc.perform(get(PREMIOS, miel).cookie(otro))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        registrar(otro, miel, PDF, "a.pdf", "Medalla", "Emisor", HOY, null, lote).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/premios/{id}/archivo", premio).cookie(otro)).andExpect(status().isNotFound());
        mockMvc.perform(delete(PREMIOS + "/{premio}", miel, premio).cookie(otro)).andExpect(status().isNotFound());
        // Un consumidor no entra a las rutas del productor
        mockMvc.perform(get(PREMIOS, miel).cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
    }

    @Test
    void suProductorDescargaElArchivoSinCache() throws Exception {
        long premio = idDe(registrar(productor, miel, PDF, "medalla.pdf", "Medalla", "Emisor", HOY, null, lote));
        mockMvc.perform(get("/api/productor/premios/{id}/archivo", premio).cookie(productor))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void retirarloLoQuitaDeLaFichaYNoSeRetiraDosVeces() throws Exception {
        long premio = idDe(registrar(productor, miel, PDF, "a.pdf", "Medalla", "Emisor", HOY, null, lote));
        mockMvc.perform(delete(PREMIOS + "/{premio}", miel, premio).cookie(productor)).andExpect(status().isNoContent());
        mockMvc.perform(get(PREMIOS, miel).cookie(productor)).andExpect(jsonPath("$.premios.length()").value(0));
        mockMvc.perform(get(PUBLICOS, miel)).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(delete(PREMIOS + "/{premio}", miel, premio).cookie(productor))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PREMIO_NO_ENCONTRADO"));
        // Se conserva: el retiro es logico
        assertThat(jdbc.queryForObject("SELECT count(*) FROM premio_producto WHERE id = ?", Integer.class, premio)).isEqualTo(1);
    }

    // --- CCPIP-169: lo que ve el visitante ---

    @Test
    void elVisitanteVeEmisorFechaYVigenciaSinArchivoNiCodigoDeLote() throws Exception {
        registrar(productor, miel, PDF, "vencido.pdf", "Premio vencido", "Feria Regional", HOY.minusYears(2), HOY.minusYears(1), lote);
        registrar(productor, miel, PDF, "oro.pdf", "Medalla de oro", "Salón del Cacao", HOY.minusMonths(1), HOY.plusYears(1), lote);

        mockMvc.perform(get(PUBLICOS, miel))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Primero lo vigente
                .andExpect(jsonPath("$[0].titulo").value("Medalla de oro"))
                .andExpect(jsonPath("$[0].emisor").value("Salón del Cacao"))
                .andExpect(jsonPath("$[0].otorgadoEn").value(HOY.minusMonths(1).toString()))
                .andExpect(jsonPath("$[0].vigente").value(true))
                .andExpect(jsonPath("$[0].cosecha").exists())
                .andExpect(jsonPath("$[1].titulo").value("Premio vencido"))
                .andExpect(jsonPath("$[1].vigente").value(false))
                // Nada privado: ni archivo, ni su nombre, ni el codigo del lote
                .andExpect(jsonPath("$..url").doesNotExist())
                .andExpect(jsonPath("$..nombreArchivo").doesNotExist())
                .andExpect(jsonPath("$..codigo").doesNotExist())
                .andExpect(jsonPath("$..lote").doesNotExist());
    }

    @Test
    void unProductoFueraDelCatalogoNoMuestraPremios() throws Exception {
        registrar(productor, miel, PDF, "a.pdf", "Medalla", "Emisor", HOY, null, lote);
        jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", miel);
        mockMvc.perform(get(PUBLICOS, miel))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
    }

    // --- Apoyo ---

    private ResultActions registrar(Cookie cookie, long producto, byte[] contenido, String nombre, String titulo,
                                    String emisor, LocalDate otorgadoEn, LocalDate vigenteHasta, Long loteId) throws Exception {
        var peticion = multipart(PREMIOS, producto)
                .file(new MockMultipartFile("archivo", nombre, "application/octet-stream", contenido))
                .param("titulo", titulo).param("emisor", emisor);
        if (otorgadoEn != null) {
            peticion.param("otorgadoEn", otorgadoEn.toString());
        }
        if (vigenteHasta != null) {
            peticion.param("vigenteHasta", vigenteHasta.toString());
        }
        if (loteId != null) {
            peticion.param("loteId", loteId.toString());
        }
        return mockMvc.perform(peticion.cookie(cookie));
    }

    private long idDe(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }
}
