package pe.ccpip.cobertura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-149 (RF-118): el administrador del punto de acopio declara su cobertura de entrega con distritos del
 * catalogo territorial. Los cambios no alteran pedidos ni entregas previas. Transaccional.
 */
@Transactional
class CoberturaDeclararTest extends IntegrationTest {

    private static final String BASE = "/api/acopio/coberturas";
    private static final String ADMIN = "admin_acopio@ccpip.test";

    private Cookie sesion;
    private long punto;
    private List<String> libres;

    @BeforeEach
    void preparar() throws Exception {
        sesion = loginAs(ADMIN);
        punto = jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN usuario_ubicacion uu ON uu.ubicacion_id=u.id
                JOIN usuario a ON a.id=uu.usuario_id
                WHERE a.correo=? AND u.tipo='PUNTO_ACOPIO' ORDER BY u.id LIMIT 1""", Long.class, ADMIN);
        libres = jdbc.queryForList("""
                SELECT codigo FROM ubigeo
                WHERE codigo NOT IN (SELECT ubigeo FROM cobertura_entrega WHERE ubicacion_id = ?)
                ORDER BY codigo LIMIT 2""", String.class, punto);
        assertThat(libres).hasSize(2);
    }

    @Test
    void declaraDistritosDelCatalogoYReactivaLosInactivos() throws Exception {
        String inactivo = libres.get(1);
        jdbc.update("INSERT INTO cobertura_entrega (ubicacion_id, ubigeo, activa) VALUES (?, ?, false)", punto, inactivo);
        String distrito = jdbc.queryForObject("SELECT distrito FROM ubigeo WHERE codigo = ?", String.class, libres.get(0));
        var entregasAntes = jdbc.queryForList("SELECT * FROM entrega ORDER BY id");
        var pedidosAntes = jdbc.queryForList("SELECT id, estado, modalidad, total FROM pedido ORDER BY id");

        declarar(Map.of("puntoId", punto, "ubigeos", libres))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.puntoId").value((int) punto))
                .andExpect(jsonPath("$.zonas[?(@.ubigeo == '" + libres.get(0) + "')].activa").value(hasItem(true)))
                .andExpect(jsonPath("$.zonas[?(@.ubigeo == '" + libres.get(0) + "')].distrito").value(hasItem(distrito)))
                .andExpect(jsonPath("$.zonas[?(@.ubigeo == '" + inactivo + "')].activa").value(hasItem(true)));

        // Los cambios no alteran pedidos ni entregas previas
        assertThat(jdbc.queryForList("SELECT * FROM entrega ORDER BY id")).isEqualTo(entregasAntes);
        assertThat(jdbc.queryForList("SELECT id, estado, modalidad, total FROM pedido ORDER BY id")).isEqualTo(pedidosAntes);
    }

    @Test
    void declararOtraVezNoDuplica() throws Exception {
        declarar(Map.of("puntoId", punto, "ubigeos", List.of(libres.get(0), libres.get(0)))).andExpect(status().isOk());
        declarar(Map.of("puntoId", punto, "ubigeos", List.of(libres.get(0)))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cobertura_entrega WHERE ubicacion_id = ? AND ubigeo = ?",
                Integer.class, punto, libres.get(0))).isEqualTo(1);
    }

    @Test
    void soloDistritosDelCatalogoTerritorial() throws Exception {
        int antes = zonas();
        declarar(Map.of("puntoId", punto, "ubigeos", List.of(libres.get(0), "999999")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UBIGEO_NO_CATALOGADO"));
        assertThat(zonas()).isEqualTo(antes);
    }

    @Test
    void soloEnPuntosPropios() throws Exception {
        Long ajeno = jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u WHERE u.tipo = 'PUNTO_ACOPIO' AND NOT EXISTS (
                    SELECT 1 FROM usuario_ubicacion uu JOIN usuario a ON a.id = uu.usuario_id
                    WHERE uu.ubicacion_id = u.id AND a.correo = ?)
                ORDER BY u.id LIMIT 1""", Long.class, ADMIN);
        long productor = jdbc.queryForObject("SELECT id FROM ubicacion WHERE tipo <> 'PUNTO_ACOPIO' ORDER BY id LIMIT 1", Long.class);
        int antes = zonas();
        if (ajeno != null) {
            declarar(Map.of("puntoId", ajeno, "ubigeos", libres)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PUNTO_NO_ENCONTRADO"));
        }
        declarar(Map.of("puntoId", productor, "ubigeos", libres)).andExpect(status().isNotFound());
        assertThat(zonas()).isEqualTo(antes);
    }

    @Test
    void validaLaDeclaracion() throws Exception {
        declarar(Map.of("puntoId", punto, "ubigeos", List.of())).andExpect(status().isBadRequest());
        declarar(Map.of("puntoId", punto, "ubigeos", List.of("15A1"))).andExpect(status().isBadRequest());
        declarar(Map.of("ubigeos", libres)).andExpect(status().isBadRequest());
        declarar(Map.of("puntoId", -1, "ubigeos", libres)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"productor@ccpip.test", "consumidor@ccpip.test", "sub_admin@ccpip.test", "sudo@ccpip.test"})
    void esSoloDelAdministradorDelPunto(String correo) throws Exception {
        mockMvc.perform(post(BASE).cookie(loginAs(correo)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("puntoId", punto, "ubigeos", libres))))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinSesionPideIngresar() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("puntoId", punto, "ubigeos", libres))))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions declarar(Map<String, ?> cuerpo) throws Exception {
        return mockMvc.perform(post(BASE).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private int zonas() {
        return jdbc.queryForObject("SELECT count(*) FROM cobertura_entrega", Integer.class);
    }
}
