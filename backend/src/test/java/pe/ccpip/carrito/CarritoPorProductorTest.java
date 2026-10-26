package pe.ccpip.carrito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-63 (RF-44): el comprador consulta su carrito agrupado por productor con el subtotal de cada
 * grupo. El total coincide con la suma de los grupos y no se cruzan ofertas ni inventarios.
 * productor@ccpip.test es la tienda "Bordadoras de San Francisco" y productor2@ccpip.test, "Cacaoteros de Umukai".
 */
@Transactional
class CarritoPorProductorTest extends IntegrationTest {

    private static final LocalDate EN_UN_MES = LocalDate.now(ZoneId.of("America/Lima")).plusDays(30);

    private Cookie consumidor;

    @BeforeEach
    void preparar() throws Exception {
        consumidor = loginAs("consumidor@ccpip.test");
    }

    @Test
    void elTotalEsLaSumaDeLosGruposDeDosProductores() throws Exception {
        long sacha = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        long cacao = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES, "productor2@ccpip.test");
        long camu = productoConLote("Camu camu en polvo", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        jdbc.update("UPDATE producto SET precio = 15.50 WHERE id = ?", cacao);
        jdbc.update("UPDATE producto SET precio = 12.35 WHERE id = ?", camu);
        agregar(sacha, 2);
        agregar(cacao, 3);
        agregar(camu, 1);

        JsonNode carrito = leer(consultar()
                .andExpect(status().isOk())
                // Un grupo por productor, en el orden de su primera oferta; las ofertas en el orden en que se agregaron
                .andExpect(jsonPath("$.grupos.length()").value(2))
                .andExpect(jsonPath("$.grupos[0].tienda.nombre").value("Bordadoras de San Francisco"))
                .andExpect(jsonPath("$.grupos[0].tienda.comunidad").isString())
                .andExpect(jsonPath("$.grupos[0].items.length()").value(2))
                .andExpect(jsonPath("$.grupos[0].items[0].productoId").value(sacha))
                .andExpect(jsonPath("$.grupos[0].items[1].productoId").value(camu))
                .andExpect(jsonPath("$.grupos[0].subtotal").value(52.35))
                .andExpect(jsonPath("$.grupos[1].tienda.nombre").value("Cacaoteros de Umukai"))
                .andExpect(jsonPath("$.grupos[1].items.length()").value(1))
                .andExpect(jsonPath("$.grupos[1].items[0].productoId").value(cacao))
                .andExpect(jsonPath("$.grupos[1].subtotal").value(46.5))
                .andExpect(jsonPath("$.comunidades").value(2))
                .andExpect(jsonPath("$.total").value(98.85))
                // La lista plana se mantiene para la compra (CCPIP-157)
                .andExpect(jsonPath("$.items.length()").value(3)));

        assertThat(sumaDeGrupos(carrito)).isEqualByComparingTo(carrito.get("total").decimalValue());
        // Cada grupo es exactamente la suma de los subtotales de sus ofertas
        for (JsonNode grupo : carrito.get("grupos")) {
            BigDecimal deItems = BigDecimal.ZERO;
            for (JsonNode item : grupo.get("items")) {
                assertThat(item.get("tienda").get("id").asLong()).isEqualTo(grupo.get("tienda").get("id").asLong());
                deItems = deItems.add(item.get("subtotal").decimalValue());
            }
            assertThat(deItems).isEqualByComparingTo(grupo.get("subtotal").decimalValue());
        }
    }

    @Test
    void loQueNoSePuedeComprarSeMuestraEnSuGrupoPeroNoSuma() throws Exception {
        long sacha = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        long agotado = productoConLote("Aguaje agotado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        long cacao = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES, "productor2@ccpip.test");
        long retirado = productoConLote("Aceite retirado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES, "productor2@ccpip.test");
        agregar(sacha, 2);
        agregar(agotado, 1);
        agregar(cacao, 4);
        agregar(retirado, 1);

        // Otros compradores se llevaron unidades y el productor 2 retiro una oferta
        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE producto_id = ?", agotado);
        jdbc.update("UPDATE lote SET cantidad_actual = 3 WHERE producto_id = ?", cacao);
        jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", retirado);

        JsonNode carrito = leer(consultar()
                .andExpect(jsonPath("$.grupos.length()").value(2))
                .andExpect(jsonPath("$.grupos[0].items[0].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.grupos[0].items[1].estado").value("AGOTADO"))
                .andExpect(jsonPath("$.grupos[0].items[1].subtotal").doesNotExist())
                .andExpect(jsonPath("$.grupos[0].subtotal").value(40))
                // Un grupo sin nada comprable sigue visible para ajustarlo o quitarlo, con subtotal cero
                .andExpect(jsonPath("$.grupos[1].items[0].estado").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.grupos[1].items[1].estado").value("NO_DISPONIBLE"))
                .andExpect(jsonPath("$.grupos[1].subtotal").value(0))
                .andExpect(jsonPath("$.comunidades").value(1))
                .andExpect(jsonPath("$.unidades").value(8))
                .andExpect(jsonPath("$.total").value(40)));
        assertThat(sumaDeGrupos(carrito)).isEqualByComparingTo(carrito.get("total").decimalValue());
    }

    @Test
    void ofertasDeIgualNombreDeDosProductoresNoSeCruzan() throws Exception {
        long delPrimero = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        long delSegundo = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES, "productor2@ccpip.test");
        jdbc.update("UPDATE producto SET precio = 18 WHERE id = ?", delSegundo);
        agregar(delPrimero, 5);
        agregar(delSegundo, 2);

        // Cada oferta con su precio y su propio saldo: el primero tiene 5 y el segundo no le presta stock
        consultar()
                .andExpect(jsonPath("$.grupos.length()").value(2))
                .andExpect(jsonPath("$.grupos[0].items.length()").value(1))
                .andExpect(jsonPath("$.grupos[0].items[0].productoId").value(delPrimero))
                .andExpect(jsonPath("$.grupos[0].items[0].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.grupos[0].subtotal").value(100))
                .andExpect(jsonPath("$.grupos[1].items.length()").value(1))
                .andExpect(jsonPath("$.grupos[1].items[0].productoId").value(delSegundo))
                .andExpect(jsonPath("$.grupos[1].subtotal").value(36))
                .andExpect(jsonPath("$.total").value(136));

        jdbc.update("UPDATE lote SET cantidad_actual = 1 WHERE producto_id = ?", delSegundo);
        consultar()
                .andExpect(jsonPath("$.grupos[0].items[0].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.grupos[1].items[0].estado").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.grupos[1].items[0].disponibles").value(1))
                .andExpect(jsonPath("$.total").value(100));
    }

    @Test
    void unCarritoVacioNoTieneGrupos() throws Exception {
        consultar()
                .andExpect(jsonPath("$.grupos").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }

    private static BigDecimal sumaDeGrupos(JsonNode carrito) {
        BigDecimal suma = BigDecimal.ZERO;
        for (JsonNode grupo : carrito.get("grupos")) {
            suma = suma.add(grupo.get("subtotal").decimalValue());
        }
        return suma;
    }

    private JsonNode leer(ResultActions respuesta) throws Exception {
        return objectMapper.readTree(respuesta.andReturn().getResponse().getContentAsString());
    }

    private ResultActions consultar() throws Exception {
        return mockMvc.perform(get("/api/carrito").cookie(consumidor));
    }

    private void agregar(long productoId, int cantidad) throws Exception {
        mockMvc.perform(post("/api/carrito/items").cookie(consumidor).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productoId", productoId, "cantidad", cantidad))))
                .andExpect(status().isOk());
    }
}
