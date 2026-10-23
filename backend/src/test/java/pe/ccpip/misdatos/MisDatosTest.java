package pe.ccpip.misdatos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CW-13 "Mis datos": CCPIP-400 consultar y CCPIP-113 editar los datos personales y de contacto del
 * consumidor. Solo los propios, sin genero ni fecha de nacimiento, sin tocar los pedidos ya hechos.
 */
@Transactional
class MisDatosTest extends IntegrationTest {

    private static final String RUTA = "/api/cuenta/datos";
    private static final String CONSUMIDOR = "consumidor@ccpip.test";

    @Test
    void consultaSusDatosConElNombreCompletoComoNombresEnUnaCuentaAnterior() throws Exception {
        String nombre = jdbc.queryForObject("SELECT nombre FROM usuario WHERE correo = ?", String.class, CONSUMIDOR);
        mockMvc.perform(get(RUTA).cookie(loginAs(CONSUMIDOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombres").value(nombre))
                .andExpect(jsonPath("$.apellidos").value(""))
                .andExpect(jsonPath("$.correo").value(CONSUMIDOR))
                .andExpect(jsonPath("$.correoConfirmado").value(true))
                .andExpect(jsonPath("$.genero").doesNotExist())
                .andExpect(jsonPath("$.fechaNacimiento").doesNotExist());
    }

    @Test
    void editaNombresApellidosYCelularYElNombreDeLaCuentaCambiaConEllos() throws Exception {
        Cookie sesion = loginAs(CONSUMIDOR);
        editar(sesion, datos("  Lucía ", "Ramírez   Torres", "987 654 321"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombres").value("Lucía"))
                .andExpect(jsonPath("$.apellidos").value("Ramírez Torres"))
                .andExpect(jsonPath("$.celular").value("987 654 321"));

        mockMvc.perform(get("/api/auth/me").cookie(sesion)).andExpect(jsonPath("$.user.fullName").value("Lucía Ramírez Torres"));
        mockMvc.perform(get(RUTA).cookie(sesion)).andExpect(jsonPath("$.apellidos").value("Ramírez Torres"));

        // Sin celular tambien es valido
        editar(sesion, datos("Lucía", "Ramírez", "")).andExpect(status().isOk()).andExpect(jsonPath("$.celular").doesNotExist());
    }

    @Test
    void elCorreoNoSeCambiaDesdeMisDatos() throws Exception {
        Cookie sesion = loginAs(CONSUMIDOR);
        Map<String, Object> cuerpo = datos("Lucía", "Ramírez", null);
        cuerpo.put("correo", "otro@ccpip.test");
        cuerpo.put("genero", "F");
        editar(sesion, cuerpo).andExpect(status().isOk()).andExpect(jsonPath("$.correo").value(CONSUMIDOR));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuario WHERE correo = 'otro@ccpip.test'", Integer.class)).isZero();
    }

    @Test
    void rechazaDatosInvalidosSinCambiarNada() throws Exception {
        Cookie sesion = loginAs(CONSUMIDOR);
        String antes = jdbc.queryForObject("SELECT nombre FROM usuario WHERE correo = ?", String.class, CONSUMIDOR);
        editar(sesion, datos(" ", "Ramírez", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.nombres").isString());
        editar(sesion, datos("Lucía", "x".repeat(80), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.apellidos").isString());
        editar(sesion, datos("Lucía", "Ramírez", "abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.celular").isString());
        assertThat(jdbc.queryForObject("SELECT nombre FROM usuario WHERE correo = ?", String.class, CONSUMIDOR)).isEqualTo(antes);
    }

    @Test
    void editarSusDatosNoCambiaLosPedidosYaHechos() throws Exception {
        long producto = productoConLote("Mis datos pedido", "PUBLICADO", "PUNTO_ACOPIO", java.time.LocalDate.now(java.time.ZoneId.of("America/Lima")).plusMonths(3));
        long pedido = pedidoConfirmado("CCP-MD01", "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET celular_contacto = '999 111 222', persona_autorizada = 'Rosa Quispe' WHERE id = ?", pedido);

        editar(loginAs(CONSUMIDOR), datos("Lucía", "Ramírez", "987 654 321")).andExpect(status().isOk());

        Map<String, Object> despues = jdbc.queryForMap("SELECT celular_contacto, persona_autorizada FROM pedido WHERE id = ?", pedido);
        assertThat(despues).containsEntry("celular_contacto", "999 111 222").containsEntry("persona_autorizada", "Rosa Quispe");
    }

    @Test
    void soloElConsumidorYSoloSusDatos() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(RUTA).cookie(loginAs("productor@ccpip.test"))).andExpect(status().isForbidden());
        editar(loginAs("admin_acopio@ccpip.test"), datos("Otra", "Persona", null)).andExpect(status().isForbidden());
        // No hay ruta para ver o editar los datos de otra persona por id
        mockMvc.perform(get(RUTA + "/1").cookie(loginAs(CONSUMIDOR))).andExpect(status().is4xxClientError());
        mockMvc.perform(post(RUTA).cookie(loginAs(CONSUMIDOR))).andExpect(status().is4xxClientError());
    }

    @Test
    void elRegistroGuardaNombresYApellidosPorSeparado() throws Exception {
        mockMvc.perform(post("/api/auth/registro").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                        "firstName", "Ana", "lastName", "Quispe Mamani", "email", "ana.misdatos@ccpip.test",
                        "phone", "", "password", "Clave1234", "acceptTerms", true))))
                .andExpect(status().is2xxSuccessful());
        Map<String, Object> fila = jdbc.queryForMap("SELECT nombre, nombres, apellidos FROM usuario WHERE correo = 'ana.misdatos@ccpip.test'");
        assertThat(fila).containsEntry("nombre", "Ana Quispe Mamani").containsEntry("nombres", "Ana")
                .containsEntry("apellidos", "Quispe Mamani");
    }

    private ResultActions editar(Cookie sesion, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(put(RUTA).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private static Map<String, Object> datos(String nombres, String apellidos, String celular) {
        Map<String, Object> c = new HashMap<>();
        c.put("nombres", nombres);
        c.put("apellidos", apellidos);
        c.put("celular", celular);
        return c;
    }
}
