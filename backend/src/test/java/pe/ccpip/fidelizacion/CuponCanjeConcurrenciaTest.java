package pe.ccpip.fidelizacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import pe.ccpip.support.IntegrationTest;

/** Real PostgreSQL scheduling regression. The subclass only pauses at a domain seam. */
class CuponCanjeConcurrenciaTest extends IntegrationTest {
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired NivelesService niveles;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void editarCatalogoNoDesalineaElCostoDelCuponYElDebito() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 12);
        long buyer = jdbc.queryForObject("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Comprador concurrencia', rol_id, true
                FROM usuario WHERE correo = 'consumidor@ccpip.test' RETURNING id
                """, Long.class, "ccr-" + unique + "@ccpip.test");
        long order = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total)
                SELECT ?, ?, 'RECOJO', id, 1000 FROM ubicacion WHERE nombre = 'Centro Nacional Lima' RETURNING id
                """, Long.class, "CCR-" + unique, buyer);
        jdbc.update("""
                INSERT INTO movimiento_puntos (usuario_id, pedido_id, tipo, puntos, monto_base, regla_puntos, regla_cada_soles)
                VALUES (?, ?, 'ACUMULACION', 100, 1000, 1, 10)
                """, buyer, order);
        long coupon = jdbc.queryForObject("""
                INSERT INTO cupon (nombre, costo_puntos, tipo, valor, vigencia_dias, registrado_por)
                SELECT ?, 10, 'MONTO', 5, 14, id FROM usuario WHERE correo = 'sub_admin@ccpip.test' RETURNING id
                """, Long.class, "Antes " + unique);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        CuponesService scheduled = new CuponesService(namedJdbc, niveles) {
            @Override long saldo(long usuarioId) {
                if (usuarioId == buyer && first.compareAndSet(true, false)) {
                    read.countDown();
                    try {
                        if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("Missing scheduling release");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return super.saldo(usuarioId);
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var redemption = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> scheduled.canjear(buyer, coupon)));
            assertThat(read.await(10, TimeUnit.SECONDS)).as("catalog was read before balance check").isTrue();
            CompletableFuture<Integer> editorPid = new CompletableFuture<>();
            var edit = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                editorPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbc.update("UPDATE cupon SET costo_puntos=30, valor=15, nombre=? WHERE id=?", "Despues " + unique, coupon);
                return true;
            }));
            int pid = editorPid.get(10, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean scheduledInterleaving = false;
            while (System.nanoTime() < deadline) {
                boolean blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT coalesce(bool_or(wait_event_type='Lock'), false) FROM pg_stat_activity WHERE pid=?", Boolean.class, pid));
                if (edit.isDone() || blocked) {
                    scheduledInterleaving = true;
                    break;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            assertThat(scheduledInterleaving).as("editor completed or demonstrably blocked on the catalog lock").isTrue();
            resume.countDown();
            redemption.get(10, TimeUnit.SECONDS);
            edit.get(10, TimeUnit.SECONDS);
            Integer copied = jdbc.queryForObject("SELECT costo_puntos FROM cupon_cliente WHERE usuario_id=? AND cupon_id=?", Integer.class, buyer, coupon);
            Integer debited = jdbc.queryForObject("SELECT puntos FROM movimiento_puntos WHERE usuario_id=? AND tipo='CANJE'", Integer.class, buyer);
            assertThat(copied).as("coupon cost must match auditable point debit").isEqualTo(debited);
            assertThat(copied).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT nombre FROM cupon_cliente WHERE usuario_id=?", String.class, buyer)).isEqualTo("Antes " + unique);
        } finally {
            resume.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
