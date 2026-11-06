package pe.ccpip.cobertura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.entrega.EntregaService;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-496, 498: el administrador del punto reactiva (activa) un distrito de entrega que desactivo, sin
 * declararlo otra vez; vuelve a ofrecerse a domicilio y cada cambio queda registrado y en la auditoria.
 */
@Transactional
class CoberturaReactivarTest extends IntegrationTest {
    private static final String RUTA = "/api/acopio/coberturas";
    private static final String ADMIN = "admin_acopio@ccpip.test";
    private static final String DISTRITO = "150122";

    @Autowired private EntregaService entregas;

    private long punto;
    private long zona;
    private long admin;

    @BeforeEach
    void preparar() {
        admin = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, ADMIN);
        punto = jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN usuario_ubicacion uu ON uu.ubicacion_id = u.id
                WHERE uu.usuario_id = ? AND u.tipo = 'PUNTO_ACOPIO' ORDER BY u.id LIMIT 1""", Long.class, admin);
        zona = jdbc.queryForObject("""
                INSERT INTO cobertura_entrega (ubicacion_id, ubigeo, activa) VALUES (?, ?, false)
                ON CONFLICT (ubicacion_id, ubigeo) DO UPDATE SET activa = false RETURNING id""", Long.class, punto, DISTRITO);
        jdbc.update("""
                INSERT INTO tarifa_envio (ubigeo, monto) VALUES (?, 8.00)
                ON CONFLICT (ubigeo) DO NOTHING""", DISTRITO);
    }

    @Test
    void reactivaLaZonaVuelveAOfrecerseADomicilioYQuedaRegistrada() throws Exception {
        assertThat(entregas.cubre(punto, DISTRITO)).isFalse();
        mockMvc.perform(patch(RUTA + "/" + zona + "/activar").cookie(loginAs(ADMIN))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT activa FROM cobertura_entrega WHERE id = ?", Boolean.class, zona)).isTrue();
        assertThat(entregas.cubre(punto, DISTRITO)).isTrue();
        assertThat(entregas.coberturas(List.of(punto))).anyMatch(c -> c.puntoId() == punto
                && DISTRITO.equals(c.distrito().ubigeo()));
        assertThat(cambios()).containsExactly(Map.of("activa", true, "realizado_por", admin));
        mockMvc.perform(get(RUTA).param("punto", String.valueOf(punto)).cookie(loginAs(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].zonas[?(@.id == " + zona + ")].activa").value(hasItem(true)));
    }

    @Test
    void reactivarUnaZonaActivaNoCambiaNiRegistraNada() throws Exception {
        Cookie sesion = loginAs(ADMIN);
        mockMvc.perform(patch(RUTA + "/" + zona + "/activar").cookie(sesion)).andExpect(status().isNoContent());
        mockMvc.perform(patch(RUTA + "/" + zona + "/activar").cookie(sesion)).andExpect(status().isNoContent());
        assertThat(cambios()).hasSize(1);
    }

    @Test
    void desactivarYReactivarQuedanRegistradosEnOrdenYEnLaAuditoria() throws Exception {
        Cookie sesion = loginAs(ADMIN);
        mockMvc.perform(patch(RUTA + "/" + zona + "/activar").cookie(sesion)).andExpect(status().isNoContent());
        mockMvc.perform(patch(RUTA + "/" + zona + "/desactivar").cookie(sesion)).andExpect(status().isNoContent());
        mockMvc.perform(patch(RUTA + "/" + zona + "/desactivar").cookie(sesion)).andExpect(status().isNoContent());
        assertThat(cambios()).extracting(c -> c.get("activa")).containsExactly(true, false);
        assertThat(entregas.cubre(punto, DISTRITO)).isFalse();

        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM cobertura_entrega_cambio WHERE cobertura_id = ? ORDER BY id", Long.class, zona);
        String nombrePunto = jdbc.queryForObject("SELECT nombre FROM ubicacion WHERE id = ?", String.class, punto);
        String distrito = jdbc.queryForObject("SELECT distrito FROM ubigeo WHERE codigo = ?", String.class, DISTRITO);
        mockMvc.perform(get("/api/admin/auditoria").param("entidad", "COBERTURA_ENTREGA").cookie(loginAs("sudo@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros[?(@.clave == 'CZ-" + ids.get(0) + "')].accion").value(hasItem("COBERTURA_ACTIVADA")))
                .andExpect(jsonPath("$.registros[?(@.clave == 'CZ-" + ids.get(1) + "')].accion").value(hasItem("COBERTURA_DESACTIVADA")))
                .andExpect(jsonPath("$.registros[?(@.clave == 'CZ-" + ids.get(0) + "')].correo").value(hasItem(ADMIN)))
                .andExpect(jsonPath("$.registros[?(@.clave == 'CZ-" + ids.get(0) + "')].entidadId").value(hasItem((int) zona)))
                .andExpect(jsonPath("$.registros[?(@.clave == 'CZ-" + ids.get(0) + "')].detalle")
                        .value(hasItem(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString(nombrePunto),
                                org.hamcrest.Matchers.containsString(distrito)))));
    }

    @Test
    void declararUnDistritoInactivoTambienLoReactivaYLoRegistraUnaVez() throws Exception {
        Cookie sesion = loginAs(ADMIN);
        String cuerpo = "{\"puntoId\":" + punto + ",\"ubigeos\":[\"" + DISTRITO + "\"]}";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(RUTA).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isOk());
        }
        assertThat(entregas.cubre(punto, DISTRITO)).isTrue();
        assertThat(cambios()).containsExactly(Map.of("activa", true, "realizado_por", admin));
    }

    @Test
    void noRevelaZonasAjenasNiInexistentesYSoloElAdministradorDelPuntoReactiva() throws Exception {
        Long ajena = jdbc.queryForObject("""
                INSERT INTO cobertura_entrega (ubicacion_id, ubigeo, activa)
                SELECT u.id, ?, false FROM ubicacion u
                WHERE u.tipo = 'PUNTO_ACOPIO' AND u.id NOT IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = ?)
                ORDER BY u.id LIMIT 1
                ON CONFLICT (ubicacion_id, ubigeo) DO UPDATE SET activa = false RETURNING id""", Long.class, DISTRITO, admin);
        Cookie sesion = loginAs(ADMIN);
        mockMvc.perform(patch(RUTA + "/" + ajena + "/activar").cookie(sesion))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ZONA_NO_ENCONTRADA"));
        mockMvc.perform(patch(RUTA + "/999999999/activar").cookie(sesion))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ZONA_NO_ENCONTRADA"));
        mockMvc.perform(patch(RUTA + "/0/activar").cookie(sesion))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ZONA_INVALIDA"));
        mockMvc.perform(patch(RUTA + "/" + zona + "/activar")).andExpect(status().isUnauthorized());
        for (String correo : List.of("consumidor@ccpip.test", "productor@ccpip.test", "sub_admin@ccpip.test", "sudo@ccpip.test")) {
            mockMvc.perform(patch(RUTA + "/" + zona + "/activar").cookie(loginAs(correo))).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT activa FROM cobertura_entrega WHERE id = ?", Boolean.class, ajena)).isFalse();
        assertThat(jdbc.queryForObject("SELECT activa FROM cobertura_entrega WHERE id = ?", Boolean.class, zona)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cobertura_entrega_cambio WHERE cobertura_id IN (?, ?)",
                Long.class, zona, ajena)).isZero();
    }

    @Test
    void elRegistroDeCambiosEsDeSoloInsercion() {
        jdbc.update("INSERT INTO cobertura_entrega_cambio (cobertura_id, activa, realizado_por) VALUES (?, true, ?)", zona, admin);
        assertRechazaLaBase(() -> jdbc.update("UPDATE cobertura_entrega_cambio SET activa = false"), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM cobertura_entrega_cambio"), "solo insercion");
    }

    private List<Map<String, Object>> cambios() {
        return jdbc.queryForList("""
                SELECT activa, realizado_por FROM cobertura_entrega_cambio WHERE cobertura_id = ? ORDER BY id""", zona);
    }
}
