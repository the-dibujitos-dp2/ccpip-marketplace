package pe.ccpip.auth.config;

import java.time.Duration;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Cookie de sesion (ADR-05). HttpOnly: el JavaScript de la pagina no puede leer el token,
 * asi un XSS no lo roba. SameSite=Strict: el navegador no la envia desde otros sitios (CSRF).
 */
@Component
public class SessionCookies {

    public static final String NAME = "ccpip_sesion";
    private static final String PATH = "/api";

    private final boolean secure;

    public SessionCookies(@Value("${ccpip.auth.cookie-secure:true}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie create(String token) {
        // Sin Max-Age: muere al cerrar el navegador. El plazo real lo controla el servidor.
        return base(token).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}
