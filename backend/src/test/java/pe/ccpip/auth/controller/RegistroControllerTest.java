package pe.ccpip.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import pe.ccpip.auth.config.SessionCookies;
import pe.ccpip.aviso.Correo;
import pe.ccpip.support.IntegrationTest;

/** CCPIP-26 (registro) y CCPIP-328 (verificacion del correo con enlace de un solo uso). */
class RegistroControllerTest extends IntegrationTest {

    private static final String CORREO = "lucia.ramirez@registro.test";
    private static final String CLAVE = "Bosque2026"; // NOSONAR java:S2068 clave de prueba
    private static final Pattern ENLACE = Pattern.compile(
            "http://localhost:5173/verificar-correo\\?token=([A-Za-z0-9_-]{43})");

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void borrarCuentasDePrueba() {
        jdbc.update("DELETE FROM token_un_uso WHERE usuario_id IN (SELECT id FROM usuario WHERE correo LIKE '%@registro.test')");
        jdbc.update("DELETE FROM sesion WHERE usuario_id IN (SELECT id FROM usuario WHERE correo LIKE '%@registro.test')");
        jdbc.update("DELETE FROM usuario WHERE correo LIKE '%@registro.test'");
    }

    // --- CCPIP-26: registro ---

    @Test
    void registroCreaUnConsumidorSinConfirmarYEnviaElEnlace() throws Exception {
        registrar(datos())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(CORREO))
                .andExpect(jsonPath("$.emailSent").value(true))
                .andExpect(cookie().doesNotExist(SessionCookies.NAME));

        Map<String, Object> fila = jdbc.queryForMap("""
                SELECT u.correo, u.nombre, u.telefono, u.correo_confirmado, u.activo, u.contrasena_hash, r.codigo
                FROM usuario u JOIN rol r ON r.id = u.rol_id WHERE u.correo = ?""", CORREO);
        assertThat(fila).containsEntry("nombre", "Lucía Ramírez Torres")
                .containsEntry("telefono", "987 654 321")
                .containsEntry("correo_confirmado", false)
                .containsEntry("activo", true)
                .containsEntry("codigo", "CONSUMIDOR");
        String hash = (String) fila.get("contrasena_hash");
        assertThat(hash).isNotEqualTo(CLAVE);
        assertThat(passwordEncoder.matches(CLAVE, hash)).isTrue();

        String token = tokenDelCorreo(CORREO);
        String guardado = jdbc.queryForObject("""
                SELECT t.token_hash FROM token_un_uso t JOIN usuario u ON u.id = t.usuario_id
                WHERE u.correo = ? AND t.proposito = 'VERIFICAR_CORREO'""", String.class, CORREO);
        assertThat(guardado).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void elCorreoSeGuardaEnMinusculasYElCelularEsOpcional() throws Exception {
        Map<String, Object> datos = datos();
        datos.put("email", "Lucia.Ramirez@REGISTRO.test");
        datos.put("phone", "");
        registrar(datos).andExpect(status().isCreated()).andExpect(jsonPath("$.email").value(CORREO));
        assertThat(jdbc.queryForObject("SELECT telefono FROM usuario WHERE correo = ?", String.class, CORREO)).isNull();
    }

    @Test
    void sinVerificarElCorreoNoPuedeIngresar() throws Exception {
        registrar(datos()).andExpect(status().isCreated());
        mockMvc.perform(login(CORREO, CLAVE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORREO_NO_CONFIRMADO"));
    }

    @Test
    void unCorreoYaRegistradoSeRechazaSinImportarMayusculas() throws Exception {
        Map<String, Object> datos = datos();
        datos.put("email", "CONSUMIDOR@ccpip.test");
        registrar(datos)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORREO_YA_REGISTRADO"));
        assertThat(correos.enviados()).isEmpty();
    }

    @Test
    void datosInvalidosSeRechazanPorCampo() throws Exception {
        Map<String, Object> datos = datos();
        datos.put("firstName", " ");
        datos.put("email", "no-es-correo");
        datos.put("phone", "abc");
        datos.put("password", "sinmayuscula1");
        datos.put("acceptTerms", false);
        registrar(datos)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.firstName").exists())
                .andExpect(jsonPath("$.campos.email").exists())
                .andExpect(jsonPath("$.campos.phone").exists())
                .andExpect(jsonPath("$.campos.password").value("Incluye una mayúscula y un número"))
                .andExpect(jsonPath("$.campos.acceptTerms").exists());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuario WHERE correo LIKE '%@registro.test'", Long.class)).isZero();
    }

    @Test
    void unaContrasenaCortaSeRechaza() throws Exception {
        Map<String, Object> datos = datos();
        datos.put("password", "Corta1");
        registrar(datos).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.password").exists());
    }

    @Test
    void unaContrasenaDeMasDe72BytesSeRechazaEnVezDeTruncarse() throws Exception {
        Map<String, Object> datos = datos();
        datos.put("password", "Ñ1" + "ñ".repeat(40));
        registrar(datos)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONTRASENA_MUY_LARGA"));
    }

    @Test
    void siElCorreoNoSaleLaCuentaQuedaCreadaYSeAvisa() throws Exception {
        correos.fallar(true);
        registrar(datos())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailSent").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuario WHERE correo = ?", Long.class, CORREO)).isOne();
    }

