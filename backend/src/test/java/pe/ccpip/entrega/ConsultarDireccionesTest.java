package pe.ccpip.entrega;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-393 (RF-285): el comprador consulta sus direcciones de entrega guardadas. Solo las propias y activas, la
 * ultima que guardo primero; las de otros compradores y las retiradas no aparecen.
 */
@Transactional
class ConsultarDireccionesTest extends IntegrationTest {

    private static final String RUTA = "/api/cuenta/direcciones";
    private static final String COMPRADOR = "consumidor@ccpip.test";

    private Cookie comprador;

    @BeforeEach
    void sesion() throws Exception {
        jdbc.update("DELETE FROM direccion_guardada WHERE usuario_id = (SELECT id FROM usuario WHERE correo = ?)", COMPRADOR);
        comprador = loginAs(COMPRADOR);
    }

    /** Guarda una direccion del {@code correo} directo en la base, hace {@code minutos} minutos. */
    private long direccion(String correo, String alias, String ubigeo, String direccion, String referencia, int minutos) {
        return jdbc.queryForObject("""
                INSERT INTO direccion_guardada (usuario_id, alias, ubigeo, direccion, referencia, creado_en)
                SELECT id, ?, ?, ?, ?, now() - make_interval(mins => ?) FROM usuario WHERE correo = ? RETURNING id""",
                Long.class, alias, ubigeo, direccion, referencia, minutos, correo);
    }

    private ResultActions consultar(Cookie sesion) throws Exception {
        var peticion = get(RUTA);
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    @Test
    void devuelveSusDireccionesConSuDistritoLaUltimaPrimero() throws Exception {
        long casa = direccion(COMPRADOR, "Casa", "150122", "Av. Larco 123, dpto. 4", "Frente al parque Kennedy", 60);
        long trabajo = direccion(COMPRADOR, null, "080108", "Av. de la Cultura 1234", null, 5);

        consultar(comprador)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", contains((int) trabajo, (int) casa)))
                .andExpect(jsonPath("$[0].alias").doesNotExist())
                .andExpect(jsonPath("$[0].ubigeo").value("080108"))
                .andExpect(jsonPath("$[0].distrito").value("Wanchaq"))
                .andExpect(jsonPath("$[0].provincia").value("Cusco"))
                .andExpect(jsonPath("$[0].departamento").value("Cusco"))
                .andExpect(jsonPath("$[1].alias").value("Casa"))
                .andExpect(jsonPath("$[1].direccion").value("Av. Larco 123, dpto. 4"))
                .andExpect(jsonPath("$[1].referencia").value("Frente al parque Kennedy"))
                .andExpect(jsonPath("$[1].distrito").value("Miraflores"));
    }

    @Test
    void sinDireccionesDevuelveUnaListaVacia() throws Exception {
        consultar(comprador).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void lasRetiradasYaNoAparecen() throws Exception {
        long casa = direccion(COMPRADOR, "Casa", "150122", "Av. Larco 123, dpto. 4", null, 10);
        direccion(COMPRADOR, "Trabajo", "150131", "Av. Javier Prado Este 500", null, 5);
        mockMvc.perform(delete(RUTA + "/{id}", casa).cookie(comprador)).andExpect(status().isNoContent());

        consultar(comprador)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].alias").value("Trabajo"));
    }

    @Test
    void soloVeLasSuyasNuncaLasDeOtroComprador() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'otra.compradora@ccpip.test', contrasena_hash, 'Otra Compradora', rol_id, true
                FROM usuario WHERE correo = ?""", COMPRADOR);
        direccion("otra.compradora@ccpip.test", "Casa de otra", "150122", "Calle Ajena 999", null, 1);
        direccion(COMPRADOR, "Casa", "150122", "Av. Larco 123, dpto. 4", null, 2);

        consultar(comprador)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(content().string(not(containsString("Calle Ajena"))));
        consultar(loginAs("otra.compradora@ccpip.test"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].direccion").value("Calle Ajena 999"));
    }

    @Test
    void esSoloParaElComprador() throws Exception {
        consultar(null).andExpect(status().isUnauthorized());
        for (String correo : new String[] {"productor@ccpip.test", "admin_acopio@ccpip.test", "distribuidor@ccpip.test",
                "sub_admin@ccpip.test", "sudo@ccpip.test"}) {
            consultar(loginAs(correo)).andExpect(status().isForbidden());
        }
    }
}
