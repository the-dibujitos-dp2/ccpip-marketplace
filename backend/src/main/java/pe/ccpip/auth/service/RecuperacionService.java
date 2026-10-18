package pe.ccpip.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.auth.service.RegistroService.EnlaceEmitido;
import pe.ccpip.auth.service.RegistroStore.Cuenta;
import pe.ccpip.auth.service.RegistroStore.Enlace;
import pe.ccpip.auth.service.RegistroStore.Proposito;
import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-33 (RF-08): recuperar la contrasena con un enlace de un solo uso que vence
 * ({@code ccpip.auth.enlace-recuperacion-vigencia}, 30 minutos por defecto). Solo se guarda el
 * SHA-256 del token (token_un_uso). Al cambiar la clave se cierran todas las sesiones abiertas y se
 * levanta el bloqueo por intentos fallidos. El correo se envia fuera de la transaccion.
 */
@Service
public class RecuperacionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RegistroStore store;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final Duration vigencia;
    private final Duration esperaReenvio;

    public RecuperacionService(RegistroStore store, JdbcTemplate jdbc, PasswordEncoder passwordEncoder, Clock clock,
                               @Value("${ccpip.auth.enlace-recuperacion-vigencia:30m}") Duration vigencia,
                               @Value("${ccpip.auth.enlace-reenvio-espera:60s}") Duration esperaReenvio) {
        this.store = store;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.vigencia = vigencia;
        this.esperaReenvio = esperaReenvio;
    }

    public Duration vigencia() {
        return vigencia;
    }

    /**
     * Emite un enlace si la cuenta existe y esta activa, y si no se pidio otro hace muy poco. En
     * cualquier otro caso no hace nada y quien llama responde igual, para no revelar que correos
     * tienen cuenta. Un enlace nuevo anula los anteriores sin usar.
     */
    @Transactional
    public Optional<EnlaceEmitido> solicitar(String correo) {
        if (correo == null || correo.isBlank()) {
            return Optional.empty();
        }
        Optional<Cuenta> cuenta = store.cuentaPorCorreo(correo.trim()).filter(Cuenta::activa);
        if (cuenta.isEmpty()) {
            return Optional.empty();
        }
        Cuenta c = cuenta.get();
        Instant ahora = clock.instant();
        boolean reciente = store.ultimoEnlace(c.id(), Proposito.RECUPERAR_CLAVE)
                .map(ultimo -> ahora.isBefore(ultimo.plus(esperaReenvio)))
                .orElse(false);
        if (reciente) {
            return Optional.empty();
        }
        return Optional.of(new EnlaceEmitido(c.correo(), c.nombre(), emitir(c.id(), ahora, vigencia)));
    }

    /**
     * CCPIP-301, 304: enlace de bienvenida para que una cuenta creada por la Camara elija su contrasena.
     * Es el mismo enlace de restablecer, con su propia vigencia; anula los anteriores sin usar. Devuelve el
     * token en claro solo para el correo: no se guarda ni se responde a la pantalla.
     */
    @Transactional
    public String emitirEnlaceDeAcceso(long usuarioId, Duration vigenciaAcceso) {
        return emitir(usuarioId, clock.instant(), vigenciaAcceso);
    }

    private String emitir(long usuarioId, Instant ahora, Duration plazo) {
        store.descartarEnlacesSinUsar(usuarioId, Proposito.RECUPERAR_CLAVE);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.guardarEnlace(usuarioId, Proposito.RECUPERAR_CLAVE, SessionService.hash(token), ahora, ahora.plus(plazo));
        return token;
    }

    /**
     * Cambia la contrasena con el enlace y lo inutiliza. Devuelve el correo de la cuenta para que la
     * persona ingrese con su nueva clave: el enlace no inicia sesion. Abrir el enlace prueba que la
     * persona recibe correo en esa direccion, asi que tambien queda confirmado.
     */
    @Transactional
    public String restablecer(String token, String nuevaContrasena) {
        if (nuevaContrasena.getBytes(StandardCharsets.UTF_8).length > RegistroService.MAX_BYTES_CONTRASENA) {
            throw ErrorNegocio.invalido("CONTRASENA_MUY_LARGA", "La contraseña es demasiado larga.");
        }
        Enlace enlace = Optional.ofNullable(token).filter(t -> !t.isBlank())
                .flatMap(t -> store.buscarEnlace(SessionService.hash(t.trim()), Proposito.RECUPERAR_CLAVE))
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.BAD_REQUEST, "ENLACE_INVALIDO",
                        "El enlace no es válido. Revisa que lo hayas copiado completo."));
        Instant ahora = clock.instant();
        if (enlace.usadoEn() != null) {
            throw usado();
        }
        if (!ahora.isBefore(enlace.expiraEn())) {
            throw new ErrorNegocio(HttpStatus.GONE, "ENLACE_VENCIDO", "El enlace venció. Pide uno nuevo con tu correo.");
        }
        if (!store.marcarUsado(enlace.id(), ahora)) {
            throw usado();
        }
        jdbc.update("""
                UPDATE usuario SET contrasena_hash = ?, intentos_fallidos = 0, bloqueado_hasta = NULL,
                       correo_confirmado = true, actualizado_en = now()
                WHERE id = ?""", passwordEncoder.encode(nuevaContrasena), enlace.usuarioId());
        // Quien tuviera la clave anterior pierde las sesiones que haya abierto con ella
        jdbc.update("DELETE FROM sesion WHERE usuario_id = ?", enlace.usuarioId());
        return enlace.correo();
    }

    private static ErrorNegocio usado() {
        return new ErrorNegocio(HttpStatus.GONE, "ENLACE_USADO", "Este enlace ya se usó. Si necesitas otro, pídelo con tu correo.");
    }
}
