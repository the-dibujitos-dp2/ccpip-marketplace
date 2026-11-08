package pe.ccpip.gobernanza;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

@Transactional
class AuditoriaGobernanzaSnapshotTest extends IntegrationTest {
    @Test void capturaFuentesGobernanza() {
        Integer triggers = jdbc.queryForObject("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE NOT t.tgisinternal AND t.tgname LIKE 'auditar_gobernanza_%'", Integer.class);
        assertThat(triggers).isEqualTo(14);
    }
    @Test void conservaRolAlMomentoDeRegistro() {
        long actor = jdbc.queryForObject("SELECT id FROM usuario WHERE correo='sudo@ccpip.test'", Long.class);
        jdbc.queryForObject("SELECT registrar_evento_auditoria(?, ?, ?, ?, ?, now(), NULL, NULL, '{}'::jsonb, 'EXITOSA')", Object.class, actor, "SNAPSHOT-TEST", "TEST", "TEST", "1");
        String rol = jdbc.queryForObject("SELECT rol FROM auditoria_evento WHERE clave_evento='SNAPSHOT-TEST'", String.class);
        jdbc.update("UPDATE usuario SET rol_id=(SELECT id FROM rol WHERE codigo='CONSUMIDOR') WHERE id=?", actor);
        assertThat(jdbc.queryForObject("SELECT rol FROM auditoria_evento WHERE clave_evento='SNAPSHOT-TEST'", String.class)).isEqualTo(rol);
    }
}
