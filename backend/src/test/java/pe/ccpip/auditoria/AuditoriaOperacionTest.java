package pe.ccpip.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pe.ccpip.support.IntegrationTest;

class AuditoriaOperacionTest extends IntegrationTest {
    @Test
    void movimientoNuevoRegistraEventoConActorSnapshotYNoDuplica() {
        long producto = productoConLote("Auditoria operativa", "PUBLICADO", "PUNTO_ACOPIO", java.time.LocalDate.now().plusDays(30));
        Long ubicacion = jdbc.queryForObject("SELECT ubicacion_id FROM lote WHERE producto_id=?", Long.class, producto);
        Long actor = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='productor@ccpip.test'", Long.class);
        Long id = jdbc.queryForObject("INSERT INTO movimiento_inventario(producto_id,ubicacion_id,tipo,cantidad,usuario_id) VALUES (?,?,'AJUSTE',1,?) RETURNING id", Long.class, producto, ubicacion, actor);
        assertThat(jdbc.queryForList("SELECT clave_evento,usuario_id,usuario_nombre,rol,detalle FROM auditoria_evento WHERE clave_evento=?", "MI-"+id))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("usuario_id")).isEqualTo(actor);
                    assertThat(row.get("usuario_nombre")).isNotNull();
                    assertThat(row.get("rol")).isNotNull();
                    assertThat(row.get("detalle").toString()).contains("cantidad");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE clave_evento=?", Long.class, "MI-"+id)).isEqualTo(1L);
    }
}
