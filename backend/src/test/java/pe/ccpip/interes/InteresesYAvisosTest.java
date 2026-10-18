package pe.ccpip.interes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.aviso.Correo;
import pe.ccpip.entrega.AvisoPedidoListo;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-105 (RF-38): interes de compra en una oferta agotada o pausada, sin reserva ni cobro.
 * CCPIP-106 (RF-24): el productor ve interesados y cantidad por oferta suya.
 * CCPIP-273 (RF-150): aviso por correo cuando la oferta vuelve a venderse, una sola vez.
 * CCPIP-69 (RF-51): aviso por correo cuando el pedido esta listo para recojo, sin duplicar.
 *
 * <p>Transaccional: intereses, avisos y pedidos de la prueba no llegan a las demas pruebas. La tarea
 * periodica no corre durante la suite (ver IntegrationTest); aqui se llama a {@code revisar()}.
 */
@Transactional
class InteresesYAvisosTest extends IntegrationTest {

    private static final String CONSUMIDOR = "consumidor@ccpip.test";
    private static final String OTRO = "otro.interesado@ccpip.test";
    private static final String LIMA = "Centro Nacional Lima";

    @Autowired
    private AvisoInteresVendible tarea;

    @Autowired
    private AvisoPedidoListo avisoListo;

    // --- CCPIP-105: registrar interes ---

