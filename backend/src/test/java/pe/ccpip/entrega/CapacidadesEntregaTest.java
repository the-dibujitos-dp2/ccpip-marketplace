package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-484: el superadministrador fija y quita la capacidad diaria de entregas a domicilio de cada punto;
 * el administrador del punto la ve junto a su cobertura. Cada cambio se audita (CCPIP-97). Transaccional:
 * las capacidades vuelven a su estado al terminar cada prueba. El limite al comprar se prueba en
 * {@code EntregaDomicilioTest}.
 */
@Transactional
class CapacidadesEntregaTest extends IntegrationTest {

    private static final String CAPACIDADES = "/api/admin/capacidades-entrega";
    private static final String CUSCO = "Centro Regional Cusco";

    private Cookie sudo;
    private long cusco;

    @BeforeEach
    void preparar() throws Exception {
        sudo = loginAs("sudo@ccpip.test");
        cusco = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, CUSCO);
        jdbc.update("DELETE FROM capacidad_entrega WHERE ubicacion_id = ?", cusco);
    }

    private ResultActions fijar(Object puntoId, Object maximo) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("maximoDiario", maximo);
        return mockMvc.perform(put(CAPACIDADES + "/" + puntoId).cookie(sudo)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private long cambios() {
        return jdbc.queryForObject("SELECT count(*) FROM capacidad_entrega_cambio WHERE ubicacion_id = ?", Long.class, cusco);
    }

    @Test
    void listaLosPuntosActivosConSuCapacidadOSinLimite() throws Exception {
        mockMvc.perform(get(CAPACIDADES).cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.punto == '" + CUSCO + "')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.punto == '" + CUSCO + "' && @.maximoDiario == null)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.punto == '" + CUSCO + "')].usadosHoy", hasItem(notNullValue())));
    }

    @Test
    void fijarCambiarYQuitarQuedanEnLaAuditoria() throws Exception {
        long antes = cambios();
        fijar(cusco, 20).andExpect(status().isOk())
                .andExpect(jsonPath("$.puntoId").value(cusco))
                .andExpect(jsonPath("$.maximoDiario").value(20))
                .andExpect(jsonPath("$.actualizadoPor").value(notNullValue()));
        fijar(cusco, 20).andExpect(status().isOk());
        assertThat(cambios()).isEqualTo(antes + 1);
        fijar(cusco, 35).andExpect(status().isOk()).andExpect(jsonPath("$.maximoDiario").value(35));
        mockMvc.perform(delete(CAPACIDADES + "/" + cusco).cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maximoDiario").value(nullValue()));
        assertThat(cambios()).isEqualTo(antes + 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM capacidad_entrega WHERE ubicacion_id = ?", Long.class, cusco)).isZero();

        mockMvc.perform(get("/api/admin/auditoria?entidad=CAPACIDAD_ENTREGA").cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[?(@.accion == 'CAPACIDAD_ENTREGA')].detalle")
                        .value(hasItem(CUSCO + " · sin límite → 20 por día")))
                .andExpect(jsonPath("$.registros[?(@.accion == 'CAPACIDAD_ENTREGA')].detalle")
                        .value(hasItem(CUSCO + " · 35 → sin límite por día")));
    }

    @Test
    void validaElValorYElPunto() throws Exception {
        long antes = cambios();
        fijar(cusco, 0).andExpect(status().isBadRequest());
        fijar(cusco, -3).andExpect(status().isBadRequest());
        fijar(cusco, 10001).andExpect(status().isBadRequest());
        fijar(cusco, null).andExpect(status().isBadRequest());
        fijar(cusco, "muchos").andExpect(status().isBadRequest());
        fijar(999999, 5).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUNTO_NO_ENCONTRADO"));
        // Un almacen o un centro que no es punto de acopio tampoco
        Long otro = jdbc.query("SELECT id FROM ubicacion WHERE tipo <> 'PUNTO_ACOPIO' LIMIT 1", (rs, row) -> rs.getLong(1))
                .stream().findFirst().orElse(null);
        if (otro != null) {
            fijar(otro, 5).andExpect(status().isNotFound());
        }
        assertThat(cambios()).isEqualTo(antes);
    }

    @Test
    void soloElSuperadministradorLaFija() throws Exception {
        for (String correo : new String[] {"sub_admin@ccpip.test", "admin_acopio@ccpip.test", "consumidor@ccpip.test"}) {
            Cookie otro = loginAs(correo);
            mockMvc.perform(get(CAPACIDADES).cookie(otro)).andExpect(status().isForbidden());
            mockMvc.perform(put(CAPACIDADES + "/" + cusco).cookie(otro).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("maximoDiario", 5)))).andExpect(status().isForbidden());
            mockMvc.perform(delete(CAPACIDADES + "/" + cusco).cookie(otro)).andExpect(status().isForbidden());
        }
    }

    @Test
    void elAdministradorDelPuntoLaVeJuntoASuCobertura() throws Exception {
        Long punto = jdbc.query("""
                SELECT uu.ubicacion_id FROM usuario_ubicacion uu JOIN usuario u ON u.id = uu.usuario_id
                JOIN ubicacion ub ON ub.id = uu.ubicacion_id
                WHERE u.correo = 'admin_acopio@ccpip.test' AND ub.tipo = 'PUNTO_ACOPIO' ORDER BY uu.ubicacion_id LIMIT 1""",
                (rs, row) -> rs.getLong(1)).stream().findFirst().orElseThrow();
        jdbc.update("""
                INSERT INTO capacidad_entrega (ubicacion_id, maximo_diario) VALUES (?, 12)
                ON CONFLICT (ubicacion_id) DO UPDATE SET maximo_diario = 12""", punto);
        mockMvc.perform(get("/api/acopio/coberturas?punto=" + punto).cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].capacidad.maximoDiario").value(12))
                .andExpect(jsonPath("$[0].capacidad.usadosHoy").value(notNullValue()));
    }

    @Test
    void laBaseCuidaLaCapacidadYSuHistorial() {
        assertRechazaLaBase(() -> jdbc.update("INSERT INTO capacidad_entrega (ubicacion_id, maximo_diario) VALUES (?, 0)", cusco),
                "capacidad_entrega_maximo_valido");
        jdbc.update("""
                INSERT INTO capacidad_entrega_cambio (ubicacion_id, maximo_anterior, maximo_nuevo, realizado_por)
                SELECT ?, NULL, 10, id FROM usuario WHERE correo = 'sudo@ccpip.test'""", cusco);
        assertRechazaLaBase(() -> jdbc.update("UPDATE capacidad_entrega_cambio SET maximo_nuevo = 1"), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM capacidad_entrega_cambio"), "solo insercion");
    }
}
