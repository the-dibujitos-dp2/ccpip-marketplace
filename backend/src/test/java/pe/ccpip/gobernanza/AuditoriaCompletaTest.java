package pe.ccpip.gobernanza;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/** Regressiones de CCPIP-509 sobre eventos automáticos, no inferidos como operador humano. */
@Transactional
class AuditoriaCompletaTest extends IntegrationTest {
    @Test
    void muestraLaMermaAutomaticaComoEventoDeSistemaSinAtribuirlaAlPunto() throws Exception {
        var sudo = loginAs("sudo@ccpip.test");
        String nombre = "Merma auditoria " + UUID.randomUUID();
        long producto = productoConLote(nombre, "PUBLICADO", "PUNTO_ACOPIO", hoy().plusMonths(6));
        long lote = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ? LIMIT 1", Long.class, producto);
        long ubicacion = jdbc.queryForObject("SELECT ubicacion_id FROM lote WHERE id = ?", Long.class, lote);
        long movimiento = jdbc.queryForObject("""
            INSERT INTO movimiento_inventario (producto_id, ubicacion_id, tipo, cantidad, lote_id, motivo)
            VALUES (?, ?, 'MERMA', 1, ?, NULL) RETURNING id
            """, Long.class, producto, ubicacion, lote);
        jdbc.update("INSERT INTO merma (movimiento_id, lote_id, motivo_id, motivo_nombre, motivo_nombre_en, automatica) SELECT ?, ?, id, nombre, nombre_en, true FROM motivo_merma WHERE sistema", movimiento, lote);

        mockMvc.perform(get("/api/admin/auditoria?accion=MOVIMIENTO_MERMA").cookie(sudo))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registros[*].clave", hasItem("MI-" + movimiento)))
            .andExpect(jsonPath("$.registros[?(@.clave == 'MI-" + movimiento + "')].usuarioId").value(hasItem(nullValue())));
    }

    private java.time.LocalDate hoy() {
        return jdbc.queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", java.time.LocalDate.class);
    }
}