    @Test
    void registraElInteresEnUnaOfertaAgotadaYPuedeCambiarLaCantidad() throws Exception {
        long producto = agotado();
        Cookie sesion = loginAs(CONSUMIDOR);
        mockMvc.perform(get(ruta(producto)).cookie(sesion)).andExpect(status().isNoContent());

        interes(sesion, producto, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productoId").value(producto))
                .andExpect(jsonPath("$.cantidad").value(2))
                .andExpect(jsonPath("$.motivo").value("AGOTADO"));
        interes(sesion, producto, 5).andExpect(jsonPath("$.cantidad").value(5));

        mockMvc.perform(get(ruta(producto)).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(5));
        // Un solo interes por comprador y oferta, y nada de reserva, cobro o movimiento de inventario
        assertThat(contar("SELECT count(*) FROM interes_compra WHERE producto_id = ?", producto)).isOne();
        assertThat(contar("SELECT count(*) FROM movimiento_inventario WHERE producto_id = ?", producto)).isZero();
        assertThat(contar("SELECT count(*) FROM item_carrito WHERE producto_id = ?", producto)).isZero();
        assertThat(contar("SELECT count(*) FROM item_pedido WHERE producto_id = ?", producto)).isZero();
    }

    @Test
    void unaOfertaEnVentaPausadaTambienAdmiteInteres() throws Exception {
        long producto = productoConLote("Interes pausado " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        pausarVenta(producto);
        interes(loginAs(CONSUMIDOR), producto, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.motivo").value("VENTA_PAUSADA"));
    }

    @Test
    void unaOfertaQueSePuedeComprarNoAdmiteInteres() throws Exception {
        long producto = productoConLote("Interes disponible " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        interes(loginAs(CONSUMIDOR), producto, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFERTA_DISPONIBLE"));
    }

    @Test
    void unProductoInexistenteONoPublicadoNoAdmiteInteres() throws Exception {
        Cookie sesion = loginAs(CONSUMIDOR);
        interes(sesion, 999_999_999L, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        long borrador = productoConLote("Interes borrador " + UUID.randomUUID(), "BORRADOR", null, null);
        interes(sesion, borrador, 1).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 10_000})
    void laCantidadDebeSerValida(int cantidad) throws Exception {
        interes(loginAs(CONSUMIDOR), agotado(), cantidad)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.cantidad").isString());
    }

    @Test
    void retirarElInteresLoQuitaDelConteo() throws Exception {
        long producto = agotado();
        Cookie sesion = loginAs(CONSUMIDOR);
        interes(sesion, producto, 2).andExpect(status().isOk());
        mockMvc.perform(delete(ruta(producto)).cookie(sesion)).andExpect(status().isNoContent());
        mockMvc.perform(get(ruta(producto)).cookie(sesion)).andExpect(status().isNoContent());
        mockMvc.perform(delete(ruta(producto)).cookie(sesion))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SIN_INTERES"));
        // Se puede volver a registrar
        interes(sesion, producto, 1).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"productor@ccpip.test", "sudo@ccpip.test", "admin_acopio@ccpip.test"})
    void soloElConsumidorRegistraInteres(String correo) throws Exception {
        interes(loginAs(correo), agotado(), 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void sinSesionNoHayInteres() throws Exception {
        long producto = agotado();
        mockMvc.perform(put(ruta(producto)).contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/productor/intereses")).andExpect(status().isUnauthorized());
    }

    @Test
    void elInteresEsPorUnaOfertaYUnProductorConcretos() {
        long producto = agotado();
        long otroProductor = jdbc.queryForObject(
                "SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor2@ccpip.test'", Long.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO interes_compra (usuario_id, producto_id, productor_id, cantidad, motivo)
                SELECT u.id, ?, ?, 1, 'AGOTADO' FROM usuario u WHERE u.correo = ?""", producto, otroProductor, CONSUMIDOR))
                .isInstanceOf(DataAccessException.class);
    }

    // --- CCPIP-106: el productor ve los interesados ---

    @Test
    void elProductorVeInteresadosYCantidadPorOfertaSinDatosDeLosCompradores() throws Exception {
        long producto = agotado();
        crearConsumidor(OTRO);
        interes(loginAs(CONSUMIDOR), producto, 2).andExpect(status().isOk());
        interes(loginAs(OTRO), producto, 5).andExpect(status().isOk());

        mockMvc.perform(get("/api/productor/intereses").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productoId == %d)].interesados".formatted(producto)).value(2))
                .andExpect(jsonPath("$[?(@.productoId == %d)].cantidad".formatted(producto)).value(7))
                .andExpect(jsonPath("$[?(@.productoId == %d)].estado".formatted(producto)).value("AGOTADO"))
                .andExpect(jsonPath("$[?(@.productoId == %d)].unidad".formatted(producto)).value("G"))
                .andExpect(jsonPath("$..correo").doesNotExist())
                .andExpect(jsonPath("$..usuarioId").doesNotExist());

        // Otro productor no ve los intereses en ofertas ajenas
        mockMvc.perform(get("/api/productor/intereses").cookie(loginAs("productor2@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productoId == %d)]".formatted(producto)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {CONSUMIDOR, "sudo@ccpip.test"})
    void soloElProductorConsultaSusInteresados(String correo) throws Exception {
        mockMvc.perform(get("/api/productor/intereses").cookie(loginAs(correo)))
                .andExpect(status().isForbidden());
    }

    // --- CCPIP-273: aviso cuando vuelve a venderse ---

    @Test
    void alReponerStockAvisaUnaVezYCierraElInteres() throws Exception {
        long producto = agotado();
        interes(loginAs(CONSUMIDOR), producto, 2).andExpect(status().isOk());

        // Sigue agotado: nadie recibe nada
        tarea.revisar();
        assertThat(correos.ultimoPara(CONSUMIDOR)).isEmpty();

        reponer(producto);
        assertThat(tarea.revisar()).isGreaterThanOrEqualTo(1);

        Correo correo = correos.ultimoPara(CONSUMIDOR).orElseThrow();
        String nombre = jdbc.queryForObject("SELECT nombre FROM producto WHERE id = ?", String.class, producto);
        assertThat(correo.asunto()).isEqualTo(nombre + " vuelve a estar disponible");
        assertThat(correo.texto()).contains("Registraste tu interés por 2 unidad(es)")
                .contains("No es una reserva")
                .contains("/productos/" + producto);
        assertThat(jdbc.queryForObject("SELECT estado FROM interes_compra WHERE producto_id = ?", String.class, producto))
                .isEqualTo("AVISADO");
        assertThat(jdbc.queryForObject("""
                SELECT a.estado FROM aviso a JOIN interes_compra i ON i.id = a.referencia_id
                WHERE a.tipo = 'INTERES_VENDIBLE' AND i.producto_id = ?""", String.class, producto)).isEqualTo("ENVIADO");

        // Una segunda revision no vuelve a avisar, y el interes sale del conteo del productor
        correos.vaciar();
        tarea.revisar();
        assertThat(correos.ultimoPara(CONSUMIDOR)).isEmpty();
        mockMvc.perform(get("/api/productor/intereses").cookie(loginAs("productor@ccpip.test")))
                .andExpect(jsonPath("$[?(@.productoId == %d)]".formatted(producto)).isEmpty());
        // Sin compra automatica
        assertThat(contar("SELECT count(*) FROM item_pedido WHERE producto_id = ?", producto)).isZero();
    }

    @Test
    void alReactivarLaCuentaDelProductorAvisaAlInteresado() throws Exception {
        long producto = productoConLote("Interes reactivado " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        pausarVenta(producto);
        interes(loginAs(CONSUMIDOR), producto, 1).andExpect(status().isOk());
        tarea.revisar();
        assertThat(correos.ultimoPara(CONSUMIDOR)).isEmpty();

        jdbc.update("""
                UPDATE cuenta_bancaria SET estado = 'ACTIVA', motivo_desactivacion = NULL, desactivada_en = NULL
                WHERE productor_id = (SELECT productor_id FROM producto WHERE id = ?)""", producto);
        tarea.revisar();
        assertThat(correos.ultimoPara(CONSUMIDOR)).isPresent();
    }

    @Test
    void elAvisoSaleEnElIdiomaDelComprador() throws Exception {
        long producto = agotado();
        interes(loginAs(CONSUMIDOR), producto, 3).andExpect(status().isOk());
        jdbc.update("UPDATE usuario SET idioma = 'en' WHERE correo = ?", CONSUMIDOR);
        reponer(producto);
        tarea.revisar();
        Correo correo = correos.ultimoPara(CONSUMIDOR).orElseThrow();
        assertThat(correo.asunto()).endsWith(" is available again");
        assertThat(correo.texto()).contains("You registered interest in 3 unit(s)");
    }

    @Test
    void unCorreoCaidoQuedaRegistradoSinReenviarEnCadaRevision() throws Exception {
        long producto = agotado();
        interes(loginAs(CONSUMIDOR), producto, 1).andExpect(status().isOk());
        reponer(producto);
        correos.fallar(true);
        tarea.revisar();
        assertThat(jdbc.queryForObject("""
                SELECT a.estado FROM aviso a JOIN interes_compra i ON i.id = a.referencia_id
                WHERE a.tipo = 'INTERES_VENDIBLE' AND i.producto_id = ?""", String.class, producto)).isEqualTo("FALLIDO");
        correos.fallar(false);
        tarea.revisar();
        assertThat(correos.ultimoPara(CONSUMIDOR)).isEmpty();
    }

    // --- CCPIP-69: aviso de pedido listo para recojo ---

    @Test
    void alMarcarElPedidoListoElCompradorRecibeUnCorreo() throws Exception {
        long producto = productoConLote("Pedido listo " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        String numero = "AV-" + System.nanoTime() % 1_000_000_000L;
        long pedido = pedidoConfirmado(numero, LIMA, producto, 1);

        mockMvc.perform(post("/api/acopio/pedidos/" + numero + "/listo").cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(status().isOk());

        Correo correo = correos.ultimoPara(CONSUMIDOR).orElseThrow();
        assertThat(correo.asunto()).isEqualTo("Tu pedido " + numero + " está listo para recoger");
        assertThat(correo.texto()).contains(LIMA).contains("Recoge: Tú, con tu documento de identidad.")
                .contains("/pedidos/" + pedido);
        assertThat(contar("SELECT count(*) FROM aviso WHERE tipo = 'PEDIDO_LISTO' AND referencia_id = ? AND estado = 'ENVIADO'", pedido)).isOne();

        // Sin duplicar: avisar otra vez el mismo pedido no envia otro correo
        correos.vaciar();
        avisoListo.avisar(numero);
        assertThat(correos.enviados()).isEmpty();
    }

    @Test
    void elAvisoNombraALaPersonaAutorizada() throws Exception {
        long producto = productoConLote("Pedido autorizado " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        String numero = "AV-" + System.nanoTime() % 1_000_000_000L;
        long pedido = pedidoConfirmado(numero, LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET persona_autorizada = 'Rosa Quispe' WHERE id = ?", pedido);

        mockMvc.perform(post("/api/acopio/pedidos/" + numero + "/listo").cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(status().isOk());
        assertThat(correos.ultimoPara(CONSUMIDOR).orElseThrow().texto()).contains("Recoge: Rosa Quispe, con su documento de identidad.");
    }

    @Test
    void unCorreoCaidoNoImpideMarcarElPedidoListo() throws Exception {
        long producto = productoConLote("Pedido sin correo " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        String numero = "AV-" + System.nanoTime() % 1_000_000_000L;
        long pedido = pedidoConfirmado(numero, LIMA, producto, 1);
        correos.fallar(true);

        mockMvc.perform(post("/api/acopio/pedidos/" + numero + "/listo").cookie(loginAs("admin_acopio@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("LISTO_PARA_RECOJO"));
        assertThat(jdbc.queryForObject("SELECT estado FROM aviso WHERE tipo = 'PEDIDO_LISTO' AND referencia_id = ?",
                String.class, pedido)).isEqualTo("FALLIDO");
    }

    // --- Ayudas ---

    private static String ruta(long producto) {
        return "/api/productos/" + producto + "/interes";
    }

    private ResultActions interes(Cookie sesion, long producto, int cantidad) throws Exception {
        return mockMvc.perform(put(ruta(producto)).cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("cantidad", cantidad))));
    }

    /** Publicado, autorizado y con cuenta activa, pero sin lote: agotado. */
    private long agotado() {
        return productoConLote("Interes agotado " + UUID.randomUUID(), "PUBLICADO", null, null);
    }

    private void reponer(long producto) {
        jdbc.update("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual, fecha_produccion,
                                  fecha_vencimiento)
                SELECT ?, u.id, 'L-R' || ?, 5, 5, current_date - 1, current_date + 90 FROM ubicacion u WHERE u.nombre = ?""",
                producto, producto, LIMA);
    }

    private void pausarVenta(long producto) {
        jdbc.update("""
                UPDATE cuenta_bancaria SET estado = 'DESACTIVADA', motivo_desactivacion = 'OTRO', desactivada_en = now()
                WHERE productor_id = (SELECT productor_id FROM producto WHERE id = ?)""", producto);
    }

    private void crearConsumidor(String correo) {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Otra Interesada', 1, true FROM usuario
                WHERE correo = ? ON CONFLICT (correo) DO NOTHING""", correo, CONSUMIDOR);
    }

    private long contar(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
