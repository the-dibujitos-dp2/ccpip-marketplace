package pe.ccpip.auth.controller;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import pe.ccpip.auth.access.Autenticado;
import pe.ccpip.auth.access.Publico;
import pe.ccpip.auth.config.SessionCookies;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthUser;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.service.LoginAttemptService;
import pe.ccpip.auth.service.SessionService;
import pe.ccpip.auth.service.UserStore;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record UserView(long id, String email, String fullName, String role, String roleName) {
        static UserView of(AuthUser user) {
            return new UserView(user.id(), user.email(), user.fullName(), user.role().name(), user.roleName());
        }
    }

    /** El token no viaja en el cuerpo: va solo en la cookie HttpOnly. */
    public record SessionView(UserView user, long expiresInSeconds) {
    }

    private final UserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final SessionService sessionService;
    private final SessionCookies cookies;
    /** Para que un correo inexistente tarde lo mismo que una clave incorrecta. */
    private final String dummyHash;

    public AuthController(UserStore userStore, PasswordEncoder passwordEncoder,
                          LoginAttemptService loginAttemptService, SessionService sessionService,
                          SessionCookies cookies) {
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.sessionService = sessionService;
        this.cookies = cookies;
        this.dummyHash = passwordEncoder.encode("ccpip-sin-cuenta");
    }

    @Publico
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<AuthUser> found = userStore.findByEmail(request.email());
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            return invalidCredentials();
        }
        AuthUser user = found.get();

        Optional<Instant> lockedUntil = loginAttemptService.lockedUntil(user);
        if (lockedUntil.isPresent()) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                    "code", "CUENTA_BLOQUEADA",
                    "error", "Cuenta bloqueada temporalmente por intentos fallidos",
                    "lockedUntil", lockedUntil.get().toString()));
        }

        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            loginAttemptService.loginFailed(user);
            return invalidCredentials();
        }
        loginAttemptService.loginSucceeded(user);

        // Estos chequeos van despues de la contrasena para no revelar el estado de cuentas ajenas
        if (!user.active()) {
            return forbidden("CUENTA_INACTIVA", "Tu cuenta esta desactivada. Contacta a la CCPIP.");
        }
        if (!user.emailConfirmed()) {
            return forbidden("CORREO_NO_CONFIRMADO", "Confirma tu correo antes de ingresar.");
        }

        String token = sessionService.create(user.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.create(token).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SessionView(UserView.of(user), sessionService.timeout().toSeconds()));
    }

    /** Siempre responde 204 y borra la cookie, aunque la sesion ya hubiera expirado. */
    @Publico
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessionService.invalidate(SessionCookies.read(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    @Autenticado
    @GetMapping("/me")
    public ResponseEntity<SessionView> me(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session) {
        return userStore.findById(session.userId())
                .map(user -> new SessionView(UserView.of(user), sessionService.timeout().toSeconds()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private static ResponseEntity<Map<String, String>> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "code", "CREDENCIALES_INVALIDAS",
                "error", "Correo o contrasena incorrectos"));
    }

    private static ResponseEntity<Map<String, String>> forbidden(String code, String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", code, "error", message));
    }
}
