package pe.ccpip.inventario;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import pe.ccpip.inventario.InventarioModelos.DatosMovimiento;
import pe.ccpip.inventario.InventarioModelos.MovimientoRegistrado;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-121, CCPIP-287 y CCPIP-283 (RNF-15, RNF-09): dos envios simultaneos sobre el mismo lote se
 * ordenan con el bloqueo del lote. Sin @Transactional: cada envio confirma su propia transaccion,
 * como en produccion. Al final se borra lo creado, con los disparadores de solo insercion apagados
 * solo dentro de esa limpieza.
 */
class MovimientosConcurrenciaTest extends IntegrationTest {

    @Autowired
    private MovimientoManualService servicio;

    @Autowired
    private TransactionTemplate transaccion;

    private long producto;
    private long lote;
    private long admin;

    @BeforeEach
    void loteConCincoUnidades() {
        producto = productoConLote("Concurrencia " + UUID.randomUUID(), "BORRADOR", null, null);
        long ubicacion = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = 'Centro Nacional Lima'", Long.class);
        admin = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = 'admin_acopio@ccpip.test'", Long.class);
        lote = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual)
                VALUES (?, ?, 'CC-1', 5, 5) RETURNING id""", Long.class, producto, ubicacion);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad)
                VALUES (?, ?, ?, 'ENTRADA', 5)""", producto, ubicacion, lote);
        jdbc.update("INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, 5)", producto, ubicacion);
    }

    @AfterEach
    void limpiar() {
        transaccion.executeWithoutResult(t -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("DELETE FROM movimiento_inventario WHERE producto_id = ?", producto);
            jdbc.update("DELETE FROM stock WHERE producto_id = ?", producto);
            jdbc.update("DELETE FROM lote WHERE producto_id = ?", producto);
            jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", producto);
            jdbc.update("DELETE FROM producto_linea WHERE producto_id = ?", producto);
            jdbc.update("DELETE FROM producto WHERE id = ?", producto);
        });
    }

    @Test
    void dosSalidasSimultaneasNoDejanElLoteEnNegativo() throws Exception {
        // 3 + 3 > 5: una pasa y la otra se rechaza, nunca las dos
        List<Object> resultados = aLaVez(
                () -> servicio.registrarEnPunto(admin, salida("3", UUID.randomUUID())),
                () -> servicio.registrarEnPunto(admin, salida("3", UUID.randomUUID())));

        assertThat(resultados).filteredOn(MovimientoRegistrado.class::isInstance).hasSize(1);
        assertThat(resultados).filteredOn(ErrorNegocio.class::isInstance)
                .singleElement().extracting(e -> ((ErrorNegocio) e).code()).isEqualTo("SALDO_INSUFICIENTE");
        assertThat(saldo()).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("SELECT cantidad FROM stock WHERE producto_id = ?", BigDecimal.class, producto))
                .isEqualByComparingTo("2");
        assertThat(salidas()).isEqualTo(1);
    }

    @Test
    void elMismoEnvioSimultaneoSeRegistraUnaSolaVez() throws Exception {
        UUID clave = UUID.randomUUID();
        List<Object> resultados = aLaVez(
                () -> servicio.registrarEnPunto(admin, salida("2", clave)),
                () -> servicio.registrarEnPunto(admin, salida("2", clave)));

        assertThat(resultados).allMatch(MovimientoRegistrado.class::isInstance);
        List<MovimientoRegistrado> registrados = resultados.stream().map(MovimientoRegistrado.class::cast).toList();
        assertThat(registrados).extracting(MovimientoRegistrado::repetido).containsExactlyInAnyOrder(true, false);
        assertThat(registrados).extracting(r -> r.movimiento().id()).containsOnly(registrados.get(0).movimiento().id());
        assertThat(saldo()).isEqualByComparingTo("3");
        assertThat(salidas()).isEqualTo(1);
    }

    private DatosMovimiento salida(String cantidad, UUID clave) {
        return new DatosMovimiento("SALIDA", lote, new BigDecimal(cantidad), "DEVOLUCION_PRODUCTOR", null, clave);
    }

    /** Lanza los envios al mismo tiempo y devuelve lo que respondio cada uno, o su error de negocio. */
    @SafeVarargs
    private List<Object> aLaVez(Callable<MovimientoRegistrado>... envios) throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(envios.length);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Object>> futuros = new ArrayList<>();
            for (Callable<MovimientoRegistrado> envio : envios) {
                futuros.add(hilos.submit(() -> {
                    largada.await();
                    try {
                        return envio.call();
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

    private BigDecimal saldo() {
        return jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE id = ?", BigDecimal.class, lote);
    }

    private int salidas() {
        return jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE lote_id = ? AND tipo = 'SALIDA'",
                Integer.class, lote);
    }
}
