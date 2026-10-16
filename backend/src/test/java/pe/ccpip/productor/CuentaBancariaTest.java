package pe.ccpip.productor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-32 (RF-07) registrar, CCPIP-274 (RF-169) actualizar, CCPIP-275 (RF-170) desactivar con
 * confirmacion y CCPIP-276 (RF-171) reactivar validando datos y contrasena. Vender exige la cuenta
 * ACTIVA: desactivada, las ofertas siguen visibles pero no se compran.
 */
@Transactional
class CuentaBancariaTest extends IntegrationTest {

    private static final String RUTA = "/api/productor/cuenta-bancaria";

    private static Map<String, Object> datos() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("titular", "Comunidad Kukama de Nauta");
        d.put("documento", "20609876543");
        d.put("banco", "Banco de la Nación");
        d.put("tipoCuenta", "AHORROS");
        d.put("numeroCuenta", "04-110-123456");
        d.put("cci", "018-110-000041101234-56");
        d.put("moneda", "PEN");
        d.put("correoAvisos", "kukama.ventas@ccpip.test");
        d.put("yapeCelular", "987 111 222");
        return d;
    }

    private ResultActions enviar(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder peticion,
                                 Cookie sesion, Object cuerpo) throws Exception {
        return mockMvc.perform(peticion.cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    // --- CCPIP-32 registrar ---

    @Test
    void sinCuentaRegistradaLaConsultaLoDice() throws Exception {
        mockMvc.perform(get(RUTA).cookie(loginAs("productor_nuevo@ccpip.test")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUENTA_NO_REGISTRADA"));
    }

    @Test
    void registraLaCuentaActivaConDatosNormalizadosYSinDatosDeTarjeta() throws Exception {
        Cookie nuevo = loginAs("productor_nuevo@ccpip.test");
        enviar(post(RUTA), nuevo, datos())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.cci").value("01811000004110123456"))
                .andExpect(jsonPath("$.yapeCelular").value("987111222"))
                .andExpect(jsonPath("$.numeroCuenta").value("04-110-123456"))
                .andExpect(jsonPath("$.cvv").doesNotExist())
                .andExpect(jsonPath("$.vencimiento").doesNotExist());
        mockMvc.perform(get(RUTA).cookie(nuevo))
                .andExpect(jsonPath("$.titular").value("Comunidad Kukama de Nauta"))
                .andExpect(jsonPath("$.banco").value("Banco de la Nación"));
        // Una sola cuenta por productor: la siguiente vez se actualiza
        enviar(post(RUTA), nuevo, datos()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUENTA_YA_REGISTRADA"));
    }

    @Test
    void rechazaDatosInvalidosCampoPorCampo() throws Exception {
        Cookie nuevo = loginAs("productor_nuevo@ccpip.test");
        Map<String, Object> malos = datos();
        malos.put("documento", "123");
        malos.put("titular", "");
        malos.put("cci", "abc");
        enviar(post(RUTA), nuevo, malos)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.documento").value("Escribe un DNI de 8 dígitos o un RUC de 11"))
                .andExpect(jsonPath("$.campos.titular").exists())
                .andExpect(jsonPath("$.campos.cci").exists());

        Map<String, Object> banco = datos();
        banco.put("banco", "Banco Inventado");
        enviar(post(RUTA), nuevo, banco).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BANCO_INVALIDO"));
        Map<String, Object> cci = datos();
        cci.put("cci", "0181100000411012345");
        enviar(post(RUTA), nuevo, cci).andExpect(status().isBadRequest());
        Map<String, Object> yape = datos();
        yape.put("yapeCelular", "123");
        enviar(post(RUTA), nuevo, yape).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("YAPE_INVALIDO"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM cuenta_bancaria c JOIN productor p ON p.id = c.productor_id
                JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor_nuevo@ccpip.test'""", Long.class)).isZero();
    }

    @Test
    void yapeEsOpcionalYNoReemplazaLaCuenta() throws Exception {
        Cookie nuevo = loginAs("productor_nuevo@ccpip.test");
        Map<String, Object> sinYape = datos();
        sinYape.remove("yapeCelular");
        sinYape.remove("correoAvisos");
        enviar(post(RUTA), nuevo, sinYape).andExpect(status().isCreated())
                .andExpect(jsonPath("$.yapeCelular").doesNotExist());
        Map<String, Object> soloYape = new LinkedHashMap<>(Map.of("yapeCelular", "987111222"));
        enviar(put(RUTA), nuevo, soloYape).andExpect(status().isBadRequest());
    }

    // --- CCPIP-274 actualizar ---

    @Test
    void actualizaLosDatosDeSuCuenta() throws Exception {
        Cookie productor = loginAs("productor@ccpip.test");
        Map<String, Object> nuevos = datos();
        nuevos.put("banco", "Interbank");
        nuevos.put("tipoCuenta", "CORRIENTE");
        nuevos.put("moneda", "USD");
        enviar(put(RUTA), productor, nuevos)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banco").value("Interbank"))
                .andExpect(jsonPath("$.tipoCuenta").value("CORRIENTE"))
                .andExpect(jsonPath("$.moneda").value("USD"))
                .andExpect(jsonPath("$.estado").value("ACTIVA"));
    }

    @Test
    void cadaProductorVeYCambiaSoloSuCuenta() throws Exception {
        String otra = jdbc.queryForObject("""
                SELECT c.numero_cuenta FROM cuenta_bancaria c JOIN productor p ON p.id = c.productor_id
                JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor2@ccpip.test'""", String.class);
        enviar(put(RUTA), loginAs("productor@ccpip.test"), datos()).andExpect(status().isOk());
        mockMvc.perform(get(RUTA).cookie(loginAs("productor2@ccpip.test")))
                .andExpect(jsonPath("$.numeroCuenta").value(otra));
        // Otros roles no tienen cuenta bancaria de productor
        mockMvc.perform(get(RUTA).cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
    }

    // --- CCPIP-275 desactivar y CCPIP-276 reactivar ---

    @Test
    void desactivarExigeMotivoYConfirmacionExpresa() throws Exception {
        Cookie productor = loginAs("productor@ccpip.test");
        enviar(post(RUTA + "/desactivar"), productor, Map.of("motivo", "ROBO", "confirmo", false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.confirmo").value("Confirma que quieres desactivar la cuenta"));
        enviar(post(RUTA + "/desactivar"), productor, Map.of("confirmo", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.motivo").exists());
        mockMvc.perform(get(RUTA).cookie(productor)).andExpect(jsonPath("$.estado").value("ACTIVA"));
    }

    @Test
    void desactivadaLaOfertaSigueVisiblePeroNoSeCompraHastaReactivarla() throws Exception {
        Cookie productor = loginAs("productor@ccpip.test");
        long producto = productoConLote("Aguaje de la cuenta", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.comprable").value(true));

        enviar(post(RUTA + "/desactivar"), productor, Map.of("motivo", "BLOQUEO_BANCO", "confirmo", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DESACTIVADA"))
                .andExpect(jsonPath("$.motivoDesactivacion").value("BLOQUEO_BANCO"))
                .andExpect(jsonPath("$.desactivadaEn").isNotEmpty());
        // La publicacion sigue consultable, en "Venta pausada"
        mockMvc.perform(get("/api/productos/{id}", producto))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comprable").value(false));
        // Desactivada, se corrige al reactivar, no con una actualizacion suelta
        enviar(put(RUTA), productor, datos()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUENTA_DESACTIVADA"));

        // Reactivar valida los datos y la contrasena
        enviar(post(RUTA + "/reactivar"), productor, Map.of("datos", datos(), "contrasena", "Equivocada1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONTRASENA_INCORRECTA"));
        Map<String, Object> malos = datos();
        malos.put("documento", "1");
        enviar(post(RUTA + "/reactivar"), productor, Map.of("datos", malos, "contrasena", DEMO_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos['datos.documento']").exists());
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.comprable").value(false));

        enviar(post(RUTA + "/reactivar"), productor, Map.of("datos", datos(), "contrasena", DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.motivoDesactivacion").doesNotExist())
                .andExpect(jsonPath("$.documento").value("20609876543"));
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.comprable").value(true));
        enviar(post(RUTA + "/reactivar"), productor, Map.of("datos", datos(), "contrasena", DEMO_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUENTA_ACTIVA"));
    }

    @Test
    void losPedidosYaPagadosSiguenSuCursoConLaCuentaDesactivada() throws Exception {
        long producto = productoConLote("Aguaje ya pagado", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        long pedido = pedidoConfirmado("CB-0001", "Centro Nacional Lima", producto, 1);
        enviar(post(RUTA + "/desactivar"), loginAs("productor@ccpip.test"), Map.of("motivo", "SEGURIDAD", "confirmo", true))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/acopio/pedidos/{numero}/listo", "CB-0001").cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido)).isEqualTo("LISTO_PARA_RECOJO");
    }

    @Test
    void ofreceSoloBancosDeLaLista() throws Exception {
        mockMvc.perform(get(RUTA + "/bancos").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@ == 'Banco de la Nación')]").isNotEmpty());
    }
}
