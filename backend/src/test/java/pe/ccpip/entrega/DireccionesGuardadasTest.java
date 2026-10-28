package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-65 (RF-46): el comprador guarda sus direcciones de entrega habituales. Solo datos propios (el
 * comprador sale de la sesion) y solo destinos admitidos (distritos del catalogo territorial).
 */
@Transactional
class DireccionesGuardadasTest extends IntegrationTest {

    private static final String RUTA = "/api/cuenta/direcciones";
    private static final String COMPRADOR = "consumidor@ccpip.test";

    private static Map<String, Object> direccion() {
        Map<String, Object> d = new HashMap<>();
        d.put("alias", "Casa");
        d.put("ubigeo", "150122");
        d.put("direccion", "Av. Larco 123, dpto. 4");
        d.put("referencia", "Frente al parque Kennedy");
        return d;
    }

    private ResultActions guardar(Cookie sesion, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post(RUTA).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private long guardadas(String correo) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM direccion_guardada d JOIN usuario u ON u.id = d.usuario_id
                WHERE u.correo = ? AND d.activa""", Long.class, correo);
    }

    private long direccionGuardada(String correo) {
        return jdbc.queryForObject("""
                INSERT INTO direccion_guardada (usuario_id, alias, ubigeo, direccion, referencia)
                SELECT id, 'Casa', '150122', 'Av. Larco 123, dpto. 4', 'Frente al parque Kennedy'
                FROM usuario WHERE correo = ? RETURNING id""", Long.class, correo);
    }

    private void crearOtraCompradora() {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'otra.compradora@ccpip.test', contrasena_hash, 'Otra Compradora', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""");
    }

    private ResultActions retirar(Cookie sesion, long id) throws Exception {
        return mockMvc.perform(delete(RUTA + "/{id}", id).cookie(sesion));
    }

    private long pedidoConDestinoHistorico(String correo) {
        long pedido = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total)
                SELECT 'DIR-HISTORICA', u.id, 'ENVIO', l.id, 1.00
                FROM usuario u, ubicacion l
                WHERE u.correo = ? AND l.nombre = 'Centro Nacional Lima' RETURNING id""", Long.class, correo);
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, referencia, contacto_nombre, contacto_celular)
                VALUES (?, '150122', 'Av. Larco 123, dpto. 4', 'Frente al parque Kennedy', 'Ana Quispe', '987654321')""",
                pedido);
        return pedido;
    }

    private String destinoHistorico(long pedido) {
        return jdbc.queryForObject("""
                SELECT ubigeo || '|' || direccion || '|' || referencia || '|' || contacto_nombre || '|' || contacto_celular
                FROM entrega WHERE pedido_id = ?""", String.class, pedido);
    }

    @Test
    void editaLaDireccionPropiaSinCambiarPedidoConfirmado() throws Exception {
        Cookie comprador = loginAs(COMPRADOR);
        long id = direccionGuardada(COMPRADOR);
        long pedido = pedidoConDestinoHistorico(COMPRADOR);
        String destinoAntes = destinoHistorico(pedido);
        Map<String, Object> cambios = direccion();
        cambios.put("alias", "Casa nueva");
        cambios.put("direccion", "Av. Arequipa 456, dpto. 8");
        mockMvc.perform(put(RUTA + "/{id}", id).cookie(comprador).contentType(MediaType.APPLICATION_JSON)
                .content(json(cambios)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.alias").value("Casa nueva"))
                .andExpect(jsonPath("$.direccion").value("Av. Arequipa 456, dpto. 8"));
        assertThat(destinoHistorico(pedido)).isEqualTo(destinoAntes);
    }

    @Test
    void guardaLaDireccionDelCompradorConSuDistritoProvinciaYDepartamento() throws Exception {
        guardar(loginAs(COMPRADOR), direccion())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.alias").value("Casa"))
                .andExpect(jsonPath("$.ubigeo").value("150122"))
                .andExpect(jsonPath("$.distrito").value("Miraflores"))
                .andExpect(jsonPath("$.provincia").value("Lima"))
                .andExpect(jsonPath("$.departamento").value("Lima"))
                .andExpect(jsonPath("$.direccion").value("Av. Larco 123, dpto. 4"))
                .andExpect(jsonPath("$.referencia").value("Frente al parque Kennedy"));
        assertThat(guardadas(COMPRADOR)).isEqualTo(1);
    }

    @Test
    void elAliasYLaReferenciaSonOpcionales() throws Exception {
        Map<String, Object> minima = direccion();
        minima.remove("alias");
        minima.put("referencia", "  ");
        guardar(loginAs(COMPRADOR), minima)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alias").value(nullValue()))
                .andExpect(jsonPath("$.referencia").value(nullValue()));
    }

    @Test
    void soloGuardaDatosPropiosElCompradorSaleDeLaSesion() throws Exception {
        long otro = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = 'productor@ccpip.test'", Long.class);
        Map<String, Object> ajena = direccion();
        ajena.put("usuarioId", otro);
        ajena.put("usuario_id", otro);
        guardar(loginAs(COMPRADOR), ajena).andExpect(status().isCreated());
        assertThat(guardadas(COMPRADOR)).isEqualTo(1);
        assertThat(guardadas("productor@ccpip.test")).isZero();
    }

    @Test
    void soloAdmiteDestinosDelCatalogoTerritorial() throws Exception {
        Map<String, Object> inexistente = direccion();
        inexistente.put("ubigeo", "999999");
        guardar(loginAs(COMPRADOR), inexistente).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESTINO_NO_ADMITIDO"));
        Map<String, Object> malFormado = direccion();
        malFormado.put("ubigeo", "15");
        guardar(loginAs(COMPRADOR), malFormado).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.ubigeo").value("El distrito no es válido"));
        assertThat(guardadas(COMPRADOR)).isZero();
    }

    @Test
    void exigeUnaDireccionCompleta() throws Exception {
        Map<String, Object> corta = direccion();
        corta.put("direccion", "12345");
        guardar(loginAs(COMPRADOR), corta).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DIRECCION_INVALIDA"));
        Map<String, Object> vacia = direccion();
        vacia.put("direccion", " ");
        guardar(loginAs(COMPRADOR), vacia).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.direccion").value("Indica la dirección"));
        Map<String, Object> largo = direccion();
        largo.put("alias", "x".repeat(41));
        guardar(loginAs(COMPRADOR), largo).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.alias").exists());
        assertThat(guardadas(COMPRADOR)).isZero();
    }

    @Test
    void noGuardaDosVecesLaMismaDireccionPeroOtroCompradorSi() throws Exception {
        Cookie comprador = loginAs(COMPRADOR);
        guardar(comprador, direccion()).andExpect(status().isCreated());
        Map<String, Object> igual = direccion();
        igual.put("alias", "Oficina");
        igual.put("direccion", "  AV. LARCO 123, DPTO. 4 ");
        guardar(comprador, igual).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIRECCION_YA_GUARDADA"));
        assertThat(guardadas(COMPRADOR)).isEqualTo(1);

        // En otro distrito, o con otro texto, es otra direccion
        Map<String, Object> otroDistrito = direccion();
        otroDistrito.put("ubigeo", "150131");
        guardar(comprador, otroDistrito).andExpect(status().isCreated());
        assertThat(guardadas(COMPRADOR)).isEqualTo(2);

        // Otro comprador puede guardar la misma direccion
        crearOtraCompradora();
        guardar(loginAs("otra.compradora@ccpip.test"), direccion()).andExpect(status().isCreated());
    }

    @Test
    void retiraSoloSuDireccionActivaSinCambiarElDestinoHistoricoDelPedido() throws Exception {
        long id = direccionGuardada(COMPRADOR);
        long pedido = pedidoConDestinoHistorico(COMPRADOR);
        String destinoAntes = destinoHistorico(pedido);

        retirar(loginAs(COMPRADOR), id).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT activa FROM direccion_guardada WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM direccion_guardada WHERE id = ?", Long.class, id)).isEqualTo(1);
        assertThat(destinoHistorico(pedido)).isEqualTo(destinoAntes);
    }

    @Test
    void noRevelaSiUnaDireccionEsAjenaInexistenteOYaRetirada() throws Exception {
        crearOtraCompradora();
        long ajena = direccionGuardada("otra.compradora@ccpip.test");
        long propia = direccionGuardada(COMPRADOR);
        Cookie comprador = loginAs(COMPRADOR);
        retirar(comprador, propia).andExpect(status().isNoContent());

        MvcResult ajenaResultado = retirar(comprador, ajena).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIRECCION_NO_ENCONTRADA")).andReturn();
        MvcResult inexistenteResultado = retirar(comprador, 999999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIRECCION_NO_ENCONTRADA")).andReturn();
        MvcResult repetidaResultado = retirar(comprador, propia).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DIRECCION_NO_ENCONTRADA")).andReturn();

        assertThat(ajenaResultado.getResponse().getContentAsString())
                .isEqualTo(inexistenteResultado.getResponse().getContentAsString())
                .isEqualTo(repetidaResultado.getResponse().getContentAsString());
    }

    /** CCPIP-395: datos invalidos. Un id mal formado no llega al servicio; uno fuera de rango no existe. Nada se retira. */
    @Test
    void rechazaUnIdInvalidoSinRetirarNinguna() throws Exception {
        direccionGuardada(COMPRADOR);
        Cookie comprador = loginAs(COMPRADOR);

        for (String malFormado : new String[] {"abc", "1.5", "99999999999999999999"}) {
            mockMvc.perform(delete(RUTA + "/" + malFormado).cookie(comprador))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
        }
        for (long fueraDeRango : new long[] {0, -1}) {
            retirar(comprador, fueraDeRango).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DIRECCION_NO_ENCONTRADA"));
        }
        assertThat(guardadas(COMPRADOR)).isEqualTo(1);
    }

    @Test
    void esSoloParaElComprador() throws Exception {
        mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(json(direccion())))
                .andExpect(status().isUnauthorized());
        guardar(loginAs("productor@ccpip.test"), direccion()).andExpect(status().isForbidden());
        guardar(loginAs("admin_acopio@ccpip.test"), direccion()).andExpect(status().isForbidden());
    }

    @Test
    void retirarDireccionExigeSesionDeComprador() throws Exception {
        long id = direccionGuardada(COMPRADOR);
        mockMvc.perform(delete(RUTA + "/{id}", id)).andExpect(status().isUnauthorized());
        retirar(loginAs("productor@ccpip.test"), id).andExpect(status().isForbidden());
        retirar(loginAs("admin_acopio@ccpip.test"), id).andExpect(status().isForbidden());
    }
}
