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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.inventario.DescuentoExistenciasService;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-159 (RF-128) y Figma A-05: el administrador del punto de acopio ve los pedidos de su punto y
 * registra preparación y luego disponibilidad para recojo, con responsable y fecha en el historial.
 * admin_acopio@ccpip.test administra el Centro Nacional Lima.
 */
@Transactional
class PedidosPuntoControllerTest extends IntegrationTest {

    static final String LIMA = "Centro Nacional Lima";
    static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));
    static final LocalDate EN_UN_MES = HOY.plusDays(30);

    private Cookie admin;
    private long producto;
    private long pedido;

    @BeforeEach
    void preparar() throws Exception {
        admin = loginAs("admin_acopio@ccpip.test");
        producto = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        pedido = pedidoConfirmado("MC-T0159", LIMA, producto, 2);
    }

    @Test
    void marcaElPedidoListoConResponsableYFechaEnSuHistorial() throws Exception {
        marcarPreparacion(admin, "MC-T0159").andExpect(status().isOk());
        marcarListo(admin, "MC-T0159")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("MC-T0159"))
                .andExpect(jsonPath("$.estado").value("LISTO_PARA_RECOJO"))
                .andExpect(jsonPath("$.lineas[0].producto").value("Sacha inchi tostado"))
                .andExpect(jsonPath("$.lineas[0].comunidad").value("Asoc. Nii Joi"))
                .andExpect(jsonPath("$.lineas[0].cantidad").value(2))
                .andExpect(jsonPath("$.historial[0].estado").value("CONFIRMADO"))
                // La confirmacion la hizo el comprador: no se nombra al punto (RNF-05)
                .andExpect(jsonPath("$.historial[0].responsable").doesNotExist())
                .andExpect(jsonPath("$.historial[1].estado").value("EN_PREPARACION"))
                .andExpect(jsonPath("$.historial[1].responsable").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.historial[1].registradoEn").exists())
                .andExpect(jsonPath("$.historial[2].estado").value("LISTO_PARA_RECOJO"))
                .andExpect(jsonPath("$.historial[2].responsable").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.historial[2].registradoEn").exists())
                .andExpect(content().string(not(containsString("Consumidor de Prueba"))));

        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido))
                .isEqualTo("LISTO_PARA_RECOJO");
        assertThat(jdbc.queryForObject("""
                SELECT u.correo FROM pedido_estado_historial h JOIN usuario u ON u.id = h.usuario_id
                WHERE h.pedido_id = ? AND h.estado = 'LISTO_PARA_RECOJO'""", String.class, pedido))
                .isEqualTo("admin_acopio@ccpip.test");
    }

    @Test
    void marcarListoNoTocaElInventario() throws Exception {
        Integer antes = jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class);
        marcarPreparacion(admin, "MC-T0159").andExpect(status().isOk());
        marcarListo(admin, "mc-t0159").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, producto))
                .isEqualTo(5);
    }

    @Test
    void registraPreparacionConResponsableYFechaSinTocarInventario() throws Exception {
        Integer movimientosAntes = jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class);

        marcarPreparacion(admin, "MC-T0159")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_PREPARACION"))
                .andExpect(jsonPath("$.historial[1].estado").value("EN_PREPARACION"))
                .andExpect(jsonPath("$.historial[1].responsable").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.historial[1].registradoEn").exists())
                .andExpect(content().string(not(containsString("Consumidor de Prueba"))));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class)).isEqualTo(movimientosAntes);
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido)).isEqualTo("EN_PREPARACION");
        assertThat(jdbc.queryForObject("""
                SELECT u.correo FROM pedido_estado_historial h JOIN usuario u ON u.id = h.usuario_id
                WHERE h.pedido_id = ? AND h.estado = 'EN_PREPARACION'""", String.class, pedido))
                .isEqualTo("admin_acopio@ccpip.test");
    }

    @Test
    void soloTransicionaConfirmadoAPreparacionYDespuesAListoYRecojoNoDescuentaOtraVez() throws Exception {
        long comprador = jdbc.queryForObject("SELECT usuario_id FROM pedido WHERE id = ?", Long.class, pedido);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad)
                SELECT ?, id, 5 FROM ubicacion WHERE nombre = ?""", producto, LIMA);
        descuento.descontarPorPedido(pedido, comprador);
        marcarListo(admin, "MC-T0159").andExpect(status().isConflict());
        marcarPreparacion(admin, "MC-T0159").andExpect(status().isOk());
        marcarPreparacion(admin, "MC-T0159")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTADO_INVALIDO"));
        Integer movimientosAntesDeRecojo = jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class);
        marcarListo(admin, "MC-T0159")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("LISTO_PARA_RECOJO"))
                .andExpect(jsonPath("$.historial[1].estado").value("EN_PREPARACION"))
                .andExpect(jsonPath("$.historial[2].estado").value("LISTO_PARA_RECOJO"));
        registrarRecojo(admin, "MC-T0159", "Consumidor de Prueba", "987654321").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class)).isEqualTo(movimientosAntesDeRecojo);
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, producto)).isEqualTo(3);
    }

    @Test
    void soloSeMarcaUnPedidoEnPreparacionYNoSePuedeRepetir() throws Exception {
        marcarPreparacion(admin, "MC-T0159").andExpect(status().isOk());
        marcarListo(admin, "MC-T0159").andExpect(status().isOk());
        marcarListo(admin, "MC-T0159")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTADO_INVALIDO"))
                .andExpect(jsonPath("$.error").value("Este pedido ya está listo para recojo."));

        long entregado = pedidoConfirmado("MC-T0160", LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET estado = 'ENTREGADO' WHERE id = ?", entregado);
        marcarListo(admin, "MC-T0160")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Este pedido ya fue entregado."));

        // El rechazo no deja rastro en el historial
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id = ?",
                Integer.class, pedido)).isEqualTo(3);
    }

    // --- CCPIP-74: lotes asignados a cada linea ---

    @Autowired
    DescuentoExistenciasService descuento;

    @Test
    void cadaLineaMuestraLosLotesAsignadosElQueVenceAntesPrimero() throws Exception {
        // El producto trae un lote de 5 und que vence en un mes; este otro, de 3, vence antes
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_vencimiento)
                SELECT ?, id, 'L-PRONTO', 3, 3, ? FROM ubicacion WHERE nombre = ?""", producto, HOY.plusDays(10), LIMA);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad)
                SELECT ?, id, 8 FROM ubicacion WHERE nombre = ?""", producto, LIMA);
        long grande = pedidoConfirmado("MC-T0074", LIMA, producto, 6);
        descuento.descontarPorPedido(grande, jdbc.queryForObject(
                "SELECT usuario_id FROM pedido WHERE id = ?", Long.class, grande));

        listar(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0074')].lineas[0].lotes[0].codigo").value("L-PRONTO"))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0074')].lineas[0].lotes[0].cantidad").value(3.0))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0074')].lineas[0].lotes[0].fechaVencimiento")
                        .value(HOY.plusDays(10).toString()))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0074')].lineas[0].lotes[1].codigo").value("L-T" + producto))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0074')].lineas[0].lotes[1].cantidad").value(3.0))
                .andExpect(jsonPath("$.items[?(@.numero == 'MC-T0074')].lineas[0].lotes[2]").isEmpty());

        // Los lotes tambien llegan en la respuesta de marcar el pedido como listo
        marcarPreparacion(admin, "MC-T0074").andExpect(status().isOk());
        marcarListo(admin, "MC-T0074")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineas[0].lotes.length()").value(2));
    }

    @Test
    void unPedidoQueAunNoDescuentaNoTieneLotesAsignados() throws Exception {
        listar(admin).andExpect(jsonPath("$.items[?(@.numero == 'MC-T0159')].lineas[0].lotes.length()").value(0));
    }

    @Test
    void unPedidoDeOtroPuntoNoExisteParaEsteAdministrador() throws Exception {
        String otroPunto = jdbc.queryForObject(
                "SELECT nombre FROM ubicacion WHERE tipo = 'PUNTO_ACOPIO' AND nombre <> ? ORDER BY nombre LIMIT 1",
                String.class, LIMA);
        long ajeno = pedidoConfirmado("MC-T0161", otroPunto, producto, 1);

        marcarListo(admin, "MC-T0161")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, ajeno)).isEqualTo("CONFIRMADO");
        marcarPreparacion(admin, "MC-T0161").andExpect(status().isNotFound());

        marcarListo(admin, "MC-99999").andExpect(status().isNotFound());
        marcarListo(admin, "MC 1;--").andExpect(status().isNotFound());
        listar(admin).andExpect(jsonPath("$.items[?(@.numero == 'MC-T0161')]").isEmpty());
    }

    @Test
    void listaLosPedidosDelPuntoPorColumnaDeA05() throws Exception {
        long preparando = pedidoConfirmado("MC-T0165", LIMA, producto, 1);
        long listo = pedidoConfirmado("MC-T0162", LIMA, producto, 1);
        long entregadoHoy = pedidoConfirmado("MC-T0163", LIMA, producto, 1);
        long entregadoAntes = pedidoConfirmado("MC-T0164", LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET estado = 'EN_PREPARACION' WHERE id = ?", preparando);
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO' WHERE id = ?", listo);
        jdbc.update("UPDATE pedido SET estado = 'ENTREGADO' WHERE id = ?", entregadoHoy);
        jdbc.update("UPDATE pedido SET estado = 'ENTREGADO', actualizado_en = now() - interval '2 days' WHERE id = ?",
                entregadoAntes);

        listar(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ubicacion").value(LIMA))
                // Confirmados, en preparacion, listos, luego entregados hoy; lo anterior ya no se muestra
                .andExpect(jsonPath("$.items[*].numero").value(org.hamcrest.Matchers.contains("MC-T0159", "MC-T0165", "MC-T0162", "MC-T0163")))
                .andExpect(jsonPath("$.items[0].estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.items[0].total").value(40))
                .andExpect(content().string(not(containsString("consumidor@ccpip.test"))));
    }

    @Test
    void unAdministradorSinPuntoAsignadoLoSabe() throws Exception {
        jdbc.update("DELETE FROM usuario_ubicacion WHERE usuario_id = (SELECT id FROM usuario WHERE correo = 'admin_acopio@ccpip.test')");
        listar(admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACOPIO_SIN_ASIGNAR"));
        marcarListo(admin, "MC-T0159").andExpect(status().isNotFound());
    }

    @Test
    void soloElAdministradorDePuntoOperaLosPedidos() throws Exception {
        listar(null).andExpect(status().isUnauthorized());
        marcarListo(null, "MC-T0159").andExpect(status().isUnauthorized());
        marcarPreparacion(null, "MC-T0159").andExpect(status().isUnauthorized());
        for (String correo : new String[] {"consumidor@ccpip.test", "productor@ccpip.test", "sub_admin@ccpip.test",
                "sudo@ccpip.test", "distribuidor@ccpip.test"}) {
            Cookie otro = loginAs(correo);
            listar(otro).andExpect(status().isForbidden());
            marcarListo(otro, "MC-T0159").andExpect(status().isForbidden());
            marcarPreparacion(otro, "MC-T0159").andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido)).isEqualTo("CONFIRMADO");
    }

    private ResultActions listar(Cookie sesion) throws Exception {
        var peticion = get("/api/acopio/pedidos");
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private ResultActions marcarListo(Cookie sesion, String numero) throws Exception {
        var peticion = post("/api/acopio/pedidos/{numero}/listo", numero);
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private ResultActions marcarPreparacion(Cookie sesion, String numero) throws Exception {
        var peticion = post("/api/acopio/pedidos/{numero}/preparacion", numero);
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private ResultActions registrarRecojo(Cookie sesion, String numero, String persona, String celular) throws Exception {
        var peticion = post("/api/acopio/pedidos/{numero}/recojo", numero)
                .contentType("application/json")
                .content("{\"persona\":\"" + persona + "\",\"celular\":\"" + celular + "\"}");
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }
}