    @Test
    void elCorreoLlegaEnElIdiomaDeLaPantalla() throws Exception {
        mockMvc.perform(post("/api/auth/registro").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en").content(json(datos())))
                .andExpect(status().isCreated());
        Correo correo = correos.ultimoPara(CORREO).orElseThrow();
        assertThat(correo.asunto()).isEqualTo("Verify your email for Mercado CCPIP");
        assertThat(correo.texto()).contains("Hello, Lucía Ramírez Torres").contains("expires in 24 hours");

        borrarCuentasDePrueba();
        registrar(datos()).andExpect(status().isCreated());
        assertThat(correos.ultimoPara(CORREO).orElseThrow().asunto()).isEqualTo("Verifica tu correo en Mercado CCPIP");
    }

    // --- CCPIP-328: verificacion ---

    @Test
    void elEnlaceConfirmaElCorreoYPermiteIngresar() throws Exception {
        registrar(datos()).andExpect(status().isCreated());
        verificar(tokenDelCorreo(CORREO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(CORREO))
                .andExpect(cookie().doesNotExist(SessionCookies.NAME));

        assertThat(jdbc.queryForObject("SELECT correo_confirmado FROM usuario WHERE correo = ?", Boolean.class, CORREO)).isTrue();
        mockMvc.perform(login(CORREO, CLAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("CONSUMIDOR"))
                .andExpect(jsonPath("$.user.fullName").value("Lucía Ramírez Torres"));
    }

    @Test
    void elEnlaceSirveUnaSolaVez() throws Exception {
        registrar(datos()).andExpect(status().isCreated());
        String token = tokenDelCorreo(CORREO);
        verificar(token).andExpect(status().isOk());
        verificar(token)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ENLACE_USADO"));
    }

    @Test
    void unEnlaceVencidoNoConfirma() throws Exception {
        registrar(datos()).andExpect(status().isCreated());
        jdbc.update("""
                UPDATE token_un_uso SET creado_en = now() - interval '2 days', expira_en = now() - interval '1 day'
                WHERE usuario_id = (SELECT id FROM usuario WHERE correo = ?)""", CORREO);
        verificar(tokenDelCorreo(CORREO))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ENLACE_VENCIDO"));
        assertThat(jdbc.queryForObject("SELECT correo_confirmado FROM usuario WHERE correo = ?", Boolean.class, CORREO)).isFalse();
    }

    @Test
    void unEnlaceInventadoEsInvalido() throws Exception {
        verificar("x".repeat(43))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENLACE_INVALIDO"));
    }

    @Test
    void sinTokenLaPeticionEsInvalida() throws Exception {
        verificar("").andExpect(status().isBadRequest());
    }

    // --- CCPIP-328: reenvio ---

    @Test
    void reenviarEmiteUnEnlaceNuevoYElAnteriorDejaDeServir() throws Exception {
        registrar(datos()).andExpect(status().isCreated());
        String anterior = tokenDelCorreo(CORREO);
        haceDosMinutos();

        reenviar(CORREO).andExpect(status().isAccepted());
        String nuevo = tokenDelCorreo(CORREO);
        assertThat(nuevo).isNotEqualTo(anterior);
        verificar(anterior).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ENLACE_INVALIDO"));
        verificar(nuevo).andExpect(status().isOk());
    }

    @Test
    void reenviarSeguidoNoEnviaOtroCorreo() throws Exception {
        registrar(datos()).andExpect(status().isCreated());
        reenviar(CORREO).andExpect(status().isAccepted());
        assertThat(correos.enviados()).hasSize(1);
    }

    @Test
    void reenviarNoRevelaSiElCorreoTieneCuenta() throws Exception {
        reenviar("nadie@registro.test").andExpect(status().isAccepted());
        // Cuenta ya confirmada: tampoco se envia nada
        reenviar("consumidor@ccpip.test").andExpect(status().isAccepted());
        assertThat(correos.enviados()).isEmpty();
    }

    // --- Ayudas ---

    private Map<String, Object> datos() {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("firstName", " Lucía ");
        datos.put("lastName", "Ramírez   Torres");
        datos.put("email", CORREO);
        datos.put("phone", "987 654 321");
        datos.put("password", CLAVE);
        datos.put("acceptTerms", true);
        return datos;
    }

    private ResultActions registrar(Map<String, Object> datos) throws Exception {
        return mockMvc.perform(post("/api/auth/registro").contentType(MediaType.APPLICATION_JSON).content(json(datos)));
    }

    private ResultActions verificar(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/verificar-correo").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token))));
    }

    private ResultActions reenviar(String correo) throws Exception {
        return mockMvc.perform(post("/api/auth/verificar-correo/reenviar").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", correo))));
    }

    private String tokenDelCorreo(String para) {
        Correo correo = correos.ultimoPara(para).orElseThrow(() -> new AssertionError("No llego correo a " + para));
        Matcher m = ENLACE.matcher(correo.texto());
        assertThat(m.find()).as("enlace en el correo:%n%s", correo.texto()).isTrue();
        return m.group(1);
    }

    /** Lleva los enlaces de la cuenta al pasado para que el reenvio no espere el minuto minimo. */
    private void haceDosMinutos() {
        jdbc.update("""
                UPDATE token_un_uso SET creado_en = creado_en - interval '2 minutes'
                WHERE usuario_id = (SELECT id FROM usuario WHERE correo = ?)""", CORREO);
    }
}
