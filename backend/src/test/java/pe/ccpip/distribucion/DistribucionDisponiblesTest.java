package pe.ccpip.distribucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-107 (RF-54): el distribuidor consulta los pedidos preparados disponibles para distribucion, con origen,
 * destino y antiguedad. Solo los elegibles: pedidos a domicilio ya preparados y que nadie ha asumido.
 */
@Transactional
class DistribucionDisponiblesTest extends IntegrationTest {

    private static final String RUTA = "/api/distribuidor/pedidos/disponibles";
    private static final String LIMA = "Centro Nacional Lima";
    private static final String CUSCO = "Centro Regional Cusco";

    private Cookie distribuidor;
    private int secuencia;

    @BeforeEach
    void sesion() throws Exception {
        distribuidor = loginAs("distribuidor@ccpip.test");
    }

    /**
     * Un pedido con su entrega. {@code horas}: hace cuanto quedo en {@code estado}; el celular y la direccion del
     * destino son reconocibles para comprobar que no se filtran.
     */
    private long pedido(String numero, String punto, String modalidad, String estado, String ubigeo, String estadoEntrega,
                        boolean conDistribuidor, int horas) {
        long producto = productoConLote("Producto " + numero + secuencia++, "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        long id = pedidoConfirmado(numero, punto, producto, 1);
        jdbc.update("UPDATE pedido SET modalidad = ?, estado = ? WHERE id = ?", modalidad, estado, id);
        jdbc.update("""
                INSERT INTO pedido_estado_historial (pedido_id, estado, usuario_id, registrado_en)
                SELECT ?, ?, usuario_id, now() - make_interval(hours => ?) FROM pedido WHERE id = ?""", id, estado, horas, id);
        if ("ENVIO".equals(modalidad)) {
            jdbc.update("""
                    INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular, estado, distribuidor_id)
                    VALUES (?, ?, 'Calle Secreta 999', 'Persona Reservada', '999888777', ?,
                            (SELECT CASE WHEN ? THEN (SELECT id FROM usuario WHERE correo = 'distribuidor@ccpip.test') END))""",
                    id, ubigeo, estadoEntrega, conDistribuidor);
        }
        return id;
    }

    /** Pide una pagina y la devuelve tal como llega. */
    private JsonNode pagina(String ruta) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(ruta).cookie(distribuidor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Los numeros de esta prueba que aparecen en la cola, en el orden en que llegan, recorriendo todas las paginas. */
    private List<String> mios(String ruta) throws Exception {
        List<String> numeros = new ArrayList<>();
        String cursor = null;
        do {
            String union = ruta.contains("?") ? "&" : "?";
            JsonNode pagina = pagina(ruta + union + "limite=50" + (cursor == null ? "" : "&cursor=" + cursor));
            pagina.get("items").forEach(p -> {
                if (p.get("numero").asText().startsWith("DIS-")) {
                    numeros.add(p.get("numero").asText());
                }
            });
            cursor = pagina.get("siguiente").isNull() ? null : pagina.get("siguiente").asText();
        } while (cursor != null);
        return numeros;
    }

    /** Los numeros de una sola pagina, tal como llegan. */
    private List<String> numeros(JsonNode pagina) {
        List<String> numeros = new ArrayList<>();
        pagina.get("items").forEach(p -> numeros.add(p.get("numero").asText()));
        return numeros;
    }
    @Test
    void listaSoloLosPedidosADomicilioPreparadosYSinAsumir() throws Exception {
        pedido("DIS-A", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 50);
        pedido("DIS-B", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150131", "DISPONIBLE", false, 2);
        // No elegibles: de recojo, aun no preparado, en preparacion, cancelado o ya asumido por un distribuidor
        pedido("DIS-RECOJO", LIMA, "RECOJO", "LISTO_PARA_RECOJO", null, null, false, 3);
        pedido("DIS-CONFIRMADO", LIMA, "ENVIO", "CONFIRMADO", "150122", "DISPONIBLE", false, 3);
        pedido("DIS-PREPARANDO", LIMA, "ENVIO", "EN_PREPARACION", "150122", "DISPONIBLE", false, 3);
        pedido("DIS-CANCELADO", LIMA, "ENVIO", "CANCELADO", "150122", "DISPONIBLE", false, 3);
        pedido("DIS-ASUMIDO", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "ASIGNADA", true, 3);
        pedido("DIS-RETIRADO", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "RETIRADA", true, 3);
        // Tampoco el que sale de un punto de acopio que ya no esta activo
        pedido("DIS-PUNTO-INACTIVO", CUSCO, "ENVIO", "LISTO_PARA_DESPACHO", "080108", "DISPONIBLE", false, 3);
        jdbc.update("UPDATE ubicacion SET activo = false WHERE nombre = ?", CUSCO);

        assertThat(mios(RUTA)).containsExactly("DIS-A", "DIS-B");
    }

    @Test
    void elQueLlevaMasEsperandoVaPrimero() throws Exception {
        pedido("DIS-RECIENTE", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 1);
        pedido("DIS-ANTIGUO", CUSCO, "ENVIO", "LISTO_PARA_DESPACHO", "080108", "DISPONIBLE", false, 30);
        pedido("DIS-MEDIO", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150131", "DISPONIBLE", false, 5);

        assertThat(mios(RUTA)).containsExactly("DIS-ANTIGUO", "DIS-MEDIO", "DIS-RECIENTE");
    }

    @Test
    void cadaPedidoTraeOrigenDestinoYAntiguedad() throws Exception {
        pedido("DIS-A", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 50);

        String fila = "$.items[?(@.numero == 'DIS-A')]";
        mockMvc.perform(get(RUTA).cookie(distribuidor))
                .andExpect(status().isOk())
                .andExpect(jsonPath(fila + ".origen.nombre").value(LIMA))
                .andExpect(jsonPath(fila + ".origen.distrito").exists())
                .andExpect(jsonPath(fila + ".origen.direccion").exists())
                .andExpect(jsonPath(fila + ".destino.ubigeo").value("150122"))
                .andExpect(jsonPath(fila + ".destino.distrito").value("Miraflores"))
                .andExpect(jsonPath(fila + ".destino.provincia").value("Lima"))
                .andExpect(jsonPath(fila + ".destino.departamento").value("Lima"))
                .andExpect(jsonPath(fila + ".disponibleDesde").exists());
        // Lleva unas 50 horas esperando
        long espera = 0;
        for (JsonNode p : pagina(RUTA).get("items")) {
            if ("DIS-A".equals(p.get("numero").asText())) {
                espera = p.get("esperaMinutos").asLong();
            }
        }
        assertThat(espera).isBetween(50L * 60 - 1, 50L * 60 + 5);
    }

    @Test
    void puedeFiltrarPorPuntoDeAcopio() throws Exception {
        pedido("DIS-LIMA", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 4);
        pedido("DIS-CUSCO", CUSCO, "ENVIO", "LISTO_PARA_DESPACHO", "080108", "DISPONIBLE", false, 4);
        long cusco = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, CUSCO);

        assertThat(mios(RUTA + "?punto=" + cusco)).containsExactly("DIS-CUSCO");
        assertThat(mios(RUTA)).containsExactlyInAnyOrder("DIS-LIMA", "DIS-CUSCO");
    }

    @Test
    void unPuntoSinPedidosOInexistenteDaListaVaciaYUnoMalEscritoSeRechaza() throws Exception {
        pedido("DIS-LIMA", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 4);
        long cusco = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, CUSCO);

        assertThat(mios(RUTA + "?punto=" + cusco)).isEmpty();
        assertThat(mios(RUTA + "?punto=999999999")).isEmpty();
        mockMvc.perform(get(RUTA + "?punto=abc").cookie(distribuidor)).andExpect(status().isBadRequest());
    }

    private void asumir(String numero) {
        jdbc.update("""
                UPDATE entrega SET estado = 'ASIGNADA',
                       distribuidor_id = (SELECT id FROM usuario WHERE correo = 'distribuidor@ccpip.test')
                WHERE pedido_id = (SELECT id FROM pedido WHERE numero = ?)""", numero);
    }

    @Test
    void recorreLaColaPorPaginasSinRepetirNiSaltarYConElOrdenDeSiempre() throws Exception {
        // Mismo momento de disponibilidad: empatan y los desempata el id, asi el orden es estable
        for (int i = 1; i <= 5; i++) {
            pedido("DIS-P" + i, LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 10);
        }

        JsonNode primera = pagina(RUTA + "?limite=2");
        assertThat(numeros(primera)).containsExactly("DIS-P1", "DIS-P2");
        assertThat(primera.get("siguiente").isNull()).isFalse();
        JsonNode segunda = pagina(RUTA + "?limite=2&cursor=" + primera.get("siguiente").asText());
        assertThat(numeros(segunda)).containsExactly("DIS-P3", "DIS-P4");
        JsonNode tercera = pagina(RUTA + "?limite=2&cursor=" + segunda.get("siguiente").asText());
        assertThat(numeros(tercera)).containsExactly("DIS-P5");
        assertThat(tercera.get("siguiente").isNull()).isTrue();
        // Una pagina exacta no promete otra vacia
        assertThat(pagina(RUTA + "?limite=5").get("siguiente").isNull()).isTrue();
        assertThat(mios(RUTA)).containsExactly("DIS-P1", "DIS-P2", "DIS-P3", "DIS-P4", "DIS-P5");
    }

    @Test
    void ordenaPorAntiguedadTambienAlPaginar() throws Exception {
        pedido("DIS-RECIENTE", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 1);
        pedido("DIS-ANTIGUO", CUSCO, "ENVIO", "LISTO_PARA_DESPACHO", "080108", "DISPONIBLE", false, 30);
        pedido("DIS-MEDIO", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150131", "DISPONIBLE", false, 5);

        JsonNode primera = pagina(RUTA + "?limite=1");
        assertThat(numeros(primera)).containsExactly("DIS-ANTIGUO");
        JsonNode segunda = pagina(RUTA + "?limite=1&cursor=" + primera.get("siguiente").asText());
        assertThat(numeros(segunda)).containsExactly("DIS-MEDIO");
        assertThat(numeros(pagina(RUTA + "?limite=1&cursor=" + segunda.get("siguiente").asText()))).containsExactly("DIS-RECIENTE");
    }

    @Test
    void unPedidoQueSeAsumeMientrasSeRecorreNoHaceSaltarNiRepetirAlResto() throws Exception {
        for (int i = 1; i <= 4; i++) {
            pedido("DIS-P" + i, LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 10);
        }
        JsonNode primera = pagina(RUTA + "?limite=2");
        assertThat(numeros(primera)).containsExactly("DIS-P1", "DIS-P2");

        // Otro distribuidor asume uno ya visto y otro que aun no se ve
        asumir("DIS-P1");
        asumir("DIS-P3");

        JsonNode segunda = pagina(RUTA + "?limite=2&cursor=" + primera.get("siguiente").asText());
        assertThat(numeros(segunda)).containsExactly("DIS-P4");
        assertThat(segunda.get("siguiente").isNull()).isTrue();
    }

    @Test
    void informaElTotalYLosPuntosConPedidosSinImportarElFiltro() throws Exception {
        pedido("DIS-L1", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 4);
        pedido("DIS-L2", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150131", "DISPONIBLE", false, 3);
        pedido("DIS-C1", CUSCO, "ENVIO", "LISTO_PARA_DESPACHO", "080108", "DISPONIBLE", false, 2);
        long cusco = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ?", Long.class, CUSCO);

        JsonNode todos = pagina(RUTA + "?limite=1");
        assertThat(todos.get("total").asLong()).isEqualTo(3);
        assertThat(todos.get("puntos")).hasSize(2);
        assertThat(todos.get("puntos").get(0).get("nombre").asText()).isEqualTo(LIMA);
        assertThat(todos.get("puntos").get(0).get("total").asLong()).isEqualTo(2);
        assertThat(todos.get("puntos").get(1).get("nombre").asText()).isEqualTo(CUSCO);
        assertThat(todos.get("puntos").get(1).get("id").asLong()).isEqualTo(cusco);

        // Con filtro, el total es el del punto, pero los puntos siguen siendo todos para poder cambiar
        JsonNode soloCusco = pagina(RUTA + "?punto=" + cusco);
        assertThat(soloCusco.get("total").asLong()).isEqualTo(1);
        assertThat(soloCusco.get("puntos")).hasSize(2);
        assertThat(numeros(soloCusco)).containsExactly("DIS-C1");
    }

    @Test
    void rechazaUnLimiteFueraDeRangoYUnCursorQueNoEsDeLaCola() throws Exception {
        mockMvc.perform(get(RUTA).param("limite", "0").cookie(distribuidor)).andExpect(status().isBadRequest());
        mockMvc.perform(get(RUTA).param("limite", "51").cookie(distribuidor)).andExpect(status().isBadRequest());
        mockMvc.perform(get(RUTA).param("limite", "mucho").cookie(distribuidor)).andExpect(status().isBadRequest());
        // Texto cualquiera, base64 de otra cosa ("abc") y base64 de dos numeros incompletos ("1:")
        for (String cursor : List.of("xyz", "YWJj", "MToK", "LTE6LTE")) {
            mockMvc.perform(get(RUTA).param("cursor", cursor).cookie(distribuidor))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("CURSOR_INVALIDO"));
        }
    }
    @Test
    void noMuestraLaDireccionNiElContactoDeQuienRecibe() throws Exception {
        pedido("DIS-A", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 4);

        String cuerpo = mockMvc.perform(get(RUTA).cookie(distribuidor)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain("Calle Secreta").doesNotContain("Persona Reservada").doesNotContain("999888777")
                .doesNotContain("Consumidor de Prueba");
        mockMvc.perform(get(RUTA).cookie(distribuidor))
                .andExpect(jsonPath("$.items[?(@.numero == 'DIS-A')].destino.direccion").doesNotExist())
                .andExpect(jsonPath("$.items[?(@.numero == 'DIS-A')].contactoCelular").doesNotExist());
    }

    @Test
    void sinPedidosPreparadosLaListaVacia() throws Exception {
        mockMvc.perform(get(RUTA).param("punto", "999999").cookie(distribuidor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.siguiente").doesNotExist())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void esSoloParaElDistribuidor() throws Exception {
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(RUTA).cookie(loginAs("consumidor@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get(RUTA).cookie(loginAs("productor@ccpip.test"))).andExpect(status().isForbidden());
        mockMvc.perform(get(RUTA).cookie(loginAs("admin_acopio@ccpip.test"))).andExpect(status().isForbidden());
    }

    @Test
    void elPanelPresentaAlDistribuidorComoResponsableDeLasEntregas() throws Exception {
        mockMvc.perform(get("/api/panel/distribuidor").cookie(distribuidor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Tus entregas"));
    }

    @Test
    void laBaseAdmiteElEstadoListoParaDespachoPeroNoOtrosInventados() {
        long id = pedido("DIS-A", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 1);
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id = ?", String.class, id)).isEqualTo("LISTO_PARA_DESPACHO");
        assertRechazaLaBase(() -> jdbc.update("UPDATE pedido SET estado = 'DESPACHADO' WHERE id = ?", id), "pedido_estado_valido");
    }
}
