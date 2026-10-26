package pe.ccpip.distribucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-109 (RF-56): el distribuidor registra que retiro del punto de acopio la entrega que asumio. Queda RETIRADA con
 * su fecha, la ven el punto y el comprador, y el stock no se vuelve a descontar.
 */
@Transactional
class DistribucionRetirarTest extends IntegrationTest {

    private static final String LIMA = "Centro Nacional Lima";

    private Cookie distribuidor;
    private long producto;
    private long pedido;
    private int secuencia;

    @BeforeEach
    void pedidoAsumido() throws Exception {
        distribuidor = loginAs("distribuidor@ccpip.test");
        pedido = aDomicilio("RET-0001", "LISTO_PARA_DESPACHO");
        mockMvc.perform(post("/api/distribuidor/pedidos/RET-0001/asumir").cookie(distribuidor)).andExpect(status().isOk());
    }

    /** Un pedido a domicilio de Lima en {@code estado}, con su entrega disponible. */
    private long aDomicilio(String numero, String estado) {
        producto = productoConLote("Producto " + numero + secuencia++, "PUBLICADO", "PUNTO_ACOPIO",
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

    private ResultActions retirar(Cookie sesion, String numero) throws Exception {
        var peticion = post("/api/distribuidor/pedidos/{numero}/retirar", numero);
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private String estadoEntrega(long id) {
        return jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id = ?", String.class, id);
    }

    private BigDecimal stock() {
        return jdbc.queryForObject("SELECT coalesce(sum(cantidad_actual), 0) FROM lote WHERE producto_id = ?",
                BigDecimal.class, producto);
    }

    @Test
    void laEntregaQuedaRetiradaConSuFechaSinTocarElPedidoNiElInventario() throws Exception {
        Integer movimientos = jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class);
        BigDecimal stock = stock();

        retirar(distribuidor, "ret-0001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numero").value("RET-0001"))
                .andExpect(jsonPath("$.estado").value("RETIRADA"))
                .andExpect(jsonPath("$.origen.nombre").value(LIMA))
                .andExpect(jsonPath("$.destino.distrito").value("Miraflores"))
                .andExpect(jsonPath("$.asignadaEn").exists())
                .andExpect(jsonPath("$.retiradaEn").exists());

        assertThat(estadoEntrega(pedido)).isEqualTo("RETIRADA");
        assertThat(jdbc.queryForObject("SELECT retirada_en >= asignada_en FROM entrega WHERE pedido_id = ?",
                Boolean.class, pedido)).isTrue();
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, pedido))
                .isEqualTo("LISTO_PARA_DESPACHO");
        // Sin segunda disminucion de stock: el descuento ocurrio al confirmar la compra
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario", Integer.class)).isEqualTo(movimientos);
        assertThat(stock()).isEqualByComparingTo(stock);
    }

    @Test
    void alAsumirlaTodaviaNoEstaRetirada() throws Exception {
        long otro = aDomicilio("RET-0002", "LISTO_PARA_DESPACHO");
        mockMvc.perform(post("/api/distribuidor/pedidos/RET-0002/asumir").cookie(distribuidor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ASIGNADA"))
                .andExpect(jsonPath("$.retiradaEn").doesNotExist());
        assertThat(estadoEntrega(otro)).isEqualTo("ASIGNADA");
    }

    @Test
    void repetirElRetiroNoCambiaNada() throws Exception {
        String primero = retirar(distribuidor, "RET-0001").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        retirar(distribuidor, "RET-0001")
                .andExpect(status().isOk())
                .andExpect(content().json(primero));
        assertThat(estadoEntrega(pedido)).isEqualTo("RETIRADA");
    }

    @Test
    void otroDistribuidorNoRetiraUnaEntregaQueNoAsumio() throws Exception {
        retirar(otroDistribuidor(), "RET-0001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ASIGNADO"));
        assertThat(estadoEntrega(pedido)).isEqualTo("ASIGNADA");
    }

    @Test
    void noSeRetiraUnaEntregaQueNadieAsumio() throws Exception {
        long libre = aDomicilio("RET-0003", "LISTO_PARA_DESPACHO");
        retirar(distribuidor, "RET-0003")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ASIGNADO"));
        assertThat(estadoEntrega(libre)).isEqualTo("DISPONIBLE");
    }

    @Test
    void soloSeRetiraMientrasSigueAsignadaYListaParaDespacho() throws Exception {
        // Un pedido cancelado despues de asumirlo ya no sale del punto
        jdbc.update("UPDATE pedido SET estado = 'CANCELADO' WHERE id = ?", pedido);
        retirar(distribuidor, "RET-0001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_RETIRABLE"));
        assertThat(estadoEntrega(pedido)).isEqualTo("ASIGNADA");

        // Una entrega que ya va en camino no vuelve a quedar retirada
        long enCamino = aDomicilio("RET-0004", "LISTO_PARA_DESPACHO");
        jdbc.update("""
                UPDATE entrega SET estado = 'EN_CAMINO', asignada_en = now(), retirada_en = now(),
                       distribuidor_id = (SELECT id FROM usuario WHERE correo = 'distribuidor@ccpip.test')
                WHERE pedido_id = ?""", enCamino);
        retirar(distribuidor, "RET-0004")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_RETIRABLE"));
        assertThat(estadoEntrega(enCamino)).isEqualTo("EN_CAMINO");
    }

    @Test
    void unPedidoParaRecojoOInexistenteNoExisteParaElDistribuidor() throws Exception {
        long recojo = pedidoConfirmado("RET-0005", LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET estado = 'LISTO_PARA_RECOJO' WHERE id = ?", recojo);

        retirar(distribuidor, "RET-0005")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
        retirar(distribuidor, "RET-99999").andExpect(status().isNotFound());
        retirar(distribuidor, "RET 1;--").andExpect(status().isNotFound());
    }

    @Test
    void elPuntoVeQuienLoAsumioYCuandoLoRetiro() throws Exception {
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        mockMvc.perform(get("/api/acopio/pedidos").cookie(admin))
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0001')].reparto.estado").value("ASIGNADA"))
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0001')].reparto.distribuidor").value("Distribuidor de Prueba"))
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0001')].reparto.asignadaEn").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0001')].reparto.retiradaEn").isEmpty());

        retirar(distribuidor, "RET-0001").andExpect(status().isOk());

        mockMvc.perform(get("/api/acopio/pedidos").cookie(admin))
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0001')].estado").value("LISTO_PARA_DESPACHO"))
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0001')].reparto.estado").value("RETIRADA"))
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0001')].reparto.retiradaEn").isNotEmpty());
    }

    @Test
    void unPedidoParaRecojoNoTraeRepartoEnElTablero() throws Exception {
        pedidoConfirmado("RET-0006", LIMA, producto, 1);
        mockMvc.perform(get("/api/acopio/pedidos").cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0006')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.numero == 'RET-0006')].reparto").isEmpty());
    }

    @Test
    void elCompradorVeCuandoSeAsumioYSeRetiroSuPedidoSinVerAlDistribuidor() throws Exception {
        Cookie comprador = loginAs("consumidor@ccpip.test");
        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(comprador))
                .andExpect(jsonPath("$.entrega.estado").value("ASIGNADA"))
                .andExpect(jsonPath("$.entrega.asignadaEn").isNotEmpty())
                .andExpect(jsonPath("$.entrega.retiradaEn").isEmpty());

        retirar(distribuidor, "RET-0001").andExpect(status().isOk());

        String respuesta = mockMvc.perform(get("/api/pedidos/" + pedido).cookie(comprador))
                .andExpect(jsonPath("$.entrega.estado").value("RETIRADA"))
                .andExpect(jsonPath("$.entrega.retiradaEn").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(respuesta).doesNotContain("Distribuidor de Prueba", "distribuidor@ccpip.test");
    }

    @Test
    void soloElDistribuidorRegistraElRetiro() throws Exception {
        retirar(null, "RET-0001").andExpect(status().isUnauthorized());
        for (String correo : new String[] {"consumidor@ccpip.test", "productor@ccpip.test", "admin_acopio@ccpip.test",
                "sub_admin@ccpip.test", "sudo@ccpip.test"}) {
            retirar(loginAs(correo), "RET-0001").andExpect(status().isForbidden());
        }
        assertThat(estadoEntrega(pedido)).isEqualTo("ASIGNADA");
    }
}
