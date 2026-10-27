package pe.ccpip.inventario;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-158 (RF-127) y Figma A-03: el administrador del punto de acopio consulta el movimiento de
 * descuento que genero cada compra confirmada, solo de su punto y sin datos del comprador.
 * admin_acopio@ccpip.test administra el Centro Nacional Lima, donde R__02 dejo una ENTRADA por
 * producto sembrado.
 */
@Transactional
class MovimientosControllerTest extends IntegrationTest {

    static final LocalDate EN_UN_MES = LocalDate.now(ZoneId.of("America/Lima")).plusDays(30);

    @Autowired
    DescuentoExistenciasService descuento;

    private long producto;

    @BeforeEach
    void venderUnaVez() {
        producto = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad)
                SELECT producto_id, ubicacion_id, cantidad_actual FROM lote WHERE producto_id = ?""", producto);
        Long pedido = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total)
                SELECT 'MC-T0148', u.id, 'RECOJO', l.id, 40
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
                SELECT ?, id, productor_id, 2, precio, precio * 2 FROM producto WHERE id = ?""", pedido, producto);
        descuento.descontarPorPedido(pedido, jdbc.queryForObject(
                "SELECT id FROM usuario WHERE correo = 'consumidor@ccpip.test'", Long.class));
    }

    @Test
    void elAdministradorDelPuntoVeLaSalidaDeCadaVentaConSuPedidoYLote() throws Exception {
        listar(loginAs("admin_acopio@ccpip.test"), "tipo", "SALIDAS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ubicacion").value("Centro Nacional Lima"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].tipo").value("SALIDA_VENTA"))
                .andExpect(jsonPath("$.items[0].producto").value("Sacha inchi tostado"))
                .andExpect(jsonPath("$.items[0].productoId").value(producto))
                .andExpect(jsonPath("$.items[0].lote").value("L-T" + producto))
                .andExpect(jsonPath("$.items[0].cantidad").value(2))
                .andExpect(jsonPath("$.items[0].pedido").value("MC-T0148"))
                .andExpect(jsonPath("$.items[0].registradoEn").exists())
                // RNF-05 y Figma SA-08: la venta no expone quien compro
                .andExpect(jsonPath("$.items[0].registradoPor").doesNotExist())
                .andExpect(content().string(not(containsString("Consumidor de Prueba"))))
                .andExpect(content().string(not(containsString("consumidor@ccpip.test"))));
    }

    @Test
    void filtraPorPestanaYPorNumeroDePedido() throws Exception {
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        listar(admin, "tipo", "ENTRADAS")
                .andExpect(jsonPath("$.items[*].tipo", everyItem(is("ENTRADA"))))
                .andExpect(jsonPath("$.items[*].observacion", hasItem("Carga inicial de demostracion")));
        listar(admin, "tipo", "AJUSTES").andExpect(jsonPath("$.total").value(0));
        listar(admin)
                .andExpect(jsonPath("$.tamano").value(20))
                .andExpect(jsonPath("$.items[0].tipo").value("SALIDA_VENTA"));

        // Sin distinguir mayusculas ni espacios al copiar el numero
        listar(admin, "pedido", " mc-t0148 ")
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].pedido").value("MC-T0148"));
        listar(admin, "pedido", "MC-99999").andExpect(jsonPath("$.total").value(0));
        listar(admin, "tipo", "MERMA")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIPO_INVALIDO"));
    }

    @Test
    void buscaPorProductoLoteOPedidoYFiltraPorFechaYCantidad() throws Exception {
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));

        // CCPIP-417: q busca en el producto sin mayusculas ni tildes, en el lote y en parte del pedido
        listar(admin, "q", "SÁCHA inchi tostado")
                .andExpect(jsonPath("$.items[*].producto", everyItem(is("Sacha inchi tostado"))))
                .andExpect(jsonPath("$.items[*].pedido", hasItem("MC-T0148")));
        listar(admin, "q", "t0148")
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].pedido").value("MC-T0148"));
        listar(admin, "q", "l-t" + producto).andExpect(jsonPath("$.items[*].lote", everyItem(is("L-T" + producto))));
        listar(admin, "q", "100%_").andExpect(jsonPath("$.total").value(0));

        // Un dia (desde y hasta iguales) o un rango; la venta fue hoy
        listar(admin, "pedido", "MC-T0148", "desde", hoy.toString(), "hasta", hoy.toString()).andExpect(jsonPath("$.total").value(1));
        listar(admin, "pedido", "MC-T0148", "hasta", hoy.minusDays(1).toString()).andExpect(jsonPath("$.total").value(0));
        listar(admin, "pedido", "MC-T0148", "desde", hoy.plusDays(1).toString()).andExpect(jsonPath("$.total").value(0));

        // La venta fue de 2 unidades; los limites son inclusive
        listar(admin, "pedido", "MC-T0148", "cantidadMin", "2", "cantidadMax", "2").andExpect(jsonPath("$.total").value(1));
        listar(admin, "pedido", "MC-T0148", "cantidadMin", "2.5").andExpect(jsonPath("$.total").value(0));
        listar(admin, "pedido", "MC-T0148", "cantidadMax", "1").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void rechazaRangosAlReves() throws Exception {
        Cookie admin = loginAs("admin_acopio@ccpip.test");
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));
        listar(admin, "desde", hoy.toString(), "hasta", hoy.minusDays(1).toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        listar(admin, "cantidadMin", "5", "cantidadMax", "1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        listar(admin, "cantidadMin", "-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALOR_INVALIDO"));
    }

    @Test
    void elAdministradorDeOtroPuntoNoVeEsaVenta() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'admin_otro@ccpip.test', contrasena_hash, 'Admin de otro punto', rol_id, true
                FROM usuario WHERE correo = 'admin_acopio@ccpip.test'""");
        jdbc.update("""
                INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
                SELECT u.id, l.id FROM usuario u, ubicacion l
                WHERE u.correo = 'admin_otro@ccpip.test' AND l.tipo = 'PUNTO_ACOPIO' AND l.nombre <> 'Centro Nacional Lima'
                LIMIT 1""");

        listar(loginAs("admin_otro@ccpip.test"), "pedido", "MC-T0148")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ubicacion").value(not("Centro Nacional Lima")))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void unAdministradorSinPuntoAsignadoLoSabe() throws Exception {
        jdbc.update("""
                DELETE FROM usuario_ubicacion WHERE usuario_id = (SELECT id FROM usuario WHERE correo = 'admin_acopio@ccpip.test')""");
        listar(loginAs("admin_acopio@ccpip.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACOPIO_SIN_ASIGNAR"));
    }

    @Test
    void soloElAdministradorDePuntoConsultaLosMovimientos() throws Exception {
        listar(null).andExpect(status().isUnauthorized());
        for (String correo : new String[] {"consumidor@ccpip.test", "productor@ccpip.test", "sub_admin@ccpip.test",
                "sudo@ccpip.test", "distribuidor@ccpip.test"}) {
            listar(loginAs(correo)).andExpect(status().isForbidden());
        }
    }

    private ResultActions listar(Cookie sesion, String... parametros) throws Exception {
        MockHttpServletRequestBuilder peticion = get("/api/acopio/movimientos");
        for (int i = 0; i < parametros.length; i += 2) {
            peticion.param(parametros[i], parametros[i + 1]);
        }
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }
}
