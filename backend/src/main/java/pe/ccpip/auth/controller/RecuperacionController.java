package pe.ccpip.auth.controller;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.Publico;
import pe.ccpip.auth.service.CorreoDeRecuperacion;
import pe.ccpip.auth.service.RecuperacionService;

/** CCPIP-33 (RF-08): recuperar la contrasena con un enlace de un solo uso que vence. */
@RestController
@RequestMapping("/api/auth")
public class RecuperacionController {

    public record SolicitudRequest(@NotBlank @Email String email) {
    }

    /** La nueva clave sigue las reglas del registro (RNF-01). */
    public record RestablecerRequest(
            @NotBlank String token,
            @NotBlank(message = "Escribe una contraseña")
            @Size(min = 8, max = 72, message = "Usa entre 8 y 72 caracteres")
            @Pattern(regexp = "^(?=.*\\p{Lu})(?=.*\\d).*$", message = "Incluye una mayúscula y un número")
            String password) {
    }

    private final RecuperacionService recuperacion;
    private final CorreoDeRecuperacion correo;

    public RecuperacionController(RecuperacionService recuperacion, CorreoDeRecuperacion correo) {
        this.recuperacion = recuperacion;
        this.correo = correo;
    }

    /** Siempre 202, exista o no la cuenta, para no revelar que correos estan registrados. */
    @Publico
    @PostMapping("/recuperar-contrasena")
    public ResponseEntity<Void> solicitar(
            @Valid @RequestBody SolicitudRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String idioma) {
        recuperacion.solicitar(request.email()).ifPresent(enlace -> correo.enviar(enlace, idioma));
        return ResponseEntity.accepted().build();
    }

    /** Cambia la clave y cierra las sesiones abiertas. No inicia sesion: devuelve el correo para ingresar. */
    @Publico
    @PostMapping("/restablecer-contrasena")
    public Map<String, String> restablecer(@Valid @RequestBody RestablecerRequest request) {
        return Map.of("email", recuperacion.restablecer(request.token(), request.password()));
    }
}
