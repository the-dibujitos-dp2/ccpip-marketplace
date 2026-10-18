package pe.ccpip.misdatos;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.misdatos.MisDatosService.MisDatos;

/**
 * CW-13 "Mis datos" del consumidor (CCPIP-400, CCPIP-113). Solo sus datos: el usuario sale de la
 * sesion. Las mismas reglas del registro (CW-08) para nombres, apellidos y celular.
 */
@RolesPermitidos(Rol.CONSUMIDOR)
@RestController
@RequestMapping("/api/cuenta/datos")
public class MisDatosController {

    /** Lo que se edita. El correo no: es el usuario para ingresar. */
    public record DatosEditables(
            @NotBlank(message = "Escribe tus nombres") @Size(max = 80, message = "Máximo 80 caracteres") String nombres,
            @NotBlank(message = "Escribe tus apellidos") @Size(max = 79, message = "Máximo 79 caracteres") String apellidos,
            @Pattern(regexp = "^$|^\\+?[0-9][0-9 ]{5,18}$", message = "Escribe un celular válido") String celular) {
    }

    private final MisDatosService service;

    public MisDatosController(MisDatosService service) {
        this.service = service;
    }

    @GetMapping
    public MisDatos consultar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return service.consultar(s.userId());
    }

    @PutMapping
    public MisDatos editar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                           @Valid @RequestBody DatosEditables datos) {
        return service.editar(s.userId(), datos.nombres(), datos.apellidos(), datos.celular());
    }
}
