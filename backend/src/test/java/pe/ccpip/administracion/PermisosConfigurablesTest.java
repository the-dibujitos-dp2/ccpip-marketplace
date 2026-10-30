package pe.ccpip.administracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-296 (RF-172): el superadministrador modifica los permisos configurables de cada rol. Revocar uno
 * quita de verdad esas acciones al rol; las restricciones de gobernanza no se conceden y todo cambio se
 * audita. Transaccional: los permisos vuelven a su estado al terminar cada prueba.
 */
@Transactional
class PermisosConfigurablesTest extends IntegrationTest {

    private static final String CONFIGURABLES = "/api/admin/permisos/configurables";

    private Cookie sudo;

    @BeforeEach
    void preparar() throws Exception {
        sudo = loginAs("sudo@ccpip.test");
    }

    @Test
    void listaLosPermisosDeCadaRolConSusRestricciones() throws Exception {
        mockMvc.perform(get(CONFIGURABLES).cookie(sudo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.rol == 'SUB_ADMIN')].permisos[?(@.restringido == false)].codigo", hasSize(5)))
                .andExpect(jsonPath("$[?(@.rol == 'SUB_ADMIN')].permisos[?(@.codigo == 'SUSPENDER_CUENTAS')].concedido")
                        .value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.rol == 'SUB_ADMIN')].permisos[?(@.codigo == 'EDITAR_PEDIDOS')].restringido")
                        .value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.rol == 'SUB_ADMIN')].permisos[?(@.codigo == 'EDITAR_PEDIDOS')].concedido")
                        .value(hasItem(false)))
                .andExpect(jsonPath("$[?(@.rol == 'SUPER_ADMIN')].permisos[*].restringido", not(hasItem(false))))
                // Los roles sin permisos configurables no aparecen
                .andExpect(jsonPath("$[*].rol", not(hasItem("PRODUCTOR"))));
    }

    @Test
    void revocarQuitaLaAccionAlRolYConcederLaDevuelve() throws Exception {
        long consumidor = usuario("consumidor@ccpip.test");
        Cookie sub = loginAs("sub_admin@ccpip.test");

        cambiar("SUB_ADMIN", "SUSPENDER_CUENTAS", false, "Solo la presidencia suspende cuentas este mes")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("SUB_ADMIN"))
                .andExpect(jsonPath("$.permisos[?(@.codigo == 'SUSPENDER_CUENTAS')].concedido").value(hasItem(false)))
                .andExpect(jsonPath("$.permisos[?(@.codigo == 'SUSPENDER_CUENTAS')].actualizadoPor")
                        .value(hasItem("Super Administrador de Prueba")));

        mockMvc.perform(post("/api/admin/cuentas/" + consumidor + "/suspender").cookie(sub)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", "Denuncias falsas reiteradas"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISO_REVOCADO"));
        // La cuenta no cambio
        assertThat(jdbc.queryForObject("SELECT estado FROM usuario WHERE id = ?", String.class, consumidor)).isNotEqualTo("SUSPENDIDA");

        cambiar("SUB_ADMIN", "SUSPENDER_CUENTAS", true, "Se restituye tras la revisión")
                .andExpect(jsonPath("$.permisos[?(@.codigo == 'SUSPENDER_CUENTAS')].concedido").value(hasItem(true)));
        mockMvc.perform(post("/api/admin/cuentas/" + consumidor + "/suspender").cookie(sub)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", "Denuncias falsas reiteradas"))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({
        "DECIDIR_AUTORIZACIONES, /api/admin/autorizaciones/999999/aprobar",
        "DECIDIR_AUTORIZACIONES, /api/admin/autorizaciones/999999/renovaciones",
        "MODERAR_PUBLICACIONES, /api/admin/moderacion/999999/aprobar",
        "MODERAR_PUBLICACIONES, /api/admin/moderacion/videos/999999/rechazar",
        "GESTIONAR_PRODUCTORES, /api/admin/productores/999999/bloquear",
        "GESTIONAR_DENUNCIAS, /api/admin/denuncias/999999/estado",
        "SUSPENDER_CUENTAS, /api/admin/cuentas/999999/reactivar",
    })
    void cadaPermisoProtegeSusAcciones(String permiso, String ruta) throws Exception {
        Cookie sub = loginAs("sub_admin@ccpip.test");
        mockMvc.perform(post(ruta).cookie(sub).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(not(403)));
        cambiar("SUB_ADMIN", permiso, false, "Prueba de revocación").andExpect(status().isOk());
        mockMvc.perform(post(ruta).cookie(sub).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISO_REVOCADO"));
    }

    @Test
    void lasRestriccionesDeGobernanzaNoSeConceden() throws Exception {
        int antes = cambios();
        cambiar("SUB_ADMIN", "EDITAR_PEDIDOS", true, "Quiero corregir un pedido")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESTRICCION_GOBERNANZA"));
        cambiar("SUPER_ADMIN", "EDITAR_DATOS_COMPRADORES", true, "Quiero corregir un correo")
                .andExpect(jsonPath("$.code").value("RESTRICCION_GOBERNANZA"));
        assertThat(cambios()).isEqualTo(antes);
        // Tampoco desde la base
        assertRechazaLaBase(() -> jdbc.update("UPDATE permiso_configurable SET concedido = true WHERE codigo = 'EDITAR_EXISTENCIAS'"),
                "permiso_restringido_nunca_concedido");
    }

    @Test
    void cadaCambioSeAuditaConAutorYMotivo() throws Exception {
        int antes = cambios();
        cambiar("SUB_ADMIN", "GESTIONAR_DENUNCIAS", false, "Las denuncias pasan al comité").andExpect(status().isOk());
        // Repetir el mismo estado no es un cambio
        cambiar("SUB_ADMIN", "GESTIONAR_DENUNCIAS", false, "Otra vez").andExpect(status().isOk());
        assertThat(cambios()).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("""
                SELECT c.motivo FROM permiso_configurable_cambio c JOIN usuario u ON u.id = c.realizado_por
                WHERE c.codigo = 'GESTIONAR_DENUNCIAS' AND u.correo = 'sudo@ccpip.test'
                ORDER BY c.id DESC LIMIT 1""", String.class)).isEqualTo("Las denuncias pasan al comité");

        // Aparece en la auditoria (CCPIP-97)
        mockMvc.perform(get("/api/admin/auditoria?entidad=PERMISO").cookie(sudo))
                .andExpect(jsonPath("$.registros[?(@.accion == 'PERMISO_REVOCADO')].detalle")
                        .value(hasItem("SUB_ADMIN · GESTIONAR_DENUNCIAS")))
                .andExpect(jsonPath("$.registros[?(@.accion == 'PERMISO_REVOCADO')].rol").value(hasItem("SUPER_ADMIN")));

        // Inmutable
        assertRechazaLaBase(() -> jdbc.update("UPDATE permiso_configurable_cambio SET motivo = 'otro'"), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM permiso_configurable WHERE codigo = 'GESTIONAR_DENUNCIAS'"),
                "no se borra");
    }

    @Test
    void validaElPermisoYElMotivo() throws Exception {
        cambiar("SUB_ADMIN", "BORRAR_TODO", false, "No existe").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMISO_NO_CONFIGURABLE"));
        cambiar("PRODUCTOR", "SUSPENDER_CUENTAS", false, "No lo tiene").andExpect(status().isNotFound());
        cambiar("OTRO", "SUSPENDER_CUENTAS", false, "Rol inexistente").andExpect(status().isBadRequest());
        cambiar("SUB_ADMIN", "SUSPENDER_CUENTAS", false, "   ").andExpect(status().isBadRequest());
        mockMvc.perform(put(CONFIGURABLES + "/SUB_ADMIN/SUSPENDER_CUENTAS").cookie(sudo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", "Sin decir qué"))))
                .andExpect(status().isBadRequest());
    }

    // --- Permisos (RNF-04) ---

    @ParameterizedTest
    @ValueSource(strings = {"sub_admin@ccpip.test", "productor@ccpip.test", "consumidor@ccpip.test", "admin_acopio@ccpip.test"})
    void soloElSuperadministrador(String correo) throws Exception {
        Cookie otro = loginAs(correo);
        mockMvc.perform(get(CONFIGURABLES).cookie(otro)).andExpect(status().isForbidden());
        mockMvc.perform(put(CONFIGURABLES + "/SUB_ADMIN/SUSPENDER_CUENTAS").cookie(otro)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("concedido", true, "motivo", "Me lo doy"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinSesionPideIngresar() throws Exception {
        mockMvc.perform(get(CONFIGURABLES)).andExpect(status().isUnauthorized());
    }

    // --- Apoyo ---

    private ResultActions cambiar(String rol, String codigo, boolean concedido, String motivo) throws Exception {
        return mockMvc.perform(put(CONFIGURABLES + "/" + rol + "/" + codigo).cookie(sudo).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("concedido", concedido, "motivo", motivo))));
    }

    private int cambios() {
        return jdbc.queryForObject("SELECT count(*) FROM permiso_configurable_cambio", Integer.class);
    }

    private long usuario(String correo) {
        return jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, correo);
    }
}
