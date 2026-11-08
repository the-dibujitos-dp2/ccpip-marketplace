package pe.ccpip.gobernanza;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.Map; import org.junit.jupiter.api.Test; import jakarta.servlet.http.Cookie;
import pe.ccpip.auth.config.SessionCookies; import pe.ccpip.support.IntegrationTest;
class AuditoriaSeguridadTest extends IntegrationTest {
 private static final String EMAIL="consumidor@ccpip.test";
 @Test void loginExitosoPersisteEventoConActorReal() throws Exception { long uid=jdbc.queryForObject("SELECT id FROM usuario WHERE correo=?",Long.class,EMAIL); mockMvc.perform(login(EMAIL,DEMO_PASSWORD)).andExpect(status().isOk()); assertEvento("USUARIO",uid,"LOGIN_EXITOSO","EXITOSA"); }
 @Test void loginFallidoEsAnonimoYNoGuardaCredenciales() throws Exception { mockMvc.perform(login("nadie@ccpip.test","secreto-no-registrar")).andExpect(status().isUnauthorized()); assertEvento("ANONIMO",null,"LOGIN_FALLIDO","ERROR"); assertThat(evento().toString()).doesNotContain("nadie@ccpip.test","secreto-no-registrar"); }
 @Test void loginBloqueadoEsAnonimo() throws Exception { jdbc.update("UPDATE usuario SET intentos_fallidos=5,bloqueado_hasta=now()+interval '10 minutes' WHERE correo=?",EMAIL); mockMvc.perform(login(EMAIL,DEMO_PASSWORD)).andExpect(status().isLocked()); assertEvento("ANONIMO",null,"LOGIN_BLOQUEADO","RECHAZADO"); }
 @Test void loginDenegadoPorCuentaNoConfirmadaEsAnonimo() throws Exception { mockMvc.perform(login("sin_confirmar@ccpip.test",DEMO_PASSWORD)).andExpect(status().isForbidden()); assertEvento("ANONIMO",null,"LOGIN_DENEGADO","RECHAZADO"); }
 @Test void logoutRegistraActorAutenticadoAntesDeInvalidarSesion() throws Exception { long uid=jdbc.queryForObject("SELECT id FROM usuario WHERE correo=?",Long.class,EMAIL); Cookie c=mockMvc.perform(login(EMAIL,DEMO_PASSWORD)).andExpect(status().isOk()).andReturn().getResponse().getCookie(SessionCookies.NAME); mockMvc.perform(post("/api/auth/logout").cookie(c)).andExpect(status().isNoContent()); assertEvento("USUARIO",uid,"LOGOUT","EXITOSA"); }
 private void assertEvento(String actor,Long uid,String accion,String resultado) { Map<String,Object> e=evento(); assertThat(e.get("actor_tipo")).isEqualTo(actor); assertThat(e.get("usuario_id")).isEqualTo(uid); assertThat(e.get("accion")).isEqualTo(accion); assertThat(e.get("entidad")).isEqualTo("SEGURIDAD"); assertThat(e.get("referencia")).isEqualTo(accion.startsWith("LOGIN")?"LOGIN":"LOGOUT"); assertThat(e.get("resultado")).isEqualTo(resultado); }
 private Map<String,Object> evento() { var rows=jdbc.queryForList("SELECT actor_tipo,usuario_id,accion,entidad,referencia,resultado,detalle FROM auditoria_evento ORDER BY id DESC LIMIT 1"); assertThat(rows).hasSize(1); return rows.get(0); }
}
