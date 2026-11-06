package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
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
 * CCPIP-482: el superadministrador fija la tarifa de envio de cada distrito. Lista los distritos con
 * cobertura (con o sin tarifa), registra quien cambio cada tarifa y lo muestra la auditoria (CCPIP-97).
 * Transaccional: las tarifas vuelven a su estado al terminar cada prueba.
 */
@Transactional
class TarifasEnvioTest extends IntegrationTest {

    private static final String TARIFAS = "/api/admin/tarifas-envio";
    private static final String MIRAFLORES = "150122";
    private static final String BARRANCO = "150104";

    private Cookie sudo;

    @BeforeEach
    void preparar() throws Exception {
        sudo = loginAs("sudo@ccpip.test");
        jdbc.update("INSERT INTO tarifa_envio (ubigeo, monto) VALUES (?, 10.00) "
                + "ON CONFLICT (ubigeo) DO UPDATE SET monto = 10.00", MIRAFLORES);
    }

    private ResultActions fijar(String ubigeo, Object monto) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("monto", monto);
        return mockMvc.perform(put(TARIFAS + "/" + ubigeo).cookie(sudo)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private long cambios(String ubigeo) {
        return jdbc.queryForObject("SELECT count(*) FROM tarifa_envio_cambio WHERE ubigeo = ?", Long.class, ubigeo);
    }

    @Test
    void listaLosDistritosConCoberturaYSuTarifaOSinElla() throws Exception {
        jdbc.update("DELETE FROM tarifa_envio WHERE ubigeo = ?", BARRANCO);
        mockMvc.perform(get(TARIFAS).cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ubigeo == '" + MIRAFLORES + "')].distrito").value(hasItem("Miraflores")))
                .andExpect(jsonPath("$[?(@.ubigeo == '" + MIRAFLORES + "')].monto").value(hasItem(10.0)))
                .andExpect(jsonPath("$[?(@.ubigeo == '" + MIRAFLORES + "')].puntos").value(hasItem(greaterThanOrEqualTo(1))))
                // Cubierto pero sin tarifa: aparece para que la Camara la fije; mientras, no se ofrece a domicilio
                .andExpect(jsonPath("$[?(@.ubigeo == '" + BARRANCO + "' && @.monto == null)]", hasSize(1)));
    }

    @Test
    void fijarLaTarifaLaRegistraConSuAutorYApareceEnLaAuditoria() throws Exception {
        long antes = cambios(MIRAFLORES);
        fijar(MIRAFLORES, 15.5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ubigeo").value(MIRAFLORES))
                .andExpect(jsonPath("$.monto").value(15.5))
                .andExpect(jsonPath("$.actualizadoPor").value(notNullValue()))
                .andExpect(jsonPath("$.actualizadoEn").value(notNullValue()));
        assertThat(cambios(MIRAFLORES)).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("""
                SELECT monto_anterior::text || ' ' || monto_nuevo::text FROM tarifa_envio_cambio
                WHERE ubigeo = ? ORDER BY id DESC LIMIT 1""", String.class, MIRAFLORES)).isEqualTo("10.00 15.50");

        // El mismo monto no es un cambio
        fijar(MIRAFLORES, "15.50").andExpect(status().isOk());
        assertThat(cambios(MIRAFLORES)).isEqualTo(antes + 1);

        mockMvc.perform(get("/api/admin/auditoria?entidad=TARIFA_ENVIO").cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[?(@.accion == 'TARIFA_ENVIO')].detalle")
                        .value(hasItem("Miraflores (Lima, Lima) · S/ 10.00 → S/ 15.50")))
                .andExpect(jsonPath("$.registros[?(@.accion == 'TARIFA_ENVIO')].rol").value(hasItem("SUPER_ADMIN")));
    }

    @Test
    void unaTarifaNuevaNoTieneMontoAnterior() throws Exception {
        jdbc.update("DELETE FROM tarifa_envio WHERE ubigeo = ?", BARRANCO);
        fijar(BARRANCO, 0).andExpect(status().isOk()).andExpect(jsonPath("$.monto").value(0.0));
        assertThat(jdbc.queryForObject("""
                SELECT monto_anterior IS NULL FROM tarifa_envio_cambio WHERE ubigeo = ? ORDER BY id DESC LIMIT 1""",
                Boolean.class, BARRANCO)).isTrue();
    }

    @Test
    void validaElMontoYElDistrito() throws Exception {
        long antes = cambios(MIRAFLORES);
        fijar(MIRAFLORES, -1).andExpect(status().isBadRequest());
        fijar(MIRAFLORES, 10.555).andExpect(status().isBadRequest());
        fijar(MIRAFLORES, 1000).andExpect(status().isBadRequest());
        fijar(MIRAFLORES, null).andExpect(status().isBadRequest());
        fijar("999999", 10).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("UBIGEO_NO_ENCONTRADO"));
        fijar("lima", 10).andExpect(status().isNotFound());
        assertThat(cambios(MIRAFLORES)).isEqualTo(antes);
    }

    @Test
    void soloElSuperadministrador() throws Exception {
        for (String correo : new String[] {"sub_admin@ccpip.test", "consumidor@ccpip.test"}) {
            Cookie otro = loginAs(correo);
            mockMvc.perform(get(TARIFAS).cookie(otro)).andExpect(status().isForbidden());
            mockMvc.perform(put(TARIFAS + "/" + MIRAFLORES).cookie(otro).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("monto", 1)))).andExpect(status().isForbidden());
        }
    }

    @Test
    void laBaseCuidaLasTarifasYElCostoDeEnvio() {
        assertRechazaLaBase(() -> jdbc.update("UPDATE tarifa_envio SET monto = -1 WHERE ubigeo = ?", MIRAFLORES),
                "tarifa_envio_monto_valido");
        jdbc.update("""
                INSERT INTO tarifa_envio_cambio (ubigeo, monto_anterior, monto_nuevo, realizado_por)
                SELECT ?, NULL, 10.00, id FROM usuario WHERE correo = 'sudo@ccpip.test'""", MIRAFLORES);
        assertRechazaLaBase(() -> jdbc.update("UPDATE tarifa_envio_cambio SET monto_nuevo = 1"), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM tarifa_envio_cambio"), "solo insercion");
        // El recojo no tiene envio y el envio copiado a un pedido no cambia
        Long recojo = jdbc.query("SELECT id FROM pedido WHERE modalidad = 'RECOJO' LIMIT 1",
                (rs, row) -> rs.getLong(1)).stream().findFirst().orElse(null);
        if (recojo != null) {
            assertRechazaLaBase(() -> jdbc.update("UPDATE pedido SET costo_envio = 5 WHERE id = ?", recojo),
                    "no cambia");
        }
    }
}
