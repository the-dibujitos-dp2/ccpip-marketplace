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
 * CCPIP-320 (RF-202): el administrador del punto de acopio registra la salida de un pedido a domicilio
 * preparado, que queda listo para despacho con responsable y fecha en su historial, sin volver a descontar
 * el inventario. admin_acopio@ccpip.test administra el Centro Nacional Lima.
 */
@Transactional
class PedidoListoParaDespachoTest extends IntegrationTest {

    private static final String LIMA = "Centro Nacional Lima";

    @Autowired
    DescuentoExistenciasService descuento;

    private Cookie admin;
    private long producto;
    private long pedido;

    @BeforeEach
    void pedidoADomicilioConfirmadoEnLima() throws Exception {
        admin = loginAs("admin_acopio@ccpip.test");
        producto = productoConLote("Cacao para despacho", "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        pedido = aDomicilio("DES-0001", LIMA);
    }

    /** Un pedido ENVIO confirmado, con su entrega a domicilio todavia disponible. */
    private long aDomicilio(String numero, String punto) throws Exception {
        long id = pedidoConfirmado(numero, punto, producto, 2);
        jdbc.update("UPDATE pedido SET modalidad = 'ENVIO' WHERE id = ?", id);
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular)
                VALUES (?, '150122', 'Av. Larco 100', 'María Quispe', '987654321')""", id);
        return id;
    }

    @Test
    void registraLaSalidaConResponsableYFechaEnSuHistorial() throws Exception {
        marcarPreparacion(admin, "DES-0001").andExpect(status().isOk());
        despachar(admin, "des-0001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("DES-0001"))
                .andExpect(jsonPath("$.estado").value("LISTO_PARA_DESPACHO"))
                .andExpect(jsonPath("$.modalidad").value("ENVIO"))
                .andExpect(jsonPath("$.historial[1].estado").value("EN_PREPARACION"))
                .andExpect(jsonPath("$.historial[2].estado").value("LISTO_PARA_DESPACHO"))
                .andExpect(jsonPath("$.historial[2].responsable").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.historial[2].registradoEn").exists())
                // Ni el comprador ni el destino exacto salen en la respuesta del punto (RNF-05)
                .andExpect(content().string(not(containsString("Consumidor de Prueba"))))
                .andExpect(content().string(not(containsString("Av. Larco 100"))));

        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido))
                .isEqualTo("LISTO_PARA_DESPACHO");
        assertThat(jdbc.queryForObject("""
                SELECT u.correo FROM pedido_estado_historial h JOIN usuario u ON u.id = h.usuario_id
                WHERE h.pedido_id = ? AND h.estado = 'LISTO_PARA_DESPACHO'""", String.class, pedido))
                .isEqualTo("admin_acopio@ccpip.test");
        // La entrega espera a que un distribuidor la asuma
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id = ?", String.class, pedido))
                .isEqualTo("DISPONIBLE");
    }

    @Test
    void laSalidaNoDescuentaElInventarioOtraVez() throws Exception {
        long comprador = jdbc.queryForObject("SELECT usuario_id FROM pedido WHERE id = ?", Long.class, pedido);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad)
                SELECT ?, id, 5 FROM ubicacion WHERE nombre = ?""", producto, LIMA);
        descuento.descontarPorPedido(pedido, comprador);
        Integer movimientos = jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class);

        marcarPreparacion(admin, "DES-0001").andExpect(status().isOk());
        despachar(admin, "DES-0001").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class)).isEqualTo(movimientos);
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, producto))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT s.cantidad FROM stock s JOIN ubicacion u ON u.id = s.ubicacion_id
                WHERE s.producto_id = ? AND u.nombre = ?""", Integer.class, producto, LIMA)).isEqualTo(3);
    }

    @Test
    void soloSaleUnPedidoEnPreparacionYNoSePuedeRepetir() throws Exception {
        despachar(admin, "DES-0001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTADO_INVALIDO"))
                .andExpect(jsonPath("$.error").value("Este pedido aún no está listo para despacho."));

        marcarPreparacion(admin, "DES-0001").andExpect(status().isOk());
        despachar(admin, "DES-0001").andExpect(status().isOk());
        despachar(admin, "DES-0001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTADO_INVALIDO"))
                .andExpect(jsonPath("$.error").value("Este pedido ya está listo para despacho."));
        // Ya en salida, tampoco se vuelve a preparar
        marcarPreparacion(admin, "DES-0001").andExpect(status().isConflict());

        long cancelado = aDomicilio("DES-0002", LIMA);
        jdbc.update("UPDATE pedido SET estado = 'CANCELADO' WHERE id = ?", cancelado);
        despachar(admin, "DES-0002")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Este pedido fue cancelado."));

        // Los rechazos no dejan rastro en el historial
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id = ?",
                Integer.class, pedido)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido_estado_historial WHERE pedido_id = ? AND estado = 'LISTO_PARA_DESPACHO'",
                Integer.class, cancelado)).isZero();
    }

    @Test
    void unPedidoParaRecojoNoSeDespachaYUnoADomicilioNoSeMarcaListoParaRecojo() throws Exception {
        long recojo = pedidoConfirmado("DES-0003", LIMA, producto, 1);
        marcarPreparacion(admin, "DES-0003").andExpect(status().isOk());
        despachar(admin, "DES-0003")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_PARA_RECOJO"));
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, recojo)).isEqualTo("EN_PREPARACION");

        marcarPreparacion(admin, "DES-0001").andExpect(status().isOk());
        mockMvc.perform(post("/api/acopio/pedidos/DES-0001/listo").cookie(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_A_DOMICILIO"));
    }

    @Test
    void elTableroDelPuntoLoMuestraJuntoALosListos() throws Exception {
        long listoRecojo = pedidoConfirmado("DES-0004", LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO' WHERE id = ?", listoRecojo);
        marcarPreparacion(admin, "DES-0001").andExpect(status().isOk());
        despachar(admin, "DES-0001").andExpect(status().isOk());

        mockMvc.perform(get("/api/acopio/pedidos").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.numero == 'DES-0001')].estado").value("LISTO_PARA_DESPACHO"))
                .andExpect(jsonPath("$.items[?(@.numero == 'DES-0001')].modalidad").value("ENVIO"))
                .andExpect(jsonPath("$.items[?(@.numero == 'DES-0004')].estado").value("LISTO_PARA_RECOJO"));
    }

    @Test
    void unPedidoDeOtroPuntoNoExisteParaEsteAdministrador() throws Exception {
        String otroPunto = jdbc.queryForObject(
                "SELECT nombre FROM ubicacion WHERE tipo = 'PUNTO_ACOPIO' AND nombre <> ? ORDER BY nombre LIMIT 1",
                String.class, LIMA);
        long ajeno = aDomicilio("DES-0005", otroPunto);
        jdbc.update("UPDATE pedido SET estado = 'EN_PREPARACION' WHERE id = ?", ajeno);

        despachar(admin, "DES-0005")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, ajeno)).isEqualTo("EN_PREPARACION");
        despachar(admin, "DES-99999").andExpect(status().isNotFound());
        despachar(admin, "DES 1;--").andExpect(status().isNotFound());
    }

    @Test
    void soloElAdministradorDePuntoRegistraLaSalida() throws Exception {
        jdbc.update("UPDATE pedido SET estado = 'EN_PREPARACION' WHERE id = ?", pedido);
        despachar(null, "DES-0001").andExpect(status().isUnauthorized());
        for (String correo : new String[] {"consumidor@ccpip.test", "productor@ccpip.test", "sub_admin@ccpip.test",
                "sudo@ccpip.test", "distribuidor@ccpip.test"}) {
            despachar(loginAs(correo), "DES-0001").andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido)).isEqualTo("EN_PREPARACION");
    }

    @Test
    void laBaseAdmiteElEstadoNuevoYNoUnoInventado() {
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_DESPACHO' WHERE id = ?", pedido);
        assertRechazaLaBase(() -> jdbc.update("UPDATE pedido SET estado = 'DESPACHADO' WHERE id = ?", pedido),
                "pedido_estado_valido");
    }

    private ResultActions despachar(Cookie sesion, String numero) throws Exception {
        var peticion = post("/api/acopio/pedidos/{numero}/despacho", numero);
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private ResultActions marcarPreparacion(Cookie sesion, String numero) throws Exception {
        var peticion = post("/api/acopio/pedidos/{numero}/preparacion", numero);
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }
}
