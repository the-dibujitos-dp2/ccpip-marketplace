package pe.ccpip.gobernanza;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/** Fuentes administrativas originales consultables como eventos por SUPER_ADMIN (CCPIP-509). */
@Transactional
class AuditoriaFuentesTest extends IntegrationTest {
    @Test
    void presentaAltaYCancelacionDeComisionComoEventosSeparados() throws Exception {
        long actor = usuario("sudo@ccpip.test");
        long alta = jdbc.queryForObject("""
                INSERT INTO comision_configuracion (porcentaje, vigente_desde, motivo, registrado_por)
                VALUES (12, ?, 'Prueba auditoria', ?) RETURNING id""", Long.class, hoy().plusDays(15), actor);
        jdbc.update("UPDATE comision_configuracion SET cancelado_por = ?, cancelado_en = now() WHERE id = ?", actor, alta);
        var sudo = loginAs("sudo@ccpip.test");
        mockMvc.perform(get("/api/admin/auditoria").cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[*].clave", hasItem("CC-" + alta)))
                .andExpect(jsonPath("$.registros[?(@.clave == 'CC-" + alta + "')].accion").value(hasItem("COMISION_PROGRAMADA")))
                .andExpect(jsonPath("$.registros[?(@.clave == 'CC-" + alta + "')].entidad").value(hasItem("COMISION")));
        mockMvc.perform(get("/api/admin/auditoria").param("accion", "COMISION_CANCELADA").cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[*].clave", hasItem("CCX-" + alta)));
    }

    @Test
    void presentaCadaParametroDePlataformaComoCambioDeParametro() throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO parametro_plataforma (clave, valor, registrado_por)
                VALUES ('CARRITO_VIGENCIA_HORAS', 72, ?) RETURNING id""", Long.class, usuario("sudo@ccpip.test"));
        mockMvc.perform(get("/api/admin/auditoria?accion=PARAMETRO_CAMBIADO").cookie(loginAs("sudo@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[*].clave", hasItem("PP-" + id)))
                .andExpect(jsonPath("$.registros[?(@.clave == 'PP-" + id + "')].entidad").value(hasItem("PARAMETRO")));
    }

    @Test
    void presentaLosCambiosDeMotivosDeMerma() throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO motivo_merma_cambio (motivo_id, accion, nombre, nombre_en, requiere_detalle, activo, registrado_por)
                SELECT id, 'MODIFICADO', nombre, nombre_en, requiere_detalle, activo, ? FROM motivo_merma WHERE sistema
                RETURNING id""", Long.class, usuario("sudo@ccpip.test"));
        mockMvc.perform(get("/api/admin/auditoria?accion=MOTIVO_MERMA_MODIFICADO").cookie(loginAs("sudo@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[*].clave", hasItem("MM-" + id)))
                .andExpect(jsonPath("$.registros[?(@.clave == 'MM-" + id + "')].entidad").value(hasItem("MOTIVO_MERMA")));
    }

    @Test
    void clasificaCambiosDeUnidadComoEntidadUnidadConSuNombre() throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO unidad_medida (codigo, nombre) VALUES (?, 'Unidad auditada') RETURNING id
                """, Long.class, "AUD_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        jdbc.update("INSERT INTO catalogo_maestro_auditoria (entidad, entidad_id, usuario_id, accion) VALUES ('UNIDAD', ?, ?, 'CREAR')",
                id, usuario("sudo@ccpip.test"));
        mockMvc.perform(get("/api/admin/auditoria?accion=CATALOGO_CREAR&entidad=UNIDAD").cookie(loginAs("sudo@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[?(@.entidadId == " + id + ")].detalle").value(hasItem("Unidad auditada")));
    }

    @Test
    void conservaMetadatosAunqueHayaContextoLegado() throws Exception {
        long id = jdbc.queryForObject("INSERT INTO parametro_plataforma (clave, valor, registrado_por) VALUES ('CARRITO_VIGENCIA_HORAS', 73, ?) RETURNING id", Long.class, usuario("sudo@ccpip.test"));
        mockMvc.perform(get("/api/admin/auditoria?accion=PARAMETRO_CAMBIADO").cookie(loginAs("sudo@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[?(@.clave == 'PP-" + id + "')].metadatos").value(hasItem(org.hamcrest.Matchers.containsString("CARRITO_VIGENCIA_HORAS"))));
    }

    private long usuario(String correo) {
        return jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, correo);
    }

    private LocalDate hoy() {
        return jdbc.queryForObject("SELECT (now() AT TIME ZONE 'America/Lima')::date", LocalDate.class);
    }
}
