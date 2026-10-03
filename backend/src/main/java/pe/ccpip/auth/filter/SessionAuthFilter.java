package pe.ccpip.auth.filter;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pe.ccpip.auth.config.SessionCookies;
import pe.ccpip.auth.service.SessionService;

/**
 * Protege todo /api/** con la cookie de sesion. Solo login y logout quedan fuera: el logout
 * debe poder limpiar la cookie aunque la sesion ya haya expirado.
 * Que rol puede llamar a cada endpoint lo decide {@link pe.ccpip.auth.access.AccessInterceptor}.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTRIBUTE = "ccpip.session";
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/login", "/api/auth/logout");

    private final SessionService sessionService;

    public SessionAuthFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || !path.startsWith("/api/")
                || PUBLIC_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Ninguna respuesta con datos de sesion se guarda en cache: tras cerrar sesion,
        // el boton "atras" no puede mostrar datos del rol desde la cache del navegador.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        SessionService.Validation validation = sessionService.validate(SessionCookies.read(request));
        switch (validation.status()) {
            case VALIDA -> {
                request.setAttribute(SESSION_ATTRIBUTE, validation.session());
                chain.doFilter(request, response);
            }
            case EXPIRADA -> reject(response, "SESION_EXPIRADA", "Tu sesion expiro por inactividad");
            case INVALIDA -> reject(response, "SIN_SESION", "Sesion invalida o inexistente");
        }
    }

    private static void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"error\":\"" + message + "\"}");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
