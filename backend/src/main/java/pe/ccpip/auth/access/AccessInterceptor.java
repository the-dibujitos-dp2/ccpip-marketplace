package pe.ccpip.auth.access;

import java.io.IOException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;

/**
 * CCPIP-151: cada endpoint de /api declara quien puede llamarlo con {@link Publico},
 * {@link Autenticado} o {@link RolesPermitidos}. Un endpoint sin declaracion se niega
 * (falla cerrado). Todo acceso denegado queda en el log con usuario, rol y ruta.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AccessInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (has(method, Publico.class)) {
            return true;
        }

        AuthenticatedSession session =
                (AuthenticatedSession) request.getAttribute(SessionAuthFilter.SESSION_ATTRIBUTE);
        if (session == null) {
            // No deberia pasar: el filtro ya rechaza sin sesion. Se niega por si acaso.
            return deny(request, response, null, HttpServletResponse.SC_UNAUTHORIZED, "SIN_SESION");
        }
        if (has(method, Autenticado.class)) {
            return true;
        }

        RolesPermitidos roles = find(method, RolesPermitidos.class);
        if (roles == null) {
            log.error("Endpoint sin declaracion de acceso: {} {}", request.getMethod(), request.getRequestURI());
            return deny(request, response, session, HttpServletResponse.SC_FORBIDDEN, "SIN_PERMISO");
        }
        if (Arrays.asList(roles.value()).contains(session.role())) {
            return true;
        }
        return deny(request, response, session, HttpServletResponse.SC_FORBIDDEN, "SIN_PERMISO");
    }

    private static boolean deny(HttpServletRequest request, HttpServletResponse response,
                                AuthenticatedSession session, int status, String code) throws IOException {
        log.warn("Acceso denegado: usuario={} rol={} {} {}",
                session == null ? "-" : session.userId(),
                session == null ? "-" : session.role(),
                request.getMethod(), request.getRequestURI());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"error\":\"No tienes permiso para esta accion\"}");
        return false;
    }

    private static boolean has(HandlerMethod method, Class<? extends java.lang.annotation.Annotation> type) {
        return find(method, type) != null;
    }

    private static <A extends java.lang.annotation.Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }
}
