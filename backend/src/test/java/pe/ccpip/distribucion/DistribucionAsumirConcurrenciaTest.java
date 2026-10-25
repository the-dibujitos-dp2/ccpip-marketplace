package pe.ccpip.distribucion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.support.TransactionTemplate;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.distribucion.DistribucionModelos.EntregaAsumida;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-108 (RF-55): asignacion exclusiva ante solicitudes concurrentes. Dos distribuidores asumen la misma entrega
 * al mismo tiempo y solo uno la obtiene. Sin @Transactional: cada solicitud confirma su propia transaccion, como en
 * produccion. Al final se borra lo creado, con los disparadores de solo insercion apagados solo en esa limpieza.
 */
class DistribucionAsumirConcurrenciaTest extends IntegrationTest {

    @Autowired
    private DistribucionService distribucion;

    @Autowired
    private TransactionTemplate transaccion;

    private String numero;
    private long producto;
    private long pedido;
    private long uno;
    private long otro;

    @BeforeEach
    void entregaDisponibleYDosDistribuidores() {
        numero = "CC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        producto = productoConLote("Concurrencia " + numero, "PUBLICADO", null, null);
        pedido = pedidoConfirmado(numero, "Centro Nacional Lima", producto, 1);
        jdbc.update("UPDATE pedido SET modalidad = 'ENVIO', estado = 'LISTO_PARA_DESPACHO' WHERE id = ?", pedido);
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular)
                VALUES (?, '150122', 'Av. Larco 100', 'María Quispe', '987654321')""", pedido);
        uno = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = 'distribuidor@ccpip.test'", Long.class);
        otro = jdbc.queryForObject("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Otro Distribuidor', rol_id, true FROM usuario WHERE correo = 'distribuidor@ccpip.test'
                RETURNING id""", Long.class, numero.toLowerCase() + "@ccpip.test");
    }

    @AfterEach
    void limpiar() {
        transaccion.executeWithoutResult(t -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("DELETE FROM entrega WHERE pedido_id = ?", pedido);
            jdbc.update("DELETE FROM pedido_estado_historial WHERE pedido_id = ?", pedido);
            jdbc.update("DELETE FROM item_pedido WHERE pedido_id = ?", pedido);
            jdbc.update("DELETE FROM pedido WHERE id = ?", pedido);
            jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", producto);
            jdbc.update("DELETE FROM producto_linea WHERE producto_id = ?", producto);
            jdbc.update("DELETE FROM producto WHERE id = ?", producto);
            jdbc.update("DELETE FROM usuario WHERE id = ?", otro);
        });
    }

    @Test
    void dosDistribuidoresALaVezSoloUnoObtieneLaEntrega() throws Exception {
        List<Object> resultados = aLaVez(
                () -> distribucion.asumir(uno, numero),
                () -> distribucion.asumir(otro, numero));

        assertThat(resultados).filteredOn(EntregaAsumida.class::isInstance).hasSize(1);
        assertThat(resultados).filteredOn(ErrorNegocio.class::isInstance)
                .singleElement().extracting(e -> ((ErrorNegocio) e).code()).isEqualTo("PEDIDO_YA_ASUMIDO");
        // El ganador es quien quedo en la entrega
        long ganador = resultados.get(0) instanceof EntregaAsumida ? uno : otro;
        assertThat(jdbc.queryForObject("SELECT distribuidor_id FROM entrega WHERE pedido_id = ?", Long.class, pedido))
                .isEqualTo(ganador);
        assertThat(jdbc.queryForObject("SELECT estado FROM entrega WHERE pedido_id = ?", String.class, pedido))
                .isEqualTo("ASIGNADA");
    }

    @Test
    void elMismoDistribuidorDosVecesALaVezQuedaAsignadoUnaSolaVez() throws Exception {
        List<Object> resultados = aLaVez(
                () -> distribucion.asumir(uno, numero),
                () -> distribucion.asumir(uno, numero));

        assertThat(resultados).allMatch(EntregaAsumida.class::isInstance);
        assertThat(resultados).extracting(r -> ((EntregaAsumida) r).asignadaEn()).containsOnly(((EntregaAsumida) resultados.get(0)).asignadaEn());
        assertThat(jdbc.queryForObject("SELECT distribuidor_id FROM entrega WHERE pedido_id = ?", Long.class, pedido)).isEqualTo(uno);
    }

    /** Lanza las solicitudes al mismo tiempo y devuelve lo que respondio cada una, o su error de negocio. */
    @SafeVarargs
    private List<Object> aLaVez(Callable<EntregaAsumida>... solicitudes) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(solicitudes.length);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Object>> futuros = new ArrayList<>();
            for (Callable<EntregaAsumida> solicitud : solicitudes) {
                futuros.add(hilos.submit(() -> {
                    largada.await();
                    try {
                        return solicitud.call();
                    } catch (ErrorNegocio e) {
                        return e;
                    }
                }));
            }
            largada.countDown();
            List<Object> resultados = new ArrayList<>();
            for (Future<Object> f : futuros) {
                resultados.add(f.get(30, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            hilos.shutdownNow();
        }
    }
}
