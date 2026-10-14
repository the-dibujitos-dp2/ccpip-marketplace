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

    private void correrSemillas() {
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/R__01_catalogos_base.sql"),
                new ClassPathResource("db/seed/R__02_cuentas_productores_productos.sql"))
                .execute(dataSource);
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

        assertThat(cargas).hasSize(10).allSatisfy(c -> {
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
}
