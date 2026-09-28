package pe.ccpip.auth.controller;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import pe.ccpip.auth.service.InMemoryUserStore;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthControllerTest {

    private static final String EMAIL = "consumidor@ccpip.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void loginCorrectoDevuelveTokenYUsuario() throws Exception {
        mockMvc.perform(login(EMAIL, InMemoryUserStore.DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.expiresInSeconds").value(1800))
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void contrasenaIncorrectaDevuelve401() throws Exception {
        mockMvc.perform(login(EMAIL, "incorrecta"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correoInexistenteDevuelveElMismoError() throws Exception {
        mockMvc.perform(login("nadie@ccpip.test", "incorrecta"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Correo o contrasena incorrectos"));
    }

    @Test
    void datosInvalidosDevuelven400() throws Exception {
        mockMvc.perform(login("no-es-correo", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cincoFallosBloqueanLaCuentaAunqueLuegoLaContrasenaSeaCorrecta() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login(EMAIL, "incorrecta")).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login(EMAIL, InMemoryUserStore.DEMO_PASSWORD))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.lockedUntil", notNullValue()));
    }

    @Test
    void meDevuelveElUsuarioDeLaSesion() throws Exception {
        String token = loginAndGetToken();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.role").value("CONSUMIDOR"));
    }

    @Test
    void sinTokenOTokenInvalidoDevuelve401() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer inventado"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/cualquier-cosa")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidaElTokenEnElServidor() throws Exception {
        String token = loginAndGetToken();

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String loginAndGetToken() throws Exception {
        String body = mockMvc.perform(login(EMAIL, InMemoryUserStore.DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("token").asText();
    }

    private org.springframework.test.web.servlet.RequestBuilder login(String email, String password)
            throws Exception {
        String body = objectMapper.writeValueAsString(new AuthController.LoginRequest(email, password));
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
