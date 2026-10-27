package pe.ccpip.distribucion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import pe.ccpip.distribucion.DistribucionModelos.EntregaDelDistribuidor;
import pe.ccpip.support.IntegrationTest;

/** Prueba concurrente contra PostgreSQL real con fixtures confirmados, igual que las transacciones de produccion. */
class DistribucionEntregarConcurrenciaTest extends IntegrationTest {

    @Autowired
    private DistribucionService distribucion;

    @Autowired
    private TransactionTemplate transaccion;

    private String numero;
    private long producto;
    private long pedido;
    private long distribuidor;

    @BeforeEach
    void prepararPedidoEnCamino() {
        numero = "CE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        producto = productoConLote("Entrega concurrente " + numero, "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now().plusMonths(6));
        pedido = pedidoConfirmado(numero, "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET modalidad='ENVIO', estado='LISTO_PARA_DESPACHO' WHERE id=?", pedido);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, 'LISTO_PARA_DESPACHO')", pedido);
        jdbc.update("INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular) VALUES (?, '150122', 'Av. Larco 100', 'Fixture', '987654321')", pedido);
        distribuidor = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='distribuidor@ccpip.test'", Long.class);
        assertThat(distribucion.asumir(distribuidor, numero).estado()).isEqualTo("ASIGNADA");
        assertThat(distribucion.retirar(distribuidor, numero).estado()).isEqualTo("RETIRADA");
        assertThat(distribucion.enCamino(distribuidor, numero).estado()).isEqualTo("EN_CAMINO");
    }

    @AfterEach
    void limpiarFixtureConfirmado() {
        transaccion.executeWithoutResult(t -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("DELETE FROM entrega WHERE pedido_id=?", pedido);
            jdbc.update("DELETE FROM pedido_estado_historial WHERE pedido_id=?", pedido);
            jdbc.update("DELETE FROM item_pedido WHERE pedido_id=?", pedido);
            jdbc.update("DELETE FROM pedido WHERE id=?", pedido);
            jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id=?", producto);
            jdbc.update("DELETE FROM producto_linea WHERE producto_id=?", producto);
            jdbc.update("DELETE FROM producto WHERE id=?", producto);
        });
    }

    @Test
    void dosEntregasConcurrentesDelPropietarioConfirmanUnaSolaTransicionSinEfectosDeStock() throws Exception {
        List<Map<String, Object>> lotesAntes = jdbc.queryForList("SELECT * FROM lote WHERE producto_id=? ORDER BY id", producto);
        List<Map<String, Object>> movimientosAntes = jdbc.queryForList("SELECT * FROM movimiento_inventario ORDER BY id");

        List<EntregaDelDistribuidor> respuestas = aLaVez(
                () -> distribucion.entregar(distribuidor, numero),
                () -> distribucion.entregar(distribuidor, numero));

        assertThat(respuestas).hasSize(2).allSatisfy(r -> assertThat(r.estado()).isEqualTo("ENTREGADA"));
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id=?", String.class, pedido)).isEqualTo("ENTREGADA");
        assertThat(jdbc.queryForObject("SELECT estado FROM pedido WHERE id=?", String.class, pedido)).isEqualTo("ENTREGADO");
        assertThat(jdbc.queryForObject("SELECT entregada_en FROM entrega WHERE pedido_id=?", java.sql.Timestamp.class, pedido)).isNotNull();
        List<Map<String, Object>> eventos = jdbc.queryForList("SELECT * FROM pedido_estado_historial WHERE pedido_id=? AND estado='ENTREGADO' ORDER BY id", pedido);
        assertThat(eventos).hasSize(1);
        assertThat(eventos.get(0).get("usuario_id")).isEqualTo(distribuidor);
        assertThat(eventos.get(0).get("registrado_en")).isNotNull();

        var entregaAntesRetry = jdbc.queryForList("SELECT * FROM entrega WHERE pedido_id=?", pedido);
        var pedidoAntesRetry = jdbc.queryForList("SELECT * FROM pedido WHERE id=?", pedido);
        var historialAntesRetry = jdbc.queryForList("SELECT * FROM pedido_estado_historial WHERE pedido_id=? ORDER BY id", pedido);
        var lotesDespues = jdbc.queryForList("SELECT * FROM lote WHERE producto_id=? ORDER BY id", producto);
        var movimientosDespues = jdbc.queryForList("SELECT * FROM movimiento_inventario ORDER BY id");
        assertThat(lotesDespues).isEqualTo(lotesAntes);
        assertThat(movimientosDespues).isEqualTo(movimientosAntes);

        assertThat(distribucion.entregar(distribuidor, numero).estado()).isEqualTo("ENTREGADA");
        assertThat(jdbc.queryForList("SELECT * FROM entrega WHERE pedido_id=?", pedido)).isEqualTo(entregaAntesRetry);
        assertThat(jdbc.queryForList("SELECT * FROM pedido WHERE id=?", pedido)).isEqualTo(pedidoAntesRetry);
        assertThat(jdbc.queryForList("SELECT * FROM pedido_estado_historial WHERE pedido_id=? ORDER BY id", pedido)).isEqualTo(historialAntesRetry);
        assertThat(jdbc.queryForList("SELECT * FROM lote WHERE producto_id=? ORDER BY id", producto)).isEqualTo(lotesAntes);
        assertThat(jdbc.queryForList("SELECT * FROM movimiento_inventario ORDER BY id")).isEqualTo(movimientosAntes);
    }

    @SafeVarargs
    private final List<EntregaDelDistribuidor> aLaVez(Callable<EntregaDelDistribuidor>... solicitudes) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(solicitudes.length);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<EntregaDelDistribuidor>> futuros = new ArrayList<>();
            for (Callable<EntregaDelDistribuidor> solicitud : solicitudes) {
                futuros.add(hilos.submit(() -> {
                    if (!largada.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("No se libero la largada concurrente");
                    }
                    return solicitud.call();
                }));
            }
            largada.countDown();
            List<EntregaDelDistribuidor> respuestas = new ArrayList<>();
            for (Future<EntregaDelDistribuidor> futuro : futuros) {
                respuestas.add(futuro.get(30, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            hilos.shutdownNow();
        }
    }
}
