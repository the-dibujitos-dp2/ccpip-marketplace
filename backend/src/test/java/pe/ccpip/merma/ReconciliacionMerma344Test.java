package pe.ccpip.merma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/** Caracterización CCPIP-344: consulta inclusiva, alcance servidor y ausencia de escrituras. */
@Transactional
class ReconciliacionMerma344Test extends IntegrationTest {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final LocalDate DIA_FIXTURE = LocalDate.of(2025, 6, 15);
    private static final String ADMIN = "admin_acopio@ccpip.test";
    private static final String RUTA = "/api/acopio/merma";

    @Autowired private MockMvc mvc;

    @Test
    void consultaInclusivaVaciaRangoInvertidoYPermisosSinModificarInventario() throws Exception {
        long lima = ubicacion("Centro Nacional Lima");
        long loreto = ubicacion("Centro Regional Loreto");
        long prod = producto("Merma reconciliacion " + UUID.randomUUID());
        long loteLima = lote(prod, "R344-LIMA", lima, "9");
        long loteLoreto = lote(prod, "R344-LORETO", loreto, "7");
        long motivo = jdbc.queryForObject("SELECT id FROM motivo_merma WHERE nombre = 'Daño'", Long.class);
        registrarMovimientoMerma(prod, lima, loteLima, motivo, "2", "Daño", "Damage", DIA_FIXTURE.atStartOfDay());
        registrarMovimientoMerma(prod, lima, loteLima, motivo, "3", "Daño", "Damage", DIA_FIXTURE.atTime(23, 59, 59));
        registrarMovimientoMerma(prod, lima, loteLima, motivo, "4", "Daño", "Damage", DIA_FIXTURE.plusDays(1).atStartOfDay());
        registrarMovimientoMerma(prod, loreto, loteLoreto, motivo, "1", "Daño", "Damage", DIA_FIXTURE.atTime(12, 0));
        assertThat(contar("SELECT count(*) FROM lote WHERE codigo IN ('R344-LIMA', 'R344-LORETO')")).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM merma m JOIN lote l ON l.id = m.lote_id WHERE l.codigo IN ('R344-LIMA', 'R344-LORETO')")).isEqualTo(4);
        long usuario = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, ADMIN);
        jdbc.update("INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id) VALUES (?, ?) ON CONFLICT DO NOTHING", usuario, lima);

        CookieHolder actor = new CookieHolder(loginAs(ADMIN));
        Map<String, Object> antes = snapshot(loteLima, loteLoreto, prod, lima, loreto);
        mvc.perform(get(RUTA).cookie(actor.cookie).param("desde", DIA_FIXTURE.toString()).param("hasta", DIA_FIXTURE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.lote == 'R344-LIMA')].cantidad").value(org.hamcrest.Matchers.containsInAnyOrder(2.0, 3.0)))
                .andExpect(jsonPath("$.items[?(@.lote == 'R344-LIMA')].cantidad").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(4.0))))
                .andExpect(jsonPath("$.items[*].lote").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("R344-LORETO"))));
        // Semanas, meses y rangos consultan movimientos positivos; el siguiente día queda fuera.
        LocalDate[][] periodos = {
                {DIA_FIXTURE.withDayOfMonth(9), DIA_FIXTURE},
                {DIA_FIXTURE.withDayOfMonth(1), DIA_FIXTURE.withDayOfMonth(30)},
                {DIA_FIXTURE, DIA_FIXTURE}};
        int[] cantidadesEsperadas = {2, 3, 2};
        for (int i = 0; i < periodos.length; i++) {
            LocalDate[] periodo = periodos[i];
            mvc.perform(get(RUTA).cookie(actor.cookie).param("desde", periodo[0].toString()).param("hasta", periodo[1].toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(cantidadesEsperadas[i])))
                    .andExpect(jsonPath("$.items[*].lote").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("R344-LORETO"))));
        }
        mvc.perform(get(RUTA).cookie(actor.cookie).param("desde", DIA_FIXTURE.plusDays(3).toString()).param("hasta", DIA_FIXTURE.plusDays(4).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(RUTA).cookie(actor.cookie).param("desde", DIA_FIXTURE.toString()).param("hasta", DIA_FIXTURE.minusDays(1).toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        // Sin asignaciones, el admin no recibe resultados de ningún punto.
        jdbc.update("DELETE FROM usuario_ubicacion WHERE usuario_id = ?", usuario);
        mvc.perform(get(RUTA).cookie(actor.cookie).param("desde", DIA_FIXTURE.toString()).param("hasta", DIA_FIXTURE.toString()))
                .andExpect(status().isConflict());
        mvc.perform(get(RUTA).cookie(loginAs("productor@ccpip.test"))).andExpect(status().isForbidden());
        mvc.perform(get(RUTA).cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
        assertThat(snapshot(loteLima, loteLoreto, prod, lima, loreto)).isEqualTo(antes);
    }

    private Map<String, Object> snapshot(long loteLima, long loteLoreto, long producto, long lima, long loreto) {
        return Map.of(
                "mermas", jdbc.queryForList("SELECT m.* FROM merma m JOIN movimiento_inventario mi ON mi.id = m.movimiento_id WHERE m.lote_id IN (?, ?) ORDER BY m.movimiento_id", loteLima, loteLoreto),
                "movimientos", jdbc.queryForList("SELECT * FROM movimiento_inventario WHERE producto_id = ? AND ubicacion_id IN (?, ?) ORDER BY id", producto, lima, loreto),
                "lotes", jdbc.queryForList("SELECT * FROM lote WHERE id IN (?, ?) ORDER BY id", loteLima, loteLoreto),
                "stocks", jdbc.queryForList("SELECT * FROM stock WHERE producto_id = ? AND ubicacion_id IN (?, ?) ORDER BY ubicacion_id", producto, lima, loreto));
    }

    private void registrarMovimientoMerma(long producto, long ubicacion, long lote, long motivo, String cantidad, String nombre, String nombreEn, LocalDateTime fechaHoraLima) {
        long movimiento = jdbc.queryForObject("INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion) VALUES (?, ?, ?, 'MERMA', ?, 'fixture CCPIP-344') RETURNING id", Long.class, producto, ubicacion, lote, new BigDecimal(cantidad));
        jdbc.update("INSERT INTO merma (movimiento_id, lote_id, motivo_id, motivo_nombre, motivo_nombre_en, automatica, registrado_por, registrado_en) VALUES (?, ?, ?, ?, ?, false, (SELECT id FROM usuario WHERE correo = ?), ?)", movimiento, lote, motivo, nombre, nombreEn, ADMIN, fechaHoraLima.atZone(LIMA).toOffsetDateTime());
    }

    private long contar(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private long ubicacion(String nombre) { return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, nombre); }
    private long producto(String nombre) { return productoConLote(nombre, "PUBLICADO", null, null); }
    private long lote(long producto, String codigo, long ubicacion, String cantidad) {
        BigDecimal q = new BigDecimal(cantidad);
        long id = jdbc.queryForObject("INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion, fecha_vencimiento) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class, producto, ubicacion, codigo, q, q, DIA_FIXTURE.minusDays(10), DIA_FIXTURE.plusDays(60));
        jdbc.update("INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, observacion) VALUES (?, ?, ?, 'ENTRADA', ?, 'fixture CCPIP-344')", producto, ubicacion, id, q);
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, ?) ON CONFLICT (producto_id, ubicacion_id) DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad", producto, ubicacion, q);
        return id;
    }
    private record CookieHolder(jakarta.servlet.http.Cookie cookie) {}
}
