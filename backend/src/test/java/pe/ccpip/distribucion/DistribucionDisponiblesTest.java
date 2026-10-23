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

    /** Los numeros de esta prueba que aparecen en la lista, en el orden en que llegan. */
    private List<String> mios(String ruta) throws Exception {
        JsonNode lista = objectMapper.readTree(mockMvc.perform(get(ruta).cookie(distribuidor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> numeros = new ArrayList<>();
        lista.forEach(p -> {
            if (p.get("numero").asText().startsWith("DIS-")) {
                numeros.add(p.get("numero").asText());
            }
        });
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

        String fila = "$[?(@.numero == 'DIS-A')]";
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
        JsonNode a = objectMapper.readTree(mockMvc.perform(get(RUTA).cookie(distribuidor)).andReturn().getResponse().getContentAsString());
        long espera = 0;
        for (JsonNode p : a) {
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
    void noMuestraLaDireccionNiElContactoDeQuienRecibe() throws Exception {
        pedido("DIS-A", LIMA, "ENVIO", "LISTO_PARA_DESPACHO", "150122", "DISPONIBLE", false, 4);

        String cuerpo = mockMvc.perform(get(RUTA).cookie(distribuidor)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain("Calle Secreta").doesNotContain("Persona Reservada").doesNotContain("999888777")
                .doesNotContain("Consumidor de Prueba");
        mockMvc.perform(get(RUTA).cookie(distribuidor))
                .andExpect(jsonPath("$[?(@.numero == 'DIS-A')].destino.direccion").doesNotExist())
                .andExpect(jsonPath("$[?(@.numero == 'DIS-A')].contactoCelular").doesNotExist());
    }

    @Test
    void sinPedidosPreparadosLaListaVacia() throws Exception {
        mockMvc.perform(get(RUTA).param("punto", "999999").cookie(distribuidor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
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
