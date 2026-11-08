package pe.ccpip.gobernanza;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

/** Pruebas de integración de las fuentes de contenido en la bitácora CCPIP-509. */
@Transactional
class AuditoriaContenidoTest extends IntegrationTest {
    @Test
    void registraCambioDeDestacadoConElActorPropietario() {
        long id = productoConLote("Producto destacado audit-test", "PUBLICADO", "PUNTO_ACOPIO", java.time.LocalDate.now().plusMonths(3));
        long actor = jdbc.queryForObject("SELECT pr.usuario_id FROM producto p JOIN productor pr ON pr.id=p.productor_id WHERE p.id=?", Long.class, id);
        jdbc.update("UPDATE producto SET destacado_tienda=true WHERE id=?", id);
        var evento = jdbc.queryForMap("SELECT * FROM auditoria_evento WHERE accion='TIENDA_DESTACADO_CAMBIADO' AND entidad='PRODUCTO' AND referencia=?", String.valueOf(id));
        assertEquals("TIENDA_DESTACADO_CAMBIADO", evento.get("accion"));
        assertEquals(actor, ((Number) evento.get("usuario_id")).longValue());
    }

    @Test
    void ignoraActualizacionesNoRelacionadasDeProducto() {
        long id = productoConLote("Producto no destacado audit-test", "PUBLICADO", "PUNTO_ACOPIO", java.time.LocalDate.now().plusMonths(3));
        long antes = jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE entidad='PRODUCTO' AND referencia=?", Long.class, String.valueOf(id));
        jdbc.update("UPDATE producto SET nombre=nombre WHERE id=?", id);
        long despues = jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE entidad='PRODUCTO' AND referencia=?", Long.class, String.valueOf(id));
        assertEquals(antes, despues);
    }
}
