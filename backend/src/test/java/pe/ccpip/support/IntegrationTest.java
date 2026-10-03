package pe.ccpip.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;
import pe.ccpip.auth.config.SessionCookies;
import pe.ccpip.auth.controller.AuthController;

/**
 * Base de las pruebas contra PostgreSQL real (Testcontainers, ver pom.xml) con las migraciones
 * y cuentas sembradas. Un solo contexto para toda la suite; cada prueba limpia sesiones y bloqueos.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    /** Clave comun de las cuentas de demostracion sembradas en R__02 (publica a proposito, CCPIP-23). */
    protected static final String DEMO_PASSWORD = "Demo1234!"; // NOSONAR java:S2068 credencial de demo intencional

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetAuthState() {
        jdbc.update("DELETE FROM sesion");
        jdbc.update("UPDATE usuario SET intentos_fallidos = 0, bloqueado_hasta = NULL");
    }

    protected RequestBuilder login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new AuthController.LoginRequest(email, password));
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Inicia sesion y devuelve la cookie para usarla en las siguientes peticiones. */
    protected Cookie loginAs(String email) throws Exception {
        Cookie cookie = mockMvc.perform(login(email, DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionCookies.NAME);
        return new Cookie(cookie.getName(), cookie.getValue());
    }
}
