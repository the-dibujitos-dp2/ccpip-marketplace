package pe.ccpip.auth.filter;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pe.ccpip.auth.config.SessionCookies;
import pe.ccpip.auth.service.SessionService;

/**
 * Identifica la sesion de cada peticion a /api a partir de la cookie, pero no rechaza nada:
 * quien puede llamar a cada endpoint lo decide {@link pe.ccpip.auth.access.AccessInterceptor}
 * segun sus anotaciones. Asi el catalogo publico y las rutas protegidas comparten el mismo filtro.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTRIBUTE = "ccpip.session";
    /** Motivo por el que no hay sesion: SESION_EXPIRADA o SIN_SESION. */
    public static final String SESSION_STATUS_ATTRIBUTE = "ccpip.session.status";

    private final SessionService sessionService;

    public SessionAuthFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod()) || !path(request).startsWith("/api/");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Ninguna respuesta de la API se guarda en cache: tras cerrar sesion,
        // el boton "atras" no puede mostrar datos del rol desde la cache del navegador.
        // Las imagenes publicas son la excepcion: no tienen datos de sesion y nunca cambian.
        if (!path(request).startsWith("/api/imagenes/")) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }

        SessionService.Validation validation = sessionService.validate(SessionCookies.read(request));
        switch (validation.status()) {
            case VALIDA -> request.setAttribute(SESSION_ATTRIBUTE, validation.session());
            case EXPIRADA -> request.setAttribute(SESSION_STATUS_ATTRIBUTE, "SESION_EXPIRADA");
            case INVALIDA -> request.setAttribute(SESSION_STATUS_ATTRIBUTE, "SIN_SESION");
        }
        chain.doFilter(request, response);
    }
}
