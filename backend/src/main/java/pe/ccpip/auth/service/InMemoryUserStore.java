package pe.ccpip.auth.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import pe.ccpip.auth.model.AuthUser;

/**
 * Usuarios de prueba en memoria. Solo para desarrollo mientras no exista la base de datos.
 */
@Component
public class InMemoryUserStore implements UserStore {

    /** Contrasena comun de los usuarios de prueba (solo desarrollo). */
    public static final String DEMO_PASSWORD = "Demo1234!";

    private final Map<String, AuthUser> usersByEmail = new ConcurrentHashMap<>();

    public InMemoryUserStore(PasswordEncoder passwordEncoder) {
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        add(new AuthUser("u-1", "consumidor@ccpip.test", hash, "Consumidor de Prueba", "CONSUMIDOR"));
        add(new AuthUser("u-2", "productor@ccpip.test", hash, "Productor de Prueba", "PRODUCTOR"));
        add(new AuthUser("u-3", "admin@ccpip.test", hash, "Administrador de Prueba", "SUPER_ADMIN"));
    }

    private void add(AuthUser user) {
        usersByEmail.put(normalize(user.email()), user);
    }

    @Override
    public Optional<AuthUser> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(usersByEmail.get(normalize(email)));
    }

    @Override
    public Optional<AuthUser> findById(String id) {
        return usersByEmail.values().stream().filter(u -> u.id().equals(id)).findFirst();
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
