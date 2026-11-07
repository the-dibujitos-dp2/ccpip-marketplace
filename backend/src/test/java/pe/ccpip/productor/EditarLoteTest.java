package pe.ccpip.productor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-495: editar un lote ya registrado trae sus datos y solo cambia lo que el productor corrige (codigo y
 * fechas). La cantidad y la ubicacion no se editan: cambian con movimientos y transferencias (RNF-15).
 * Usa productor_nuevo, sembrado sin productos ni lotes.
 */
@Transactional
class EditarLoteTest extends IntegrationTest {

    private Cookie nuevo;
    private long acopioLima;
    private LocalDate hoy;
    private long producto;
    private long lote;

    @BeforeEach
    void productoConUnLote() throws Exception {
        nuevo = loginAs("productor_nuevo@ccpip.test");
        acopioLima = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = 'Centro Nacional Lima'", Long.class);
        hoy = LocalDate.now(LotesService.LIMA);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nombre", "Camu camu en polvo");
        d.put("lineas", List.of("SUPERFRUTOS"));
        d.put("unidad", "BOLSA");
        d.put("modalidad", "MINORISTA");
        d.put("precio", 20.00);
        String body = mockMvc.perform(post("/api/productor/productos").cookie(nuevo)
                .contentType(MediaType.APPLICATION_JSON).content(json(d))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        producto = objectMapper.readTree(body).get("id").asLong();
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("codigo", "L-0001");
        l.put("cantidad", "40");
        l.put("fechaProduccion", hoy.minusDays(5).toString());
        l.put("fechaVencimiento", hoy.plusDays(90).toString());
        l.put("ubicacionId", acopioLima);
        mockMvc.perform(post("/api/productor/productos/{id}/lotes", producto).cookie(nuevo)
                .contentType(MediaType.APPLICATION_JSON).content(json(l))).andExpect(status().isCreated());
        lote = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?", Long.class, producto);
    }

    private ResultActions editar(Cookie quien, long productoId, long loteId, String codigo, LocalDate produccion, LocalDate vence)
            throws Exception {
        Map<String, Object> d = new HashMap<>();
        d.put("codigo", codigo);
        d.put("fechaProduccion", produccion == null ? null : produccion.toString());
        d.put("fechaVencimiento", vence == null ? null : vence.toString());
        return mockMvc.perform(put("/api/productor/productos/{id}/lotes/{lote}", productoId, loteId).cookie(quien)
                .contentType(MediaType.APPLICATION_JSON).content(json(d)));
    }

    private long auditorias() {
        return jdbc.queryForObject("SELECT count(*) FROM producto_auditoria WHERE producto_id = ? AND accion = 'LOTE'",
                Long.class, producto);
    }

    @Test
    void corrige_solo_lo_que_cambia_y_deja_cantidad_y_ubicacion() throws Exception {
        long antes = auditorias();
        editar(nuevo, producto, lote, "l-0001-b", hoy.minusDays(5), hoy.plusDays(60))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].codigo").value("L-0001-B"))
                .andExpect(jsonPath("$.items[0].fechaProduccion").value(hoy.minusDays(5).toString()))
                .andExpect(jsonPath("$.items[0].fechaVencimiento").value(hoy.plusDays(60).toString()))
                .andExpect(jsonPath("$.items[0].cantidadActual").value(40))
                .andExpect(jsonPath("$.items[0].ubicacionId").value(acopioLima));

        assertThat(auditorias()).isEqualTo(antes + 1);
        String cambios = jdbc.queryForObject("""
                SELECT cambios::text FROM producto_auditoria WHERE producto_id = ? AND accion = 'LOTE'
                ORDER BY id DESC LIMIT 1""", String.class, producto);
        assertThat(cambios).contains("\"codigo\"").contains("L-0001-B").contains("\"fechaVencimiento\"")
                .doesNotContain("fechaProduccion");
        // Ningun movimiento nuevo: corregir datos no toca existencias
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ?", Long.class, lote))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT cantidad FROM stock WHERE producto_id = ?", BigDecimal.class, producto))
                .isEqualByComparingTo("40");
    }

    @Test
    void sin_cambios_no_audita_nada() throws Exception {
        long antes = auditorias();
        editar(nuevo, producto, lote, "L-0001", hoy.minusDays(5), hoy.plusDays(90)).andExpect(status().isOk());
        assertThat(auditorias()).isEqualTo(antes);
    }

    @Test
    void rechaza_datos_invalidos() throws Exception {
        editar(nuevo, producto, lote, " ", null, hoy.plusDays(30)).andExpect(status().isBadRequest());
        editar(nuevo, producto, lote, "L-0001", null, null).andExpect(jsonPath("$.code").value("FALTA_VENCIMIENTO"));
        editar(nuevo, producto, lote, "L-0001", null, hoy.minusDays(1)).andExpect(jsonPath("$.code").value("VENCIMIENTO_PASADO"));
        editar(nuevo, producto, lote, "L-0001", hoy.plusDays(1), hoy.plusDays(30)).andExpect(jsonPath("$.code").value("PRODUCCION_FUTURA"));
        editar(nuevo, producto, lote, "L-0001", hoy.minusDays(5), hoy.minusDays(6)).andExpect(jsonPath("$.code").value("VENCIMIENTO_PASADO"));

        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_vencimiento)
                VALUES (?, ?, 'L-0002', 5, 5, ?)""", producto, acopioLima, hoy.plusDays(30));
        editar(nuevo, producto, lote, "l-0002", null, hoy.plusDays(90)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOTE_REPETIDO"));
    }

    @Test
    void un_lote_vencido_no_se_revive_pero_se_le_puede_corregir_el_codigo() throws Exception {
        long vencido = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_vencimiento)
                VALUES (?, ?, 'L-V', 8, 8, ?) RETURNING id""", Long.class, producto, acopioLima, hoy.minusDays(2));
        editar(nuevo, producto, vencido, "L-V", null, hoy.plusDays(30)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOTE_VENCIDO"));
        editar(nuevo, producto, vencido, "L-V2", null, hoy.minusDays(2)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT codigo FROM lote WHERE id = ?", String.class, vencido)).isEqualTo("L-V2");
    }

    @Test
    void solo_el_productor_duenio_y_solo_lotes_de_ese_producto() throws Exception {
        long otroProducto = jdbc.queryForObject("""
                SELECT p.id FROM producto p JOIN productor r ON r.id = p.productor_id JOIN usuario u ON u.id = r.usuario_id
                WHERE u.correo = 'productor@ccpip.test' LIMIT 1""", Long.class);
        long loteAjeno = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ? LIMIT 1", Long.class, otroProducto);

        editar(nuevo, otroProducto, loteAjeno, "X", null, hoy.plusDays(30)).andExpect(status().isNotFound());
        editar(nuevo, producto, loteAjeno, "X", null, hoy.plusDays(30)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOTE_NO_ENCONTRADO"));
        editar(loginAs("productor@ccpip.test"), producto, lote, "X", null, hoy.plusDays(30)).andExpect(status().isNotFound());
        editar(loginAs("consumidor@ccpip.test"), producto, lote, "X", null, hoy.plusDays(30)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT codigo FROM lote WHERE id = ?", String.class, lote)).isEqualTo("L-0001");
    }
}
