package pe.ccpip.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import jakarta.servlet.http.Cookie;
import pe.ccpip.auth.config.SessionCookies;
import pe.ccpip.support.IntegrationTest;

class AuthControllerTest extends IntegrationTest {

    private static final String EMAIL = "consumidor@ccpip.test";

    // --- Inicio de sesion ---

    @Test
    void loginCorrectoEntregaCookieHttpOnlyYNoExponeElToken() throws Exception {
        mockMvc.perform(login(EMAIL, DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly(SessionCookies.NAME, true))
                .andExpect(cookie().secure(SessionCookies.NAME, true))
                .andExpect(cookie().path(SessionCookies.NAME, "/api"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict")))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.expiresInSeconds").value(1800))
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.role").value("CONSUMIDOR"))
                .andExpect(jsonPath("$.user.roleName").value("Consumidor final"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void elCorreoNoDistingueMayusculas() throws Exception {
        mockMvc.perform(login("CONSUMIDOR@CCPIP.test", DEMO_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void contrasenaIncorrectaDevuelve401() throws Exception {
        mockMvc.perform(login(EMAIL, "incorrecta"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CREDENCIALES_INVALIDAS"));
    }

    @Test
    void correoInexistenteDevuelveElMismoError() throws Exception {
        mockMvc.perform(login("nadie@ccpip.test", "incorrecta"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Correo o contrasena incorrectos"));
    }

    @Test
    void datosInvalidosDevuelven400() throws Exception {
        mockMvc.perform(login("no-es-correo", "")).andExpect(status().isBadRequest());
    }

    @Test
    void correoSinConfirmarNoPuedeIngresar() throws Exception {
        mockMvc.perform(login("sin_confirmar@ccpip.test", DEMO_PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORREO_NO_CONFIRMADO"))
                .andExpect(cookie().doesNotExist(SessionCookies.NAME));
    }

    @Test
    void conClaveIncorrectaNoSeRevelaQueElCorreoNoEstaConfirmado() throws Exception {
        mockMvc.perform(login("sin_confirmar@ccpip.test", "incorrecta"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CREDENCIALES_INVALIDAS"));
    }

    @Test
    void cincoFallosBloqueanLaCuentaAunqueLuegoLaContrasenaSeaCorrecta() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login(EMAIL, "incorrecta")).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login(EMAIL, DEMO_PASSWORD))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("CUENTA_BLOQUEADA"))
                .andExpect(jsonPath("$.lockedUntil").exists());

        // El bloqueo vive en la base: sobrevive a reinicios y despliegues
        Integer intentos = jdbc.queryForObject(
                "SELECT intentos_fallidos FROM usuario WHERE correo = ?", Integer.class, EMAIL);
        assertThat(intentos).isEqualTo(5);
    }

    @Test
    void elTokenNoSeGuardaEnClaroEnLaBase() throws Exception {
        Cookie cookie = loginAs(EMAIL);
        String guardado = jdbc.queryForObject("SELECT token_hash FROM sesion", String.class);
        assertThat(guardado).hasSize(64).isNotEqualTo(cookie.getValue());
    }

    // --- Sesion activa ---

    @Test
    void meDevuelveElUsuarioDeLaSesionSinCache() throws Exception {
        mockMvc.perform(get("/api/auth/me").cookie(loginAs(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.expiresInSeconds").value(1800));
    }

    // --- Idioma preferido (CCPIP-265) ---

    @Test
    void laCuentaNaceEnEspanol() throws Exception {
        mockMvc.perform(login(EMAIL, DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.language").value("es"));
    }

    @Test
    void elIdiomaElegidoSeConservaEnLaCuentaParaLaSiguienteSesion() throws Exception {
        Cookie cookie = loginAs(EMAIL);
        mockMvc.perform(put("/api/auth/me/language").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"en\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.language").value("en"));
        // Otro dispositivo: una sesion nueva ya llega en ingles
        mockMvc.perform(login(EMAIL, DEMO_PASSWORD))
                .andExpect(jsonPath("$.user.language").value("en"));
    }

    @Test
    void unIdiomaNoSoportadoSeRechazaSinCambiarLaPreferencia() throws Exception {
        Cookie cookie = loginAs(EMAIL);
        for (String cuerpo : new String[] {"{\"language\":\"fr\"}", "{\"language\":\"EN \"}", "{}"}) {
            mockMvc.perform(put("/api/auth/me/language").cookie(cookie)
                            .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                    .andExpect(jsonPath("$.campos.language").exists());
        }
        mockMvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(jsonPath("$.user.language").value("es"));
    }

    @Test
    void sinSesionNoSeGuardaElIdioma() throws Exception {
        mockMvc.perform(put("/api/auth/me/language")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"language\":\"en\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sinCookieOConCookieInventadaDevuelve401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIN_SESION"));
        mockMvc.perform(get("/api/auth/me").cookie(new Cookie(SessionCookies.NAME, "inventado")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unaRutaDeLaApiQueNoExisteRespondeNoEncontrada() throws Exception {
        mockMvc.perform(get("/api/cualquier-cosa")).andExpect(status().isNotFound());
    }

    // --- Cierre de sesion manual ---

    @Test
    void logoutInvalidaLaSesionEnElServidorYBorraLaCookie() throws Exception {
        Cookie cookie = loginAs(EMAIL);

        mockMvc.perform(post("/api/auth/logout").cookie(cookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(SessionCookies.NAME, 0));

        // Aunque alguien conserve la cookie vieja, el servidor ya no la acepta
        mockMvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIN_SESION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sesion", Integer.class)).isZero();
    }

    @Test
    void logoutSinSesionTambienResponde204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    // --- Expiracion automatica por inactividad (independiente del cierre manual) ---

    @Test
    void sesionInactivaExpiraConUnCodigoPropio() throws Exception {
        Cookie cookie = loginAs(EMAIL);
        jdbc.update("UPDATE sesion SET ultimo_acceso = now() - interval '31 minutes'");

        mockMvc.perform(get("/api/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESION_EXPIRADA"));
    }

    @Test
    void sesionConActividadRecienteSigueVigente() throws Exception {
        Cookie cookie = loginAs(EMAIL);
        jdbc.update("UPDATE sesion SET ultimo_acceso = now() - interval '29 minutes'");

        mockMvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk());
    }
}
