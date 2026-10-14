package pe.ccpip.semillas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.support.IntegrationTest;

/**
 * Carga inicial idempotente (RNF-28): cuentas por rol (CCPIP-150), productores y autorizaciones
 * (CCPIP-359), ofertas (CCPIP-360) y existencias por movimientos (CCPIP-361).
 * Cada prueba se deshace al terminar para no tocar la base compartida de la suite.
 */
@Transactional
class SemillasTest extends IntegrationTest {

    private static final List<String> TABLAS = List.of(
            "usuario", "productor", "producto", "cuenta_bancaria", "autorizacion_producto",
            "lote", "movimiento_inventario", "stock", "ubicacion", "usuario_ubicacion");

    @Autowired
    private DataSource dataSource;

    /** Cada script va entero, como lo corre Flyway: las funciones con $$ no se pueden partir por ";". */
    private void correrSemillas() {
        ResourceDatabasePopulator semillas = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/R__01_catalogos_base.sql"),
                new ClassPathResource("db/seed/R__02_cuentas_productores_productos.sql"));
        semillas.setSeparator(ScriptUtils.EOF_STATEMENT_SEPARATOR);
        semillas.execute(dataSource);
    }

    private Map<String, Long> conteos() {
        Map<String, Long> conteos = new LinkedHashMap<>();
        TABLAS.forEach(t -> conteos.put(t, jdbc.queryForObject("SELECT count(*) FROM " + t, Long.class)));
        return conteos;
    }

    @Test
    void volverACorrerLasSemillasNoDuplicaNada() {
        Map<String, Long> antes = conteos();

        correrSemillas();

        assertThat(conteos()).isEqualTo(antes);
    }

    @Test
    void hayUnaCuentaConfirmadaPorCadaRol() {
        Long roles = jdbc.queryForObject("""
                SELECT count(DISTINCT rol_id) FROM usuario
                WHERE correo LIKE '%@ccpip.test' AND correo_confirmado AND activo""", Long.class);

        assertThat(roles).isEqualTo(6);
    }

    @Test
    void losProductoresQueVendenTienenCuentaActivaYOfertasAutorizadas() {
        Long sinCuenta = jdbc.queryForObject("""
                SELECT count(*) FROM autorizacion_producto a
                WHERE a.estado = 'APROBADA'
                  AND NOT EXISTS (SELECT 1 FROM cuenta_bancaria c WHERE c.productor_id = a.productor_id)""",
                Long.class);
        Long publicadosSembrados = jdbc.queryForObject("""
                SELECT count(*) FROM producto pr
                JOIN autorizacion_producto a ON a.producto_id = pr.id AND a.estado = 'APROBADA'
                WHERE pr.estado = 'PUBLICADO' AND pr.precio > 0""", Long.class);

        assertThat(sinCuenta).isZero();
        assertThat(publicadosSembrados).isGreaterThanOrEqualTo(10);
    }

    @Test
    void cadaExistenciaInicialEntraPorUnMovimientoLigadoASuLote() {
        List<Map<String, Object>> cargas = jdbc.queryForList("""
                SELECT mv.lote_id, mv.cantidad, l.cantidad_inicial, l.ubicacion_id = mv.ubicacion_id AS misma_ubicacion
                FROM movimiento_inventario mv
                LEFT JOIN lote l ON l.id = mv.lote_id
                WHERE mv.observacion = 'Carga inicial de demostracion'""");

        assertThat(cargas).hasSizeGreaterThanOrEqualTo(10).allSatisfy(c -> {
            assertThat(c.get("lote_id")).isNotNull();
            assertThat(c.get("cantidad")).isEqualTo(c.get("cantidad_inicial"));
            assertThat(c.get("misma_ubicacion")).isEqualTo(true);
        });
    }

    @Test
    void unProductoCreadoEnLaPlataformaNoRecibeAutorizacionNiExistenciasDeLaSemilla() {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, linea_id, unidad_medida_id, precio, estado)
                SELECT p.id, 'Producto propio del productor', (SELECT id FROM linea_producto WHERE codigo = 'CAFE'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'G'), 30.00, 'BORRADOR'
                FROM productor p JOIN usuario u ON u.id = p.usuario_id
                WHERE u.correo = 'productor@ccpip.test'
                RETURNING id""", Long.class);

        correrSemillas();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM autorizacion_producto WHERE producto_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE producto_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lote WHERE producto_id = ?", Long.class, id)).isZero();
    }

    @Test
    void unMovimientoSembradoNoSePuedeCorregirEnSuLugar() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE movimiento_inventario SET cantidad = 1 WHERE observacion = 'Carga inicial de demostracion'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("solo insercion");
    }

    @Test
    void lasExistenciasNuncaQuedanNegativas() {
        assertThatThrownBy(() -> jdbc.update("UPDATE stock SET cantidad = -1"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("stock_no_negativo");
    }

    // --- Datos del Sprint 0 (CCPIP-148): pedidos, casos limite de compra y cuentas de demostracion ---

    @Test
    void hayUnPedidoDeDemostracionEnCadaEstadoDelSprint() {
        List<String> estados = jdbc.queryForList(
                "SELECT DISTINCT estado FROM pedido WHERE numero LIKE 'CCP-900%' ORDER BY estado", String.class);

        assertThat(estados).containsExactly("CANCELADO", "CONFIRMADO", "ENTREGADO", "LISTO_PARA_RECOJO", "PENDIENTE_PAGO");
    }

    @Test
    void cadaPedidoSembradoEsCoherenteConSuPagoSuInventarioYSuHistorial() {
        // El total es la suma de sus lineas
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pedido p
                WHERE p.numero LIKE 'CCP-900%' AND p.total <> (SELECT sum(i.subtotal) FROM item_pedido i WHERE i.pedido_id = p.id)""",
                Long.class)).isZero();
        // Lo cobrado tiene un pago APROBADO y descontó cada linea completa; lo demas no descontó nada
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pedido p
                WHERE p.numero LIKE 'CCP-900%' AND (
                    (p.estado IN ('CONFIRMADO', 'LISTO_PARA_RECOJO', 'ENTREGADO') AND (
                        NOT EXISTS (SELECT 1 FROM pago g WHERE g.pedido_id = p.id AND g.estado = 'APROBADO')
                        OR EXISTS (SELECT 1 FROM item_pedido i WHERE i.pedido_id = p.id AND i.cantidad <>
                            (SELECT coalesce(sum(m.cantidad), 0) FROM movimiento_inventario m WHERE m.item_pedido_id = i.id))))
                 OR (p.estado IN ('PENDIENTE_PAGO', 'CANCELADO') AND (
                        EXISTS (SELECT 1 FROM pago g WHERE g.pedido_id = p.id AND g.estado = 'APROBADO')
                        OR EXISTS (SELECT 1 FROM movimiento_inventario m WHERE m.pedido_id = p.id))))""", Long.class)).isZero();
        // El ultimo estado del historial es el estado del pedido
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pedido p
                WHERE p.numero LIKE 'CCP-900%' AND p.estado <>
                    (SELECT h.estado FROM pedido_estado_historial h WHERE h.pedido_id = p.id ORDER BY h.registrado_en DESC, h.id DESC LIMIT 1)""",
                Long.class)).isZero();
    }

    @Test
    void elStockDeLosProductosSembradosCuadraConSusLotesYMovimientosDespuesDeLasVentas() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock s
                WHERE EXISTS (SELECT 1 FROM movimiento_inventario c WHERE c.producto_id = s.producto_id
                              AND c.observacion = 'Carga inicial de demostracion')
                  AND (s.cantidad <> (SELECT coalesce(sum(l.cantidad_actual), 0) FROM lote l
                                     WHERE l.producto_id = s.producto_id AND l.ubicacion_id = s.ubicacion_id)
                   OR s.cantidad <> (SELECT coalesce(sum(CASE WHEN m.tipo IN ('ENTRADA', 'TRANSFERENCIA_ENTRADA', 'AJUSTE')
                                                              THEN m.cantidad ELSE -m.cantidad END), 0)
                                     FROM movimiento_inventario m
                                     WHERE m.producto_id = s.producto_id AND m.ubicacion_id = s.ubicacion_id))""", Long.class)).isZero();
    }

    @Test
    void laVentaSaleDelLoteQueVenceAntes() {
        List<String> lotes = jdbc.queryForList("""
                SELECT l.codigo FROM movimiento_inventario m JOIN lote l ON l.id = m.lote_id
                JOIN pedido p ON p.id = m.pedido_id
                WHERE p.numero = 'CCP-900152' AND m.tipo = 'SALIDA_VENTA'""", String.class);

        assertThat(lotes).hasSize(1);
        assertThat(lotes.get(0)).endsWith("A");
    }

    @Test
    void hayCasosLimiteDeCompraListosParaProbar() {
        // Venta pausada: oferta visible de una tienda con la cuenta bancaria desactivada
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto pr JOIN productor p ON p.id = pr.productor_id
                JOIN cuenta_bancaria c ON c.productor_id = p.id
                WHERE pr.estado = 'PUBLICADO' AND p.tienda_publicada AND c.estado = 'DESACTIVADA'
                  AND NOT EXISTS (SELECT 1 FROM cuenta_bancaria a WHERE a.productor_id = p.id AND a.estado = 'ACTIVA')""",
                Long.class)).isGreaterThanOrEqualTo(1);
        // Agotado: publicado y sin saldo
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto pr WHERE pr.estado = 'PUBLICADO' AND pr.nombre = 'Harina de castana 300 g'
                  AND NOT EXISTS (SELECT 1 FROM lote l WHERE l.producto_id = pr.id AND l.cantidad_actual > 0)""",
                Long.class)).isEqualTo(1);
        // Dos lotes de un mismo producto, para ver cual sale primero
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT producto_id FROM lote GROUP BY producto_id HAVING count(*) > 1) x""",
                Long.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void elSegundoCompradorTieneUnCarritoArmadoYElPrimeroLoTieneVacio() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id JOIN usuario u ON u.id = c.usuario_id
                WHERE u.correo = 'consumidor2@ccpip.test'""", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM carrito c JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = 'consumidor@ccpip.test'""",
                Long.class)).isZero();
    }

    @Test
    void cadaPuntoDeAcopioTieneSuAdministradorYCadaAdministradorSoloUnPunto() {
        List<Long> puntosSinAdmin = jdbc.queryForList("""
                SELECT l.id FROM ubicacion l
                WHERE l.tipo = 'PUNTO_ACOPIO' AND NOT EXISTS (
                    SELECT 1 FROM usuario_ubicacion uu JOIN usuario u ON u.id = uu.usuario_id
                    JOIN rol r ON r.id = u.rol_id WHERE uu.ubicacion_id = l.id AND r.codigo = 'ADMIN_PUNTO')""", Long.class);

        assertThat(puntosSinAdmin).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT count(*) FROM usuario_ubicacion uu JOIN usuario u ON u.id = uu.usuario_id
                WHERE u.correo LIKE 'admin_acopio%' GROUP BY u.id HAVING count(*) > 1""", Long.class)).isEmpty();
    }

    @Test
    void unProductoNuevoDeUnProductorDeCasosLimiteNoRecibeLaSemilla() {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, linea_id, unidad_medida_id, precio, estado)
                SELECT p.id, 'Producto propio de Ese Eja', (SELECT id FROM linea_producto WHERE codigo = 'CAFE'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'G'), 30.00, 'PUBLICADO'
                FROM productor p JOIN usuario u ON u.id = p.usuario_id
                WHERE u.correo = 'productor4@ccpip.test'
                RETURNING id""", Long.class);

        correrSemillas();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM autorizacion_producto WHERE producto_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM producto_historia WHERE producto_id = ?", Long.class, id)).isZero();
    }
}
