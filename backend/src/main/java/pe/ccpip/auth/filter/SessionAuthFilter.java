package pe.ccpip.auth.filter;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pe.ccpip.auth.service.SessionService;

/**
 * Protege todo /api/** con el header "Authorization: Bearer &lt;token&gt;".
 * Solo el login queda publico; logout y /me necesitan la sesion para saber que cerrar o mostrar.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTRIBUTE = "ccpip.session";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SessionService sessionService;

    public SessionAuthFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.OPTIONS.matches(request.getMethod())
                || !path.startsWith("/api/")
                || path.equals("/api/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<SessionService.Session> session = sessionService.validateAndTouch(extractToken(request));
        if (session.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Sesion invalida o expirada\"}");
            return;
        }
        request.setAttribute(SESSION_ATTRIBUTE, session.get());
        chain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
