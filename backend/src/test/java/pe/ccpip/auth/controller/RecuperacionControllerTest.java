package pe.ccpip.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.aviso.Correo;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-33 (RF-08): recuperar la contrasena con un enlace de un solo uso que vence (RNF-01).
 * Transaccional: la cuenta, sus enlaces y sus sesiones no quedan para otras pruebas.
 */
@Transactional
class RecuperacionControllerTest extends IntegrationTest {

    private static final String CORREO = "olvido.clave@recuperar.test";
    private static final String NUEVA = "Selva2026"; // NOSONAR java:S2068 clave de prueba
    private static final Pattern ENLACE = Pattern.compile(
            "http://localhost:5173/restablecer-contrasena\\?token=([A-Za-z0-9_-]{43})");

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cuentaConLaClaveDeDemostracion() {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Rosa Olvido', 1, true FROM usuario WHERE correo = 'consumidor@ccpip.test'""",
                CORREO);
    }

    private ResultActions solicitar(String correo) throws Exception {
        return solicitar(correo, null);
    }

    private ResultActions solicitar(String correo, String idioma) throws Exception {
        var peticion = post("/api/auth/recuperar-contrasena").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", correo)));
        if (idioma != null) {
            peticion.header(HttpHeaders.ACCEPT_LANGUAGE, idioma);
        }
        return mockMvc.perform(peticion);
    }

    private ResultActions restablecer(String token, String clave) throws Exception {
        return mockMvc.perform(post("/api/auth/restablecer-contrasena").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "password", clave))));
    }

    private String tokenDelCorreo() {
        Correo correo = correos.ultimoPara(CORREO).orElseThrow(() -> new AssertionError("No llego correo a " + CORREO));
        Matcher m = ENLACE.matcher(correo.texto());
        assertThat(m.find()).as("enlace en el correo:%n%s", correo.texto()).isTrue();
        return m.group(1);
    }

    private String hash() {
        return jdbc.queryForObject("SELECT contrasena_hash FROM usuario WHERE correo = ?", String.class, CORREO);
    }

    @Test
    void elEnlaceCambiaLaClaveUnaSolaVezYCierraLasSesionesAbiertas() throws Exception {
        Cookie abierta = loginAs(CORREO);
        solicitar(CORREO).andExpect(status().isAccepted());
        Correo correo = correos.ultimoPara(CORREO).orElseThrow();
        assertThat(correo.asunto()).isEqualTo("Restablece tu contraseña de Mercado CCPIP");
        assertThat(correo.texto()).contains("vence en 30 minutos");
        String token = tokenDelCorreo();
        // Solo se guarda el hash del token
        String guardado = jdbc.queryForObject("""
                SELECT t.token_hash FROM token_un_uso t JOIN usuario u ON u.id = t.usuario_id
                WHERE u.correo = ? AND t.proposito = 'RECUPERAR_CLAVE'""", String.class, CORREO);
        assertThat(guardado).hasSize(64).isNotEqualTo(token);

        restablecer(token, NUEVA).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(CORREO));

        assertThat(passwordEncoder.matches(NUEVA, hash())).isTrue();
        mockMvc.perform(get("/api/auth/me").cookie(abierta)).andExpect(status().isUnauthorized());
        mockMvc.perform(login(CORREO, NUEVA)).andExpect(status().isOk());
        mockMvc.perform(login(CORREO, DEMO_PASSWORD)).andExpect(status().isUnauthorized());

        restablecer(token, "OtraClave1").andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ENLACE_USADO"));
        assertThat(passwordEncoder.matches(NUEVA, hash())).isTrue();
    }

    @Test
    void unEnlaceVencidoNoSirve() throws Exception {
        solicitar(CORREO).andExpect(status().isAccepted());
        String token = tokenDelCorreo();
        jdbc.update("""
                UPDATE token_un_uso SET creado_en = now() - interval '2 hours', expira_en = now() - interval '1 hour'
                WHERE usuario_id = (SELECT id FROM usuario WHERE correo = ?)""", CORREO);
        restablecer(token, NUEVA).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ENLACE_VENCIDO"));
        assertThat(passwordEncoder.matches(DEMO_PASSWORD, hash())).isTrue();
    }

    @Test
    void unEnlaceNuevoAnulaElAnteriorYNoSeReenviaEnSeguida() throws Exception {
        solicitar(CORREO).andExpect(status().isAccepted());
        String primero = tokenDelCorreo();
        // Pedirlo otra vez enseguida no envia otro correo
        correos.vaciar();
        solicitar(CORREO).andExpect(status().isAccepted());
        assertThat(correos.ultimoPara(CORREO)).isEmpty();

        jdbc.update("""
                UPDATE token_un_uso SET creado_en = now() - interval '5 minutes'
                WHERE usuario_id = (SELECT id FROM usuario WHERE correo = ?)""", CORREO);
        solicitar(CORREO).andExpect(status().isAccepted());
        String segundo = tokenDelCorreo();
        assertThat(segundo).isNotEqualTo(primero);
        restablecer(primero, NUEVA).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ENLACE_INVALIDO"));
        restablecer(segundo, NUEVA).andExpect(status().isOk());
    }

    @Test
    void responderIgualExistaONoLaCuentaYNoAvisaACuentasInactivas() throws Exception {
        solicitar("nadie@recuperar.test").andExpect(status().isAccepted());
        jdbc.update("UPDATE usuario SET activo = false, estado = 'DESACTIVADA' WHERE correo = ?", CORREO);
        solicitar(CORREO).andExpect(status().isAccepted());
        assertThat(correos.enviados()).isEmpty();
    }

    @Test
    void laNuevaClaveSigueLasReglasYUnTokenInventadoNoSirve() throws Exception {
        solicitar(CORREO).andExpect(status().isAccepted());
        String token = tokenDelCorreo();
        restablecer(token, "corta").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.password").exists());
        restablecer(token, "sinmayuscula1").andExpect(status().isBadRequest());
        restablecer("inventado", NUEVA).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENLACE_INVALIDO"));
        // El enlace sigue sirviendo despues de un intento con una clave que no cumple
        restablecer(token, NUEVA).andExpect(status().isOk());
    }

    @Test
    void restablecerLevantaElBloqueoPorIntentosFallidos() throws Exception {
        jdbc.update("UPDATE usuario SET intentos_fallidos = 5, bloqueado_hasta = now() + interval '15 minutes' WHERE correo = ?", CORREO);
        solicitar(CORREO).andExpect(status().isAccepted());
        restablecer(tokenDelCorreo(), NUEVA).andExpect(status().isOk());
        mockMvc.perform(login(CORREO, NUEVA)).andExpect(status().isOk());
    }

    @Test
    void elCorreoSaleEnElIdiomaDeLaPantalla() throws Exception {
        solicitar(CORREO, "en-US,en;q=0.9").andExpect(status().isAccepted());
        Correo correo = correos.ultimoPara(CORREO).orElseThrow();
        assertThat(correo.asunto()).isEqualTo("Reset your Mercado CCPIP password");
        assertThat(correo.texto()).contains("expires in 30 minutes");
    }
}
