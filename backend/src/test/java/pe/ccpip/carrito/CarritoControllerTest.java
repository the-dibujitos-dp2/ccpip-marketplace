package pe.ccpip.carrito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-58: el comprador agrega una oferta especifica con su cantidad. Cada producto de prueba
 * nace con un lote de 5 unidades (ver {@link IntegrationTest#productoConLote}).
 */
@Transactional
class CarritoControllerTest extends IntegrationTest {

    private static final LocalDate EN_UN_MES = LocalDate.now(ZoneId.of("America/Lima")).plusDays(30);

    private Cookie consumidor;
    private long oferta;

    @BeforeEach
    void preparar() throws Exception {
        consumidor = loginAs("consumidor@ccpip.test");
        oferta = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
    }

    @Test
    void agregaUnaOfertaDisponibleSinReservarNiDescontarExistencias() throws Exception {
        agregar(consumidor, oferta, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productoId").value(oferta))
                .andExpect(jsonPath("$.cantidad").value(2))
                .andExpect(jsonPath("$.articulos").value(1));

        // RNF-35: el lote conserva sus 5 unidades y no hay movimientos de inventario nuevos
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, oferta))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE producto_id = ?",
                Integer.class, oferta)).isZero();
        // El carrito queda persistido en la base, no en el navegador
        assertThat(jdbc.queryForObject("""
                SELECT i.cantidad FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id
                JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = 'consumidor@ccpip.test' AND i.producto_id = ?""",
                Integer.class, oferta)).isEqualTo(2);
    }

    @Test
    void agregarLaMismaOfertaSumaCantidadSinDuplicarla() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        agregar(consumidor, oferta, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(5))
                .andExpect(jsonPath("$.articulos").value(1));

        long otra = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        agregar(consumidor, otra, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulos").value(2));
    }

    @Test
    void noPermiteSuperarElStockVendibleContandoLoQueYaEstaEnElCarrito() throws Exception {
        agregar(consumidor, oferta, 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.error").value("Solo quedan 5 unidades disponibles."));

        agregar(consumidor, oferta, 4).andExpect(status().isOk());
        agregar(consumidor, oferta, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Solo quedan 5 unidades y ya tienes 4 en tu carrito."));
        // Lo rechazado no altera el carrito
        agregar(consumidor, oferta, 1).andExpect(jsonPath("$.cantidad").value(5));
    }

    @Test
    void laCantidadEsUnEnteroDeAlMenosUnaUnidad() throws Exception {
        agregar(consumidor, oferta, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.cantidad").value("La cantidad mínima es una unidad"));
        agregar(consumidor, oferta, -2).andExpect(status().isBadRequest());
        agregar(consumidor, oferta, 1.5)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").value("La cantidad debe ser un número entero de unidades"));
        agregar(consumidor, oferta, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").value("Indica la cantidad"));
    }

    @Test
    void soloSeAgreganOfertasVisiblesYVendibles() throws Exception {
        agregar(consumidor, 999_999_999L, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));

        long borrador = productoConLote("Borrador de aceite", "BORRADOR", "PUNTO_ACOPIO", EN_UN_MES);
        agregar(consumidor, borrador, 1).andExpect(status().isNotFound());

        // Un lote vencido o que sigue en el almacen del productor no se vende
        long vencido = productoConLote("Camu camu vencido", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES.minusDays(31));
        agregar(consumidor, vencido, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGOTADO"));
        long enAlmacen = productoConLote("Aguaje en almacen", "PUBLICADO", "ALMACEN_PRODUCTOR", EN_UN_MES);
        agregar(consumidor, enAlmacen, 1).andExpect(jsonPath("$.code").value("AGOTADO"));
    }

    @Test
    void unaOfertaSoloMayoristaAunNoSeAgrega() throws Exception {
        jdbc.update("""
                UPDATE producto SET modalidad = 'MAYORISTA', precio = NULL, precio_mayorista = 15,
                                    cantidad_minima_mayorista = 10 WHERE id = ?""", oferta);
        agregar(consumidor, oferta, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOLO_MAYORISTA"));
    }

    @Test
    void soloElCompradorConSesionUsaElCarrito() throws Exception {
        agregar(null, oferta, 1)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIN_SESION"));
        agregar(loginAs("productor@ccpip.test"), oferta, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_carrito WHERE producto_id = ?", Integer.class, oferta))
                .isZero();
    }

    private ResultActions agregar(Cookie sesion, long productoId, Number cantidad) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("productoId", productoId);
        cuerpo.put("cantidad", cantidad);
        var peticion = post("/api/carrito/items").contentType(MediaType.APPLICATION_JSON).content(json(cuerpo));
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }
}
