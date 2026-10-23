package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.inventario.DescuentoExistenciasService;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-71 (RF-53): el administrador del punto registra el recojo de un pedido listo por el comprador
 * o la persona autorizada, validando persona y celular, con responsable y fecha, y cierra la venta
 * sin descontar existencias otra vez. El comprador de los pedidos es "Consumidor de Prueba".
 */
@Transactional
class RecojoPedidoControllerTest extends IntegrationTest {

    static final String LIMA = "Centro Nacional Lima";
    static final String COMPRADOR = "Consumidor de Prueba";
    static final String CELULAR = "987654321";

    @Autowired
    DescuentoExistenciasService descuento;

    private Cookie admin;
    private long producto;

    @BeforeEach
    void preparar() throws Exception {
        admin = loginAs("admin_acopio@ccpip.test");
        producto = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusDays(30));
    }

    @Test
    void recogeElCompradorYQuedaConstanciaDeQuienFueYDeQuienLoEntrego() throws Exception {
        pedidoListo("MC-T0071", null, CELULAR);

        // Con descuidos de mostrador: mayusculas, espacios y el celular con +51 y guiones
        recojo(admin, "MC-T0071", "  consumidor DE   prueba ", "+51 987-654-321")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ENTREGADO"))
                .andExpect(jsonPath("$.recogidoPor").value(COMPRADOR))
                .andExpect(jsonPath("$.historial[-1:].estado").value("ENTREGADO"))
                .andExpect(jsonPath("$.historial[-1:].responsable").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.historial[-1:].registradoEn").exists());

        assertThat(jdbc.queryForObject("SELECT recogido_celular FROM pedido WHERE numero = 'MC-T0071'", String.class)).isEqualTo(CELULAR);
        assertThat(jdbc.queryForObject("""
                SELECT u.correo FROM pedido_estado_historial h JOIN usuario u ON u.id = h.usuario_id
                WHERE h.pedido_id = (SELECT id FROM pedido WHERE numero = 'MC-T0071') AND h.estado = 'ENTREGADO'""", String.class))
                .isEqualTo("admin_acopio@ccpip.test");
        // Aparece en "Entregado hoy"
        mockMvc.perform(get("/api/acopio/pedidos").cookie(admin))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0071')].estado").value("ENTREGADO"));
    }

    @Test
    void tambienPuedeRecogerLaPersonaAutorizada() throws Exception {
        pedidoListo("MC-T0072", "Rosa Ancón Pérez", CELULAR);

        recojo(admin, "MC-T0072", "  rosa ancon  perez ", CELULAR)
                .andExpect(status().isOk())
                // Queda el nombre tal como figura en el pedido, no como se tecleo
                .andExpect(jsonPath("$.recogidoPor").value("Rosa Ancón Pérez"));
    }

    @Test
    void sinLaPersonaCorrectaNoSeEntregaYElRechazoNoRevelaQuienEra() throws Exception {
        pedidoListo("MC-T0073", "Rosa Ancón Pérez", CELULAR);

        recojo(admin, "MC-T0073", "Juan Pérez", CELULAR)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERSONA_NO_COINCIDE"))
                .andExpect(content().string(not(containsString(COMPRADOR))))
                .andExpect(content().string(not(containsString("Rosa"))))
                .andExpect(content().string(not(containsString(CELULAR))));

        assertThat(estado("MC-T0073")).isEqualTo("LISTO_PARA_RECOJO");
        assertThat(jdbc.queryForObject("SELECT recogido_por FROM pedido WHERE numero = 'MC-T0073'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE estado = 'ENTREGADO'", Integer.class)).isZero();
    }

    @Test
    void conElCelularEquivocadoNoSeEntrega() throws Exception {
        pedidoListo("MC-T0074", null, CELULAR);

        recojo(admin, "MC-T0074", COMPRADOR, "999 111 222")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CELULAR_NO_COINCIDE"))
                .andExpect(content().string(not(containsString(CELULAR))));
        assertThat(estado("MC-T0074")).isEqualTo("LISTO_PARA_RECOJO");
    }

    @Test
    void sinCelularRegistradoSeAceptaUnoValidoYQuedaComoConstancia() throws Exception {
        pedidoListo("MC-T0075", null, null);

        recojo(admin, "MC-T0075", COMPRADOR, "+51 912 345 678").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT recogido_celular FROM pedido WHERE numero = 'MC-T0075'", String.class))
                .isEqualTo("912345678");
    }

    @Test
    void validaElFormatoDeLosDatosAntesDeTocarElPedido() throws Exception {
        pedidoListo("MC-T0076", null, CELULAR);

        recojo(admin, "MC-T0076", " ", CELULAR)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.persona").value("Indica quién recoge"));
        recojo(admin, "MC-T0076", COMPRADOR, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.celular").value("Indica el celular de contacto"));
        recojo(admin, "MC-T0076", COMPRADOR, "12345")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CELULAR_INVALIDO"));
        recojo(admin, "MC-T0076", "x".repeat(161), CELULAR).andExpect(status().isBadRequest());

        assertThat(estado("MC-T0076")).isEqualTo("LISTO_PARA_RECOJO");
    }

    @Test
    void soloSeEntregaUnPedidoListoYUnaSolaVez() throws Exception {
        long confirmado = pedidoConfirmado("MC-T0077", LIMA, producto, 1);
        recojo(admin, "MC-T0077", COMPRADOR, CELULAR)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTADO_INVALIDO"))
                .andExpect(jsonPath("$.error").value("Este pedido aún no está listo para recojo."));
        assertThat(estado("MC-T0077")).isEqualTo("CONFIRMADO");

        pedidoListo("MC-T0078", null, CELULAR);
        recojo(admin, "MC-T0078", COMPRADOR, CELULAR).andExpect(status().isOk());
        recojo(admin, "MC-T0078", COMPRADOR, CELULAR)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Este pedido ya fue entregado."));

        jdbc.update("UPDATE pedido SET estado = 'CANCELADO' WHERE id = ?", confirmado);
        recojo(admin, "MC-T0077", COMPRADOR, CELULAR)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Este pedido fue cancelado."));

        // La segunda entrega no deja otra fila en el historial
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pedido_estado_historial
                WHERE estado = 'ENTREGADO' AND pedido_id = (SELECT id FROM pedido WHERE numero = 'MC-T0078')""", Integer.class)).isEqualTo(1);
    }

    @Test
    void cerrarLaVentaNoVuelveADescontarExistencias() throws Exception {
        // El producto trae un lote de 5 und; el stock lo explica, como al registrar un lote
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) SELECT producto_id, ubicacion_id, cantidad_actual FROM lote WHERE producto_id = ?",
                producto);
        long id = pedidoConfirmado("MC-T0079", LIMA, producto, 2);
        jdbc.update("UPDATE pedido SET celular_contacto = ? WHERE id = ?", CELULAR, id);
        descuento.descontarPorPedido(id, jdbc.queryForObject("SELECT usuario_id FROM pedido WHERE id = ?", Long.class, id));
        mockMvc.perform(post("/api/acopio/pedidos/MC-T0079/preparacion").cookie(admin)).andExpect(status().isOk());
        mockMvc.perform(post("/api/acopio/pedidos/MC-T0079/listo").cookie(admin)).andExpect(status().isOk());
        var antes = inventario();
        assertThat(antes.get("lote")).isEqualTo(3);

        recojo(admin, "MC-T0079", COMPRADOR, CELULAR).andExpect(status().isOk());
        recojo(admin, "MC-T0079", COMPRADOR, CELULAR).andExpect(status().isConflict());

        // Ni el lote, ni el stock, ni los movimientos de la venta cambiaron: el descuento fue uno solo
        assertThat(inventario()).isEqualTo(antes);
        assertThat(antes.get("ventas")).isEqualTo(1);
    }

    @Test
    void soloElAdministradorDeEsePuntoRegistraElRecojo() throws Exception {
        pedidoListo("MC-T0080", null, CELULAR);
        String otroPunto = jdbc.queryForObject(
                "SELECT nombre FROM ubicacion WHERE tipo = 'PUNTO_ACOPIO' AND nombre <> ? ORDER BY nombre LIMIT 1", String.class, LIMA);
        long ajeno = pedidoConfirmado("MC-T0081", otroPunto, producto, 1);
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO' WHERE id = ?", ajeno);

        recojo(admin, "MC-T0081", COMPRADOR, CELULAR)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
        assertThat(estado("MC-T0081")).isEqualTo("LISTO_PARA_RECOJO");
        recojo(admin, "MC-99999", COMPRADOR, CELULAR).andExpect(status().isNotFound());

        recojo(null, "MC-T0080", COMPRADOR, CELULAR).andExpect(status().isUnauthorized());
        for (String correo : new String[] {"consumidor@ccpip.test", "productor@ccpip.test", "sub_admin@ccpip.test",
                "sudo@ccpip.test", "distribuidor@ccpip.test"}) {
            recojo(loginAs(correo), "MC-T0080", COMPRADOR, CELULAR).andExpect(status().isForbidden());
        }
        assertThat(estado("MC-T0080")).isEqualTo("LISTO_PARA_RECOJO");
    }

    // ---------- Datos y llamadas de prueba ----------

    /** Un pedido ya listo para recojo en el Centro Nacional Lima, con la persona autorizada y el celular dados. */
    private long pedidoListo(String numero, String personaAutorizada, String celular) {
        long id = pedidoConfirmado(numero, LIMA, producto, 2);
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO', persona_autorizada = ?, celular_contacto = ? WHERE id = ?",
                personaAutorizada, celular, id);
        jdbc.update("""
                INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id)
                SELECT ?, 'LISTO_PARA_RECOJO', id FROM usuario WHERE correo = 'admin_acopio@ccpip.test'""", id);
        return id;
    }

    private String estado(String numero) {
        return jdbc.queryForObject("SELECT estado FROM pedido WHERE numero = ?", String.class, numero);
    }

    /** Lo que el recojo no debe mover: lo que queda del lote, el stock y los movimientos de la venta. */
    private Map<String, Object> inventario() {
        Map<String, Object> m = new HashMap<>();
        m.put("lote", jdbc.queryForObject("SELECT cantidad_actual::int FROM lote WHERE producto_id = ?", Integer.class, producto));
        m.put("stock", jdbc.queryForObject("SELECT cantidad::int FROM stock WHERE producto_id = ?", Integer.class, producto));
        m.put("ventas", jdbc.queryForObject("SELECT count(*)::int FROM movimiento_inventario WHERE producto_id = ? AND tipo = 'SALIDA_VENTA'",
                Integer.class, producto));
        return m;
    }

    private ResultActions recojo(Cookie sesion, String numero, String persona, String celular) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("persona", persona);
        cuerpo.put("celular", celular);
        var peticion = post("/api/acopio/pedidos/{numero}/recojo", numero).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo));
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }
}
