package pe.ccpip.entrega;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
 * CCPIP-485 y CCPIP-486: un producto de un pedido confirmado se malogra en el punto antes de entregarse y
 * el administrador lo reemplaza con otro lote o lo reembolsa. admin_acopio@ccpip.test administra el Centro
 * Nacional Lima; el pedido es de 2 unidades a S/ 20.00, pagado, con su lote A ya descontado.
 */
@Transactional
class ProductoMalogradoTest extends IntegrationTest {

    private static final String LIMA = "Centro Nacional Lima";
    private static final String NUMERO = "MC-T0485";
    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Lima"));

    @Autowired
    DescuentoExistenciasService descuento;

    private Cookie admin;
    private long producto;
    private long pedido;
    private long item;
    private long loteA;
    private long loteB;
    private long danio;

    @BeforeEach
    void preparar() throws Exception {
        admin = loginAs("admin_acopio@ccpip.test");
        producto = productoConLote("Aguaymanto deshidratado", "PUBLICADO", "PUNTO_ACOPIO", HOY.plusDays(30));
        loteA = jdbc.queryForObject("SELECT id FROM lote WHERE producto_id = ?", Long.class, producto);
        loteB = lote("L-B" + producto, LIMA, 4, HOY.plusDays(60));
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) SELECT ?, id, 9 FROM ubicacion WHERE nombre = ?",
                producto, LIMA);
        pedido = pedidoConfirmado(NUMERO, LIMA, producto, 2);
        item = jdbc.queryForObject("SELECT id FROM item_pedido WHERE pedido_id = ?", Long.class, pedido);
        // Pagado con tarjeta: el pago se registra cuando el pedido espera su pago
        jdbc.update("UPDATE pedido SET estado = 'PENDIENTE_PAGO', confirmado_en = NULL WHERE id = ?", pedido);
        jdbc.update("""
                INSERT INTO pago (pedido_id, clave_idempotencia, monto, estado, pasarela, referencia_pasarela, marca_tarjeta,
                                  codigo_respuesta, resuelto_en)
                VALUES (?, ?, 40.00, 'APROBADO', 'SIMULADA', ?, 'VISA', 'APROBADO', now())""",
                pedido, UUID.randomUUID(), "SIM-" + UUID.randomUUID());
        jdbc.update("UPDATE pedido SET estado = 'CONFIRMADO', confirmado_en = now() WHERE id = ?", pedido);
        long comprador = jdbc.queryForObject("SELECT usuario_id FROM pedido WHERE id = ?", Long.class, pedido);
        descuento.descontarPorPedido(pedido, comprador);
        danio = jdbc.queryForObject("SELECT id FROM motivo_merma WHERE nombre = 'Daño'", Long.class);
    }

    private long lote(String codigo, String punto, int cantidad, LocalDate vence) {
        return jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion,
                                  fecha_vencimiento)
                SELECT ?, u.id, ?, greatest(?, 1), ?, ?::date - 90, ? FROM ubicacion u WHERE u.nombre = ?
                RETURNING id""", Long.class, producto, codigo, cantidad, cantidad, vence, vence, punto);
    }

    private ResultActions malogrado(Cookie quien, String accion, long lote, String cantidad, Long reemplazo, UUID clave)
            throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("loteId", lote);
        cuerpo.put("cantidad", new BigDecimal(cantidad));
        cuerpo.put("motivoId", danio);
        cuerpo.put("accion", accion);
        cuerpo.put("loteReemplazoId", reemplazo);
        cuerpo.put("clave", clave);
        return mockMvc.perform(post("/api/acopio/pedidos/" + NUMERO + "/lineas/" + item + "/malogrado").cookie(quien)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private BigDecimal saldo(long lote) {
        return jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE id = ?", BigDecimal.class, lote);
    }

    private BigDecimal stock() {
        return jdbc.queryForObject("""
                SELECT cantidad FROM stock WHERE producto_id = ? AND ubicacion_id = (SELECT id FROM ubicacion WHERE nombre = ?)""",
                BigDecimal.class, producto, LIMA);
    }

    // ---------- CCPIP-485: reemplazo ----------

    @Test
    void ofreceLosLotesDeLaLineaYLosVigentesDelPuntoParaReemplazar() throws Exception {
        lote("L-V" + producto, LIMA, 6, HOY.minusDays(1));
        lote("L-J" + producto, "Centro Regional Junin", 6, HOY.plusDays(60));
        mockMvc.perform(get("/api/acopio/pedidos/" + NUMERO + "/lineas/" + item + "/malogrado").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.porEntregar").value(2))
                .andExpect(jsonPath("$.lotes", hasSize(1)))
                .andExpect(jsonPath("$.lotes[0].id").value(loteA))
                .andExpect(jsonPath("$.lotes[0].cantidad").value(2))
                // Ni el vencido, ni el de otro punto, ni el que ya cubre la linea
                .andExpect(jsonPath("$.reemplazos", hasSize(1)))
                .andExpect(jsonPath("$.reemplazos[0].id").value(loteB))
                .andExpect(jsonPath("$.reemplazos[0].disponible").value(4));
    }

    @Test
    void reemplazaConOtroLoteSinEditarElOriginalYDescontandoElNuevoUnaVez() throws Exception {
        BigDecimal stockAntes = stock();
        malogrado(admin, "REEMPLAZAR", loteA, "1", loteB, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("REEMPLAZO"))
                .andExpect(jsonPath("$.reembolsado").value(0))
                .andExpect(jsonPath("$.pedido.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.pedido.lineas[0].porEntregar").value(2))
                .andExpect(jsonPath("$.pedido.lineas[0].lotes", hasSize(2)))
                .andExpect(jsonPath("$.pedido.lineas[0].incidencias[0].tipo").value("REEMPLAZO"))
                .andExpect(jsonPath("$.pedido.lineas[0].incidencias[0].loteNuevo").value("L-B" + producto))
                .andExpect(jsonPath("$.pedido.lineas[0].incidencias[0].causa").value("Daño"));

        // El lote original no cambia (reintegro y merma se compensan); el nuevo baja una sola vez
        assertThat(saldo(loteA)).isEqualByComparingTo("3");
        assertThat(saldo(loteB)).isEqualByComparingTo("3");
        assertThat(stock()).isEqualByComparingTo(stockAntes.subtract(BigDecimal.ONE));
        assertThat(jdbc.queryForObject("""
                SELECT string_agg(tipo || coalesce(':' || motivo, ''), ',' ORDER BY id) FROM movimiento_inventario
                WHERE producto_id = ? AND tipo <> 'ENTRADA' OR (producto_id = ? AND motivo = 'REINTEGRO_PEDIDO')""",
                String.class, producto, producto))
                .isEqualTo("SALIDA_VENTA,ENTRADA:REINTEGRO_PEDIDO,MERMA,SALIDA_VENTA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merma WHERE lote_id = ? AND NOT automatica", Long.class, loteA))
                .isEqualTo(1);
        // El comprador lo ve en su pedido, el precio no cambia, y recibe un correo
        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(jsonPath("$.incidencias[0].tipo").value("REEMPLAZO"))
                .andExpect(jsonPath("$.incidencias[0].producto").value("Aguaymanto deshidratado"))
                .andExpect(jsonPath("$.reembolsado").value(0))
                .andExpect(jsonPath("$.totalFinal").value(40.0))
                .andExpect(jsonPath("$.lineas[0].noEntregada").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aviso WHERE tipo = 'PRODUCTO_MALOGRADO' AND usuario_id = "
                + "(SELECT usuario_id FROM pedido WHERE id = ?)", Long.class, pedido)).isEqualTo(1);
    }

    @Test
    void elReemplazoRechazaLotesQueNoSirven() throws Exception {
        long vencido = lote("L-V" + producto, LIMA, 6, HOY.minusDays(1));
        long otroPunto = lote("L-J" + producto, "Centro Regional Junin", 6, HOY.plusDays(60));
        long escaso = lote("L-E" + producto, LIMA, 1, HOY.plusDays(60));
        malogrado(admin, "REEMPLAZAR", loteA, "1", vencido, UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOTE_VENCIDO"));
        malogrado(admin, "REEMPLAZAR", loteA, "1", otroPunto, UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOTE_NO_DISPONIBLE"));
        malogrado(admin, "REEMPLAZAR", loteA, "1", loteA, UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MISMO_LOTE"));
        malogrado(admin, "REEMPLAZAR", loteA, "2", escaso, UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SALDO_INSUFICIENTE"));
        malogrado(admin, "REEMPLAZAR", loteA, "3", loteB, UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANTIDAD_EXCEDE"));
        malogrado(admin, "REEMPLAZAR", loteB, "1", loteB, UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LOTE_NO_ASIGNADO"));
        // Nada quedo registrado: ni merma ni incidencia
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidencia_pedido WHERE pedido_id = ?", Long.class, pedido)).isZero();
        assertThat(saldo(loteA)).isEqualByComparingTo("3");
    }

    // ---------- CCPIP-486: entrega parcial y reembolso ----------

    @Test
    void sinOtroLoteEntregaLoDisponibleYReembolsaLoFaltanteConLaComisionEnProporcion() throws Exception {
        jdbc.update("UPDATE item_pedido SET comision_monto = 4.00, neto = 36.00 WHERE id = ?", item);
        malogrado(admin, "REEMBOLSAR", loteA, "1", null, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("FALTANTE"))
                .andExpect(jsonPath("$.reembolsado").value(20.0))
                .andExpect(jsonPath("$.pedidoAnulado").value(false))
                .andExpect(jsonPath("$.pedido.lineas[0].porEntregar").value(1))
                .andExpect(jsonPath("$.pedido.lineas[0].lotes[0].cantidad").value(1));

        assertThat(jdbc.queryForObject("SELECT monto FROM reembolso WHERE pedido_id = ?", BigDecimal.class, pedido))
                .isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("SELECT comision_anulada FROM incidencia_pedido WHERE pedido_id = ?", BigDecimal.class, pedido))
                .isEqualByComparingTo("2.00");
        // El pago original no se toca
        assertThat(jdbc.queryForObject("SELECT estado || ' ' || monto FROM pago WHERE pedido_id = ?", String.class, pedido))
                .isEqualTo("APROBADO 40.00");
        assertThat(saldo(loteA)).isEqualByComparingTo("3");
        mockMvc.perform(get("/api/pedidos/" + pedido).cookie(loginAs("consumidor@ccpip.test")))
                .andExpect(jsonPath("$.estado").value("CONFIRMADO"))
                .andExpect(jsonPath("$.lineas[0].noEntregada").value(1))
                .andExpect(jsonPath("$.incidencias[0].tipo").value("FALTANTE"))
                .andExpect(jsonPath("$.incidencias[0].reembolsado").value(20.0))
                .andExpect(jsonPath("$.reembolsado").value(20.0))
                .andExpect(jsonPath("$.totalFinal").value(20.0));
        // La liquidacion del productor ya no cuenta lo reembolsado
        mockMvc.perform(get("/api/productor/liquidaciones").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidos[?(@.numero == '" + NUMERO + "')].lineas[0].cantidad").value(1.0))
                .andExpect(jsonPath("$.pedidos[?(@.numero == '" + NUMERO + "')].lineas[0].comision").value(2.0))
                .andExpect(jsonPath("$.pedidos[?(@.numero == '" + NUMERO + "')].lineas[0].neto").value(18.0));
    }

    @Test
    void siNoQuedaNadaPorEntregarElPedidoSeAnulaYSeDevuelveTodo() throws Exception {
        malogrado(admin, "REEMBOLSAR", loteA, "1", null, UUID.randomUUID()).andExpect(status().isOk());
        malogrado(admin, "REEMBOLSAR", loteA, "1", null, UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pedidoAnulado").value(true))
                .andExpect(jsonPath("$.pedido.estado").value("CANCELADO"));
        assertThat(jdbc.queryForObject("SELECT sum(monto) FROM reembolso WHERE pedido_id = ?", BigDecimal.class, pedido))
                .isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject("""
                SELECT u.correo FROM pedido_estado_historial h JOIN usuario u ON u.id = h.usuario_id
                WHERE h.pedido_id = ? AND h.estado = 'CANCELADO'""", String.class, pedido)).isEqualTo("admin_acopio@ccpip.test");
        // Ya cancelado, no admite mas
        malogrado(admin, "REEMBOLSAR", loteA, "1", null, UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ESTADO_INVALIDO"));
    }

    @Test
    void unReintentoDelMismoEnvioNoRegistraDosVeces() throws Exception {
        UUID clave = UUID.randomUUID();
        malogrado(admin, "REEMBOLSAR", loteA, "1", null, clave).andExpect(status().isOk())
                .andExpect(jsonPath("$.repetida").value(false));
        malogrado(admin, "REEMBOLSAR", loteA, "1", null, clave).andExpect(status().isOk())
                .andExpect(jsonPath("$.repetida").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidencia_pedido WHERE pedido_id = ?", Long.class, pedido)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reembolso WHERE pedido_id = ?", Long.class, pedido)).isEqualTo(1);
    }

    // ---------- Alcance y validaciones ----------

    @Test
    void unPedidoEntregadoOEnCaminoNoAdmiteMalogrados() throws Exception {
        jdbc.update("UPDATE pedido SET estado = 'ENTREGADO' WHERE id = ?", pedido);
        malogrado(admin, "REEMBOLSAR", loteA, "1", null, UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTADO_INVALIDO"));
    }

    @Test
    void otroPuntoOtrosRolesYDatosInvalidos() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'admin_junin@ccpip.test', contrasena_hash, 'Marta Chavez', rol_id, true
                FROM usuario WHERE correo = 'admin_acopio@ccpip.test'""");
        jdbc.update("""
                INSERT INTO usuario_ubicacion (usuario_id, ubicacion_id)
                SELECT u.id, l.id FROM usuario u, ubicacion l
                WHERE u.correo = 'admin_junin@ccpip.test' AND l.nombre = 'Centro Regional Junin'""");
        malogrado(loginAs("admin_junin@ccpip.test"), "REEMBOLSAR", loteA, "1", null, UUID.randomUUID())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PEDIDO_NO_ENCONTRADO"));
        malogrado(loginAs("consumidor@ccpip.test"), "REEMBOLSAR", loteA, "1", null, UUID.randomUUID())
                .andExpect(status().isForbidden());
        malogrado(admin, "REEMBOLSAR", loteA, "0", null, UUID.randomUUID()).andExpect(status().isBadRequest());
        malogrado(admin, "REEMBOLSAR", loteA, "-1", null, UUID.randomUUID()).andExpect(status().isBadRequest());
        malogrado(admin, "REEMBOLSAR", loteA, "0.0001", null, UUID.randomUUID()).andExpect(status().isBadRequest());
        malogrado(admin, "REEMPLAZAR", loteA, "1", null, UUID.randomUUID())
                .andExpect(jsonPath("$.code").value("LOTE_REEMPLAZO_REQUERIDO"));
        mockMvc.perform(post("/api/acopio/pedidos/" + NUMERO + "/lineas/999999/malogrado").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("loteId", loteA, "cantidad", 1,
                                "motivoId", danio, "accion", "REEMBOLSAR", "clave", UUID.randomUUID()))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINEA_NO_ENCONTRADA"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidencia_pedido WHERE pedido_id = ?", Long.class, pedido)).isZero();
    }

    @Test
    void laBaseNoDejaEditarNiReembolsarDeMas() throws Exception {
        malogrado(admin, "REEMBOLSAR", loteA, "1", null, UUID.randomUUID()).andExpect(status().isOk());
        assertRechazaLaBase(() -> jdbc.update("UPDATE incidencia_pedido SET cantidad = 2"), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM reembolso"), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("""
                INSERT INTO reembolso (pago_id, pedido_id, monto, pasarela, referencia_pasarela, registrado_por)
                SELECT p.id, p.pedido_id, 30.00, 'SIMULADA', 'R-X', u.id FROM pago p, usuario u
                WHERE p.pedido_id = ? AND u.correo = 'admin_acopio@ccpip.test'""", pedido), "supera");
        // El reintegro siempre dice de que pedido, linea y lote vuelve
        assertRechazaLaBase(() -> jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, motivo, cantidad)
                VALUES (?, (SELECT id FROM ubicacion WHERE nombre = ?), ?, 'ENTRADA', 'REINTEGRO_PEDIDO', 1)""",
                producto, LIMA, loteA), "movimiento_reintegro_completo");
    }
}
