package pe.ccpip.gobernanza;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

/** Regresiones para que cada mutación distinta de contenido conserve su propio hecho. */
@Transactional
class AuditoriaContenidoRepetidoTest extends IntegrationTest {
    @Test
    void conservaLaSecuenciaDeCambiosDeDestacadoSinDuplicarElNoOp() {
        long producto = productoConLote("Auditoria repetible", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(3));
        jdbc.update("UPDATE producto SET destacado_tienda=true WHERE id=?", producto);
        jdbc.update("UPDATE producto SET destacado_tienda=false WHERE id=?", producto);
        jdbc.update("UPDATE producto SET destacado_tienda=true WHERE id=?", producto);
        jdbc.update("UPDATE producto SET destacado_tienda=true WHERE id=?", producto);
        assertThat(jdbc.queryForList("SELECT detalle->>'destacado' FROM auditoria_evento WHERE accion='TIENDA_DESTACADO_CAMBIADO' AND referencia=? ORDER BY id", String.class, "" + producto))
                .containsExactly("true", "false", "true");
    }

    @Test
    void conservaLaAprobacionInicialYCorreccionesDeUnaTraduccionSinRegistrarTexto() {
        long producto = productoConLote("Auditoria traduccion", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(3));
        long id = jdbc.queryForObject("INSERT INTO traduccion(entidad,clave,campo,idioma,texto,estado,aprobada_en,version_original) VALUES ('PRODUCTO',?,'nombre','en','contenido uno','APROBADA',now(),1) RETURNING id", Long.class, "" + producto);
        jdbc.update("UPDATE traduccion SET texto='contenido dos', actualizado_en=now() WHERE id=?", id);
        jdbc.update("UPDATE traduccion SET texto='contenido tres', actualizado_en=now() WHERE id=?", id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE accion='TRADUCCION_APROBADA' AND referencia=? AND entidad='TRADUCCION' AND detalle->>'tipo'='PRODUCTO' AND detalle->>'versionOriginal'='1'", Long.class, "" + id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT string_agg(detalle::text,' ') FROM auditoria_evento WHERE accion='TRADUCCION_APROBADA' AND referencia=?", String.class, "" + id))
                .contains("PRODUCTO", "versionOriginal").doesNotContain("contenido uno", "contenido dos", "contenido tres");
    }
}
