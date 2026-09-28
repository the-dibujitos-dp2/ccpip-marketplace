package pe.ccpip.auth.controller;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthUser;
import pe.ccpip.auth.service.LoginAttemptService;
import pe.ccpip.auth.service.SessionService;
import pe.ccpip.auth.service.UserStore;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record UserView(String id, String email, String fullName, String role) {
        static UserView of(AuthUser user) {
            return new UserView(user.id(), user.email(), user.fullName(), user.role());
        }
    }

    public record LoginResponse(String token, long expiresInSeconds, UserView user) {
    }

    private final UserStore userStore;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final SessionService sessionService;

    public AuthController(UserStore userStore, PasswordEncoder passwordEncoder,
                          LoginAttemptService loginAttemptService, SessionService sessionService) {
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<Instant> lockedUntil = loginAttemptService.lockedUntil(request.email());
        if (lockedUntil.isPresent()) {
            return ResponseEntity.status(HttpStatus.LOCKED).body(Map.of(
                    "error", "Cuenta bloqueada temporalmente por intentos fallidos",
                    "lockedUntil", lockedUntil.get().toString()));
        }

        Optional<AuthUser> user = userStore.findByEmail(request.email())
                .filter(u -> passwordEncoder.matches(request.password(), u.passwordHash()));
        if (user.isEmpty()) {
            loginAttemptService.loginFailed(request.email());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Correo o contrasena incorrectos"));
        }

        loginAttemptService.loginSucceeded(request.email());
        SessionService.Session session = sessionService.create(user.get().id());
        return ResponseEntity.ok(new LoginResponse(
                session.token(), sessionService.timeout().toSeconds(), UserView.of(user.get())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) SessionService.Session session) {
        sessionService.invalidate(session.token());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserView> me(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) SessionService.Session session) {
        return userStore.findById(session.userId())
                .map(UserView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
}
