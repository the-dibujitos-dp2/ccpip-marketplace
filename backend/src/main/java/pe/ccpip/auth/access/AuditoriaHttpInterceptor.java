package pe.ccpip.auth.access;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.gobernanza.AuditoriaEventos;

/** CCPIP-509: audit security outcomes and report attachments, never request bodies or URI values. */
@Component
public class AuditoriaHttpInterceptor implements HandlerInterceptor {
    private final AuditoriaEventos eventos;
    private static final String AUDITADA = AuditoriaHttpInterceptor.class.getName();

    public AuditoriaHttpInterceptor(AuditoriaEventos eventos) { this.eventos = eventos; }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod) || request.getAttribute(AUDITADA) != null) return;
        request.setAttribute(AUDITADA, Boolean.TRUE);
        Object mapping = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(mapping instanceof String ruta) || ruta.length() > 120) return;
        int estado = response.getStatus();
        var sesion = (AuthenticatedSession) request.getAttribute(SessionAuthFilter.SESSION_ATTRIBUTE);
        String accion;
        String entidad = "SEGURIDAD";
        boolean anonimo = false;
        if (ruta.equals("/api/auth/recuperar-contrasena") && request.getMethod().equals("POST")) {
            accion = "RECUPERACION_SOLICITADA";
            anonimo = true;
        } else if (ruta.equals("/api/auth/restablecer-contrasena") && estado >= 400) {
            accion = "RESTABLECIMIENTO_RECHAZADO";
            anonimo = true;
        } else if ((estado == 401 || estado == 403) && !ruta.equals("/api/auth/login")
                && !ruta.equals("/api/auth/me")) {
            accion = "ACCESO_DENEGADO";
        } else {
            String disposicion = response.getHeader("Content-Disposition");
            if (disposicion == null || !disposicion.startsWith("attachment") || estado >= 400
                    || !(ruta.contains("/reportes/") || ruta.contains("/liquidaciones/") || ruta.contains("/merma/"))) return;
            accion = "REPORTE_DESCARGADO";
            entidad = "REPORTE";
        }
        String resultado = estado >= 400 ? "RECHAZADO" : "EXITOSA";
        if (sesion == null || anonimo) eventos.anonimo(accion, entidad, ruta, resultado, Map.of());
        else eventos.registrar(sesion.userId(), accion, entidad, ruta, resultado, Map.of());
    }
}
