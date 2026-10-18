package pe.ccpip.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.auth.service.RegistroStore.Cuenta;
import pe.ccpip.auth.service.RegistroStore.Enlace;
import pe.ccpip.auth.service.RegistroStore.Proposito;
import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-26: el consumidor crea su cuenta con nombre, correo y contrasena; nace sin confirmar.
 * CCPIP-328: confirma el correo con un enlace de un solo uso que vence.
 * El correo se envia fuera de la transaccion (lo hace quien llama), para no bloquear la base
 * mientras responde el servidor SMTP.
 */
@Service
public class RegistroService {

    /** Datos ya validados del formulario de CW-08. */
    public record NuevaCuenta(String nombres, String apellidos, String correo, String telefono, String contrasena) {
    }

    /** Enlace recien emitido. El token en claro solo existe aqui, para armar el correo. */
    public record EnlaceEmitido(String correo, String nombre, String token) {
    }

    /** BCrypt solo considera los primeros 72 bytes; una clave mas larga se rechaza en vez de truncarse. */
    static final int MAX_BYTES_CONTRASENA = 72;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegistroStore store;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration vigencia;
    private final Duration esperaReenvio;

    public RegistroService(RegistroStore store, PasswordEncoder passwordEncoder, Clock clock,
                           @Value("${ccpip.auth.enlace-verificacion-vigencia:24h}") Duration vigencia,
                           @Value("${ccpip.auth.enlace-reenvio-espera:60s}") Duration esperaReenvio) {
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.vigencia = vigencia;
        this.esperaReenvio = esperaReenvio;
    }

    public Duration vigencia() {
        return vigencia;
    }

    @Transactional
    public EnlaceEmitido registrar(NuevaCuenta datos) {
        if (datos.contrasena().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES_CONTRASENA) {
            throw ErrorNegocio.invalido("CONTRASENA_MUY_LARGA", "La contraseña es demasiado larga.");
        }
        String correo = datos.correo().trim().toLowerCase(Locale.ROOT);
        String nombres = espacios(datos.nombres());
        String apellidos = espacios(datos.apellidos());
        String nombre = (nombres + " " + apellidos).trim();
        String telefono = datos.telefono() == null || datos.telefono().isBlank() ? null : espacios(datos.telefono());
        long id;
        try {
            id = store.crearConsumidor(correo, passwordEncoder.encode(datos.contrasena()), nombres, apellidos, telefono);
        } catch (DuplicateKeyException e) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CORREO_YA_REGISTRADO",
                    "Ya existe una cuenta con ese correo. Inicia sesión o pide un nuevo enlace de verificación.");
        }
        return new EnlaceEmitido(correo, nombre, emitir(id));
    }

    /**
     * Confirma el correo de la cuenta del enlace y lo inutiliza. Devuelve el correo confirmado.
     * El enlace no inicia sesion: la persona ingresa con su contrasena (CW-14).
     */
    @Transactional
    public String verificar(String token) {
        Enlace enlace = Optional.ofNullable(token).filter(t -> !t.isBlank())
                .flatMap(t -> store.buscarEnlace(SessionService.hash(t.trim()), Proposito.VERIFICAR_CORREO))
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.BAD_REQUEST, "ENLACE_INVALIDO",
                        "El enlace no es válido. Revisa que lo hayas copiado completo."));
        Instant ahora = clock.instant();
        if (enlace.usadoEn() != null) {
            throw usado();
        }
        if (!ahora.isBefore(enlace.expiraEn())) {
            throw new ErrorNegocio(HttpStatus.GONE, "ENLACE_VENCIDO",
                    "El enlace venció. Pide uno nuevo con tu correo.");
        }
        if (!store.marcarUsado(enlace.id(), ahora)) {
            throw usado();
        }
        store.confirmarCorreo(enlace.usuarioId());
        return enlace.correo();
    }

    /**
     * Emite un enlace nuevo si la cuenta existe, esta activa y aun no confirma su correo, y si no
     * se emitio otro hace muy poco. En cualquier otro caso no hace nada, y quien llama responde igual,
     * para no revelar que correos tienen cuenta.
     */
    @Transactional
    public Optional<EnlaceEmitido> reemitir(String correo) {
        if (correo == null || correo.isBlank()) {
            return Optional.empty();
        }
        Optional<Cuenta> cuenta = store.cuentaPorCorreo(correo.trim())
                .filter(Cuenta::activa)
                .filter(c -> !c.correoConfirmado());
        if (cuenta.isEmpty()) {
            return Optional.empty();
        }
        Cuenta c = cuenta.get();
        Instant ahora = clock.instant();
        boolean reciente = store.ultimoEnlace(c.id(), Proposito.VERIFICAR_CORREO)
                .map(ultimo -> ahora.isBefore(ultimo.plus(esperaReenvio)))
                .orElse(false);
        if (reciente) {
            return Optional.empty();
        }
        store.descartarEnlacesSinUsar(c.id(), Proposito.VERIFICAR_CORREO);
        return Optional.of(new EnlaceEmitido(c.correo(), c.nombre(), emitir(c.id())));
    }

    private String emitir(long usuarioId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant ahora = clock.instant();
        store.guardarEnlace(usuarioId, Proposito.VERIFICAR_CORREO, SessionService.hash(token), ahora,
                ahora.plus(vigencia));
        return token;
    }

    private static ErrorNegocio usado() {
        return new ErrorNegocio(HttpStatus.GONE, "ENLACE_USADO",
                "Este enlace ya se usó. Si ya verificaste tu correo, inicia sesión.");
    }

    /** Quita espacios sobrantes: "  Lucía   María " queda "Lucía María". */
    private static String espacios(String texto) {
        return texto.trim().replaceAll("\\s+", " ");
    }
}
