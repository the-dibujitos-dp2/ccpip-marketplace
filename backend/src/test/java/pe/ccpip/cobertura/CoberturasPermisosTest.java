package pe.ccpip.cobertura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import jakarta.servlet.ServletException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

/** Caracterización adversarial del contrato de lectura CCPIP-401. */
@Transactional
class CoberturasPermisosTest extends IntegrationTest {
    private static final String RUTA = "/api/acopio/coberturas";

    @Test
    void permisosRechazanAnonimoYRolesAjeno() throws Exception {
        snapshot(() -> {
            mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
            for (String correo : List.of("consumidor@ccpip.test", "productor@ccpip.test", "distribuidor@ccpip.test", "sub_admin@ccpip.test", "sudo@ccpip.test")) {
                mockMvc.perform(get(RUTA).cookie(loginAs(correo))).andExpect(status().isForbidden());
            }
        });
    }

    @Test
    void listaTodosLosPuntosPropiosIncluyeZonasInactivasYCatalogoSinMutar() throws Exception {
        var cookie = loginAs("admin_acopio@ccpip.test");
        Long punto = puntoPropio();
        jdbc.update("INSERT INTO cobertura_entrega(ubicacion_id,ubigeo,activa) VALUES (?, '150122', true) ON CONFLICT (ubicacion_id,ubigeo) DO UPDATE SET activa=true", punto);
        String otroCodigo = jdbc.queryForObject("SELECT codigo FROM ubigeo WHERE codigo<>'150122' ORDER BY codigo LIMIT 1", String.class);
        jdbc.update("INSERT INTO cobertura_entrega(ubicacion_id,ubigeo,activa) VALUES (?, ?, false) ON CONFLICT (ubicacion_id,ubigeo) DO UPDATE SET activa=false", punto, otroCodigo);
        snapshot(() -> {
            mockMvc.perform(get(RUTA).cookie(cookie)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].puntoId").value(punto.intValue()))
                    .andExpect(jsonPath("$[0].zonas[?(@.ubigeo == '150122')].departamento").isNotEmpty())
                    .andExpect(jsonPath("$[0].zonas[?(@.ubigeo == '150122')].provincia").isNotEmpty())
                    .andExpect(jsonPath("$[0].zonas[?(@.ubigeo == '150122')].distrito").isNotEmpty())
                    .andExpect(jsonPath("$[0].zonas[?(@.ubigeo == '" + otroCodigo + "')].activa").value(org.hamcrest.Matchers.contains(false)));
            mockMvc.perform(get(RUTA).param("punto", punto.toString()).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].zonas[*].ubigeo").isNotEmpty());
        });
    }

    @Test
    void filtroInvalidoAjenoEInexistenteUsanContratoDeErrores() throws Exception {
        var cookie = loginAs("admin_acopio@ccpip.test");
        snapshot(() -> {
            assertThat(assertThrows(ServletException.class, () -> mockMvc.perform(get(RUTA).param("punto", "0").cookie(cookie)))
                    .getCause()).isInstanceOf(jakarta.validation.ConstraintViolationException.class);
            mockMvc.perform(get(RUTA).param("punto", "not-a-long").cookie(cookie)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
            var ajeno = jdbc.queryForObject("SELECT id FROM ubicacion WHERE tipo='PUNTO_ACOPIO' AND id NOT IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id=(SELECT id FROM usuario WHERE correo='admin_acopio@ccpip.test')) ORDER BY id LIMIT 1", Long.class);
            assertThat(ajeno).as("seed debe ofrecer otro punto").isNotNull();
            mockMvc.perform(get(RUTA).param("punto", ajeno.toString()).cookie(cookie)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PUNTO_NO_ENCONTRADO"));
            mockMvc.perform(get(RUTA).param("punto", "999999999").cookie(cookie)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PUNTO_NO_ENCONTRADO"));
        });
    }

    @Test
    void sinAsignacionDevuelve409ConCodigoDeNegocio() throws Exception {
        var cookie = loginAs("admin_acopio@ccpip.test");
        Long uid = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='admin_acopio@ccpip.test'", Long.class);
        jdbc.update("DELETE FROM usuario_ubicacion WHERE usuario_id=?", uid);
        snapshot(() -> mockMvc.perform(get(RUTA).cookie(cookie)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACOPIO_SIN_ASIGNAR")));
    }

    @Test
    void puntoPropioSinZonasSeListaConArregloVacioYNoSeFiltranPuntosDeOtroAdministrador() throws Exception {
        var cookie = loginAs("admin_acopio@ccpip.test");
        Long uid = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='admin_acopio@ccpip.test'", Long.class);
        Long punto = jdbc.queryForObject("INSERT INTO ubicacion(tipo,nombre,departamento) VALUES ('PUNTO_ACOPIO','QA401 sin zonas','Lima') RETURNING id", Long.class);
        jdbc.update("INSERT INTO usuario_ubicacion(usuario_id,ubicacion_id) VALUES (?,?)", uid, punto);
        Long ajeno = jdbc.queryForObject("SELECT id FROM ubicacion WHERE tipo='PUNTO_ACOPIO' AND id NOT IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id=?) ORDER BY id LIMIT 1", Long.class, uid);
        snapshot(() -> {
            mockMvc.perform(get(RUTA).param("punto", punto.toString()).cookie(cookie)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].puntoId").value(punto.intValue())).andExpect(jsonPath("$[0].zonas").isEmpty());
            mockMvc.perform(get(RUTA).cookie(cookie)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.puntoId == " + ajeno + ")]").isEmpty());
        });
    }

    private Long puntoPropio() {
        return jdbc.queryForObject("SELECT u.id FROM ubicacion u JOIN usuario_ubicacion uu ON uu.ubicacion_id=u.id JOIN usuario a ON a.id=uu.usuario_id WHERE a.correo='admin_acopio@ccpip.test' AND u.tipo='PUNTO_ACOPIO' ORDER BY u.id LIMIT 1", Long.class);
    }

    /** Snapshot integral de tablas de negocio antes y después de cada lectura/denegación. */
    private void snapshot(ThrowingRunnable requests) throws Exception {
        var antes = snapshotNegocio();
        requests.run();
        assertThat(snapshotNegocio()).isEqualTo(antes);
    }

    private List<String> snapshotNegocio() {
        List<String> tablas = List.of("cobertura_entrega", "ubigeo", "usuario_ubicacion", "ubicacion", "pedido", "entrega", "pedido_estado_historial", "lote", "movimiento_inventario");
        java.util.ArrayList<String> filas = new java.util.ArrayList<>();
        for (String tabla : tablas) {
            filas.add(tabla + "=" + jdbc.queryForList("SELECT to_jsonb(t)::text AS row FROM \"" + tabla + "\" t ORDER BY to_jsonb(t)::text", String.class));
        }
        return filas;
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
