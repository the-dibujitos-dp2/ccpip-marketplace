package pe.ccpip.gobernanza;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.support.IntegrationTest;

@Transactional
class AuditoriaAutomaticosTest extends IntegrationTest {
    @Test void avisoGuardaEstadosSinDestinatarioNiErrorLibre() {
        long uid=jdbc.queryForObject("SELECT id FROM usuario WHERE correo='consumidor@ccpip.test'",Long.class);
        long id=jdbc.queryForObject("INSERT INTO aviso(usuario_id,tipo,referencia_id) VALUES (?,'PEDIDO_LISTO',9109) RETURNING id",Long.class,uid);
        jdbc.update("UPDATE aviso SET estado='FALLIDO',error='secreto-no-exponer@correo.test' WHERE id=?",id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE referencia=? AND entidad='AVISO'",Long.class,""+id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT string_agg(detalle::text,'') FROM auditoria_evento WHERE referencia=? AND entidad='AVISO'",String.class,""+id))
            .doesNotContain("correo.test");
    }
    @Test void vencimientoDeCarritoEsSistemaYSinEventoPorNoop() {
        long uid=jdbc.queryForObject("SELECT id FROM usuario WHERE correo='consumidor@ccpip.test'",Long.class);
        long id=jdbc.queryForObject("INSERT INTO carrito(usuario_id) VALUES (?) ON CONFLICT(usuario_id) DO UPDATE SET vencido_en=NULL RETURNING id",Long.class,uid);
        jdbc.update("UPDATE carrito SET vencido_en=now() WHERE id=?",id);
        jdbc.update("UPDATE carrito SET vencido_en=vencido_en WHERE id=?",id);
        var fila=jdbc.queryForMap("SELECT actor_tipo,usuario_id FROM auditoria_evento WHERE accion='CARRITO_VENCIDO' AND referencia=?",""+id);
        assertThat(fila.get("actor_tipo")).isEqualTo("SISTEMA");
        assertThat(fila.get("usuario_id")).isNull();
    }
    @Test void pagoConservaCadaResultadoSinDatosDeTarjeta() {
        long pedido=jdbc.queryForObject("""
            INSERT INTO pedido(numero,usuario_id,modalidad,ubicacion_origen_id,total,estado,confirmado_en)
            SELECT 'AUD-PAGO-509',u.id,'RECOJO',p.id,20,'PENDIENTE_PAGO',NULL FROM usuario u,ubicacion p
            WHERE u.correo='consumidor@ccpip.test' AND p.nombre='Centro Nacional Lima' RETURNING pedido.id
            """,Long.class);
        long pago=jdbc.queryForObject("INSERT INTO pago(pedido_id,clave_idempotencia,monto,pasarela,marca_tarjeta) VALUES (?,gen_random_uuid(),20,'simulada','VISA') RETURNING id",Long.class,pedido);
        jdbc.update("UPDATE pago SET estado='RECHAZADO',resuelto_en=now(),mensaje_respuesta='mensaje privado' WHERE id=?",pago);
        assertThat(jdbc.queryForList("SELECT resultado FROM auditoria_evento WHERE clave_evento LIKE ? ORDER BY id",String.class,"PG-"+pago+"-%"))
            .containsExactly("PENDIENTE","RECHAZADO");
        assertThat(jdbc.queryForObject("SELECT string_agg(detalle::text,'') FROM auditoria_evento WHERE clave_evento LIKE ?",String.class,"PG-"+pago+"-%"))
            .doesNotContain("VISA","mensaje privado");
    }
    @Test void cambioDeClaveEInvalidacionNoGuardanHashes() throws Exception {
        var sesion=loginAs("consumidor@ccpip.test");
        long uid=jdbc.queryForObject("SELECT id FROM usuario WHERE correo='consumidor@ccpip.test'",Long.class);
        jdbc.update("UPDATE usuario SET contrasena_hash='hash-no-se-guarda' WHERE id=?",uid);
        long anteriores=jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE accion='SESION_INVALIDADA' AND referencia=?",Long.class,""+uid);
        int eliminadas=jdbc.update("DELETE FROM sesion WHERE usuario_id=?",uid);
        assertThat(eliminadas).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE accion='CONTRASENA_RESTABLECIDA' AND usuario_id=?",Long.class,uid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auditoria_evento WHERE accion='SESION_INVALIDADA' AND referencia=?",Long.class,""+uid)).isEqualTo(anteriores+eliminadas);
        assertThat(jdbc.queryForObject("SELECT string_agg(detalle::text,'') FROM auditoria_evento WHERE accion IN ('CONTRASENA_RESTABLECIDA','SESION_INVALIDADA')",String.class))
            .doesNotContain("hash-no-se-guarda",sesion.getValue());
    }
}
