package pe.ccpip.gobernanza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

@Transactional
class AuditoriaEventosTest extends IntegrationTest {
    @Autowired AuditoriaEventos auditoria;
    @Autowired JdbcTemplate jdbc;

    @Test
    void capturaNombreYRolsnapshotAlRegistrar() {
        Long id = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, "sudo@ccpip.test");
        String nombre = jdbc.queryForObject("SELECT nombre FROM usuario WHERE id = ?", String.class, id);
        String rol = jdbc.queryForObject("SELECT r.codigo FROM usuario u JOIN rol r ON r.id=u.rol_id WHERE u.id=?", String.class, id);
        auditoria.registrar(id, "ACTUALIZAR", "PEDIDO", "42", "OK", Map.of("estadoNuevo", "CONFIRMADO"));
        jdbc.update("UPDATE usuario SET nombre='Nuevo Nombre' WHERE id=?", id);
        jdbc.update("UPDATE usuario SET rol_id=(SELECT id FROM rol WHERE codigo <> ? LIMIT 1) WHERE id=?", rol, id);
        assertThat(jdbc.queryForMap("SELECT usuario_nombre, rol, actor_tipo FROM auditoria_evento ORDER BY id DESC LIMIT 1"))
                .containsEntry("usuario_nombre", nombre).containsEntry("rol", rol).containsEntry("actor_tipo", "USUARIO");
    }

    @Test
    void nullEsSistemaYAnonimoEsDistinto() {
        auditoria.registrar(null, "A", "E", "1", "OK", Map.of());
        auditoria.anonimo("A", "E", "2", "OK", Map.of());
        assertThat(jdbc.queryForList("SELECT actor_tipo FROM auditoria_evento ORDER BY id", String.class))
                .containsExactly("SISTEMA", "ANONIMO");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"password", "token", "numeroCuenta"})
    void detalleRechazaDatosPrivadosSinPersistirlos(String clavePrivada) {
        assertThatThrownBy(() -> auditoria.anonimo("A", "E", "3", "OK", Map.of(clavePrivada, "secreto")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento", Long.class)).isZero();
    }

    @Test
    void claveEventoDeduplica() {
        auditoria.registrar(null, "A", "E", "4", "OK", 8L, "corr", "una-clave", Map.of());
        auditoria.registrar(null, "A", "E", "4", "OK", 8L, "corr", "una-clave", Map.of());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento", Long.class)).isEqualTo(1);
    }

    @Test
    void tablaRechazaUpdateYDelete() {
        auditoria.registrar(null, "A", "E", "5", "OK", Map.of());
        assertThatThrownBy(() -> jdbc.update("UPDATE auditoria_evento SET accion='X'"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM auditoria_evento"))
                .isInstanceOf(RuntimeException.class);
    }
}
