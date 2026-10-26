package pe.ccpip.distribucion;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-108 (RF-55): el distribuidor asume la entrega de un pedido disponible. La entrega pasa a ser suya, deja de
 * estar en la cola y nadie mas puede asumirla; el pedido no cambia de estado ni se toca el inventario.
 */
@Transactional
class DistribucionAsumirTest extends IntegrationTest {

    private static final String LIMA = "Centro Nacional Lima";

    private Cookie distribuidor;
    private long pedido;
    private int secuencia;

    @BeforeEach
    void pedidoListoParaDespacho() throws Exception {
        distribuidor = loginAs("distribuidor@ccpip.test");
        pedido = aDomicilio("ASU-0001", "LISTO_PARA_DESPACHO");
    }

    /** Un pedido a domicilio de Lima en {@code estado}, con su entrega disponible y un destino reconocible. */
    private long aDomicilio(String numero, String estado) {
        long producto = productoConLote("Producto " + numero + secuencia++, "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        long id = pedidoConfirmado(numero, LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET modalidad = 'ENVIO', estado = ? WHERE id = ?", estado, id);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, ?)", id, estado);
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular)
                VALUES (?, '150122', 'Calle Secreta 999', 'Persona Reservada', '999888777')""", id);
        return id;
    }

    /** Un segundo distribuidor, con la misma clave de demostracion que el del seed. */
    private Cookie otroDistribuidor() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'distribuidor2@ccpip.test', contrasena_hash, 'Otro Distribuidor', rol_id, true
                FROM usuario WHERE correo = 'distribuidor@ccpip.test'""");
        return loginAs("distribuidor2@ccpip.test");
    }

    private ResultActions asumir(Cookie sesion, String numero) throws Exception {
        var peticion = post("/api/distribuidor/pedidos/{numero}/asumir", numero);
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private String estadoEntrega() {
        return jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id = ?", String.class, pedido);
    }

    @Test
    void laEntregaPasaASerSuyaConLaFechaYSinTocarElPedidoNiElInventario() throws Exception {
        Integer movimientos = jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class);

        asumir(distribuidor, "asu-0001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("ASU-0001"))
                .andExpect(jsonPath("$.origen.nombre").value(LIMA))
                .andExpect(jsonPath("$.destino.ubigeo").value("150122"))
                .andExpect(jsonPath("$.destino.distrito").value("Miraflores"))
                .andExpect(jsonPath("$.asignadaEn").exists())
                // La direccion exacta y el contacto llegan con CCPIP-110; el comprador nunca (RNF-05)
                .andExpect(content().string(not(containsString("Calle Secreta"))))
                .andExpect(content().string(not(containsString("999888777"))))
                .andExpect(content().string(not(containsString("Consumidor de Prueba"))));

        assertThat(estadoEntrega()).isEqualTo("ASIGNADA");
        assertThat(jdbc.queryForObject("""
                SELECT u.correo FROM entrega e JOIN usuario u ON u.id = e.distribuidor_id WHERE e.pedido_id = ?""",
                String.class, pedido)).isEqualTo("distribuidor@ccpip.test");
        assertThat(jdbc.queryForObject("SELECT asignada_en IS NOT NULL FROM entrega WHERE pedido_id = ?", Boolean.class, pedido))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido))
                .isEqualTo("LISTO_PARA_DESPACHO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class)).isEqualTo(movimientos);
    }

    @Test
    void dejaDeEstarEnLaColaDeDisponibles() throws Exception {
        mockMvc.perform(get("/api/distribuidor/pedidos/disponibles").param("limite", "50").cookie(distribuidor))
                .andExpect(jsonPath("$.items[?(@.numero == 'ASU-0001')]").isNotEmpty());
        asumir(distribuidor, "ASU-0001").andExpect(status().isOk());
        mockMvc.perform(get("/api/distribuidor/pedidos/disponibles").param("limite", "50").cookie(distribuidor))
                .andExpect(jsonPath("$.items[?(@.numero == 'ASU-0001')]").isEmpty());
    }

    @Test
    void siQuienLaAsumioLaVuelveAPedirRecibeLaMismaAsignacion() throws Exception {
        String primera = asumir(distribuidor, "ASU-0001").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        asumir(distribuidor, "ASU-0001")
                .andExpect(status().isOk())
                .andExpect(content().json(primera));
        assertThat(estadoEntrega()).isEqualTo("ASIGNADA");
    }

    @Test
    void otroDistribuidorNoPuedeAsumirUnaEntregaYaAsumida() throws Exception {
        asumir(distribuidor, "ASU-0001").andExpect(status().isOk());

        asumir(otroDistribuidor(), "ASU-0001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_YA_ASUMIDO"));
        assertThat(jdbc.queryForObject("""
                SELECT u.correo FROM entrega e JOIN usuario u ON u.id = e.distribuidor_id WHERE e.pedido_id = ?""",
                String.class, pedido)).isEqualTo("distribuidor@ccpip.test");
    }

    @Test
    void soloSeAsumeUnPedidoElegible() throws Exception {
        aDomicilio("ASU-0002", "EN_PREPARACION");
        asumir(distribuidor, "ASU-0002")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_DISPONIBLE"));

        aDomicilio("ASU-0003", "CANCELADO");
        asumir(distribuidor, "ASU-0003").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_DISPONIBLE"));

        // Un punto de acopio inactivo no despacha: su pedido no esta en la cola ni se asume
        jdbc.update("UPDATE ubicacion SET activo = false WHERE nombre = ?", LIMA);
        asumir(distribuidor, "ASU-0001").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_DISPONIBLE"));
        assertThat(estadoEntrega()).isEqualTo("DISPONIBLE");
    }

    @Test
    void unPedidoParaRecojoOInexistenteNoExisteParaElDistribuidor() throws Exception {
        long producto = productoConLote("Producto de recojo", "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        long recojo = pedidoConfirmado("ASU-0004", LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO' WHERE id = ?", recojo);

        asumir(distribuidor, "ASU-0004")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
        asumir(distribuidor, "ASU-99999").andExpect(status().isNotFound());
        asumir(distribuidor, "ASU 1;--").andExpect(status().isNotFound());
    }

    @Test
    void elPedidoQueElPuntoDespachaSePuedeAsumir() throws Exception {
        // El flujo real: el punto lo prepara y registra su salida (CCPIP-68 y CCPIP-320), y el distribuidor lo asume
        long nuevo = aDomicilio("ASU-0005", "CONFIRMADO");
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        mockMvc.perform(post("/api/acopio/pedidos/ASU-0005/preparacion").cookie(admin)).andExpect(status().isOk());
        asumir(distribuidor, "ASU-0005").andExpect(status().isConflict());
        mockMvc.perform(post("/api/acopio/pedidos/ASU-0005/despacho").cookie(admin)).andExpect(status().isOk());

        asumir(distribuidor, "ASU-0005").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id = ?", String.class, nuevo))
                .isEqualTo("ASIGNADA");
    }

    @Test
    void soloElDistribuidorAsumeEntregas() throws Exception {
        asumir(null, "ASU-0001").andExpect(status().isUnauthorized());
        for (String correo : new String[] {"consumidor@ccpip.test", "productor@ccpip.test", "admin_acopio@ccpip.test",
                "sub_admin@ccpip.test", "sudo@ccpip.test"}) {
            asumir(loginAs(correo), "ASU-0001").andExpect(status().isForbidden());
        }
        assertThat(estadoEntrega()).isEqualTo("DISPONIBLE");
    }
}
