package pe.ccpip.auth.controller;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.Publico;
import pe.ccpip.auth.service.CorreoDeVerificacion;
import pe.ccpip.auth.service.RegistroService;
import pe.ccpip.auth.service.RegistroService.EnlaceEmitido;
import pe.ccpip.auth.service.RegistroService.NuevaCuenta;

/**
 * CCPIP-26: registro del consumidor (pantalla CW-08).
 * CCPIP-328: verificacion del correo con enlace de un solo uso y reenvio del enlace (CW-14).
 */
@RestController
@RequestMapping("/api/auth")
public class RegistroController {

    /**
     * Formulario de CW-08. Sin genero ni fecha de nacimiento (RF-01). Nombres y apellidos se guardan
     * por separado y juntos en usuario.nombre (CCPIP-113). Los terminos se simulan mientras no exista el texto aprobado.
     */
    public record RegistroRequest(
            @NotBlank(message = "Escribe tus nombres") @Size(max = 80, message = "Máximo 80 caracteres")
            String firstName,
            @NotBlank(message = "Escribe tus apellidos") @Size(max = 79, message = "Máximo 79 caracteres")
            String lastName,
            @NotBlank(message = "Escribe tu correo") @Email(message = "Escribe un correo válido")
            @Size(max = 255, message = "Máximo 255 caracteres")
            String email,
            @Pattern(regexp = "^$|^\\+?[0-9][0-9 ]{5,18}$", message = "Escribe un celular válido")
            String phone,
            @NotBlank(message = "Escribe una contraseña")
            @Size(min = 8, max = 72, message = "Usa entre 8 y 72 caracteres")
            @Pattern(regexp = "^(?=.*\\p{Lu})(?=.*\\d).*$", message = "Incluye una mayúscula y un número")
            String password,
            @AssertTrue(message = "Acepta los términos para continuar")
            boolean acceptTerms) {
    }

    public record VerificacionRequest(@NotBlank String token) {
    }

    public record ReenvioRequest(@NotBlank @Email String email) {
    }

    /** emailSent en false indica que la cuenta se creo pero el correo no salio; CW-14 ofrece reenviar. */
    public record RegistroView(String email, boolean emailSent) {
    }

    private final RegistroService registro;
    private final CorreoDeVerificacion correo;

    public RegistroController(RegistroService registro, CorreoDeVerificacion correo) {
        this.registro = registro;
        this.correo = correo;
    }

    @Publico
    @PostMapping("/registro")
    public ResponseEntity<RegistroView> registrar(
            @Valid @RequestBody RegistroRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String idioma) {
        EnlaceEmitido enlace = registro.registrar(new NuevaCuenta(request.firstName(), request.lastName(),
                request.email(), request.phone(), request.password()));
        boolean enviado = correo.enviar(enlace, idioma);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegistroView(enlace.correo(), enviado));
    }

    @Publico
    @PostMapping("/verificar-correo")
    public Map<String, String> verificar(@Valid @RequestBody VerificacionRequest request) {
        return Map.of("email", registro.verificar(request.token()));
    }

    /** Siempre 202, exista o no la cuenta, para no revelar que correos estan registrados. */
    @Publico
    @PostMapping("/verificar-correo/reenviar")
    public ResponseEntity<Void> reenviar(
            @Valid @RequestBody ReenvioRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String idioma) {
        registro.reemitir(request.email()).ifPresent(enlace -> correo.enviar(enlace, idioma));
        return ResponseEntity.accepted().build();
    }
}
