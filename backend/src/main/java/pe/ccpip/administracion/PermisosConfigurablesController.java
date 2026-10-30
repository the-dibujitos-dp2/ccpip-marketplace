package pe.ccpip.administracion;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.ccpip.administracion.PermisosConfigurablesService.PermisosDeRol;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;

/**
 * CCPIP-296 (RF-172): el superadministrador consulta y modifica los permisos configurables de cada rol,
 * siempre con motivo. Las restricciones de gobernanza se muestran pero no se conceden.
 */
@RestController
@RequestMapping("/api/admin/permisos/configurables")
@RolesPermitidos(Rol.SUPER_ADMIN)
public class PermisosConfigurablesController {

    public record Cambio(@NotNull(message = "Indica si se concede o se revoca") Boolean concedido,
                         @NotBlank(message = "Indica el motivo del cambio")
                         @Size(max = 300, message = "El motivo es demasiado largo") String motivo) {
    }

    private final PermisosConfigurablesService service;

    public PermisosConfigurablesController(PermisosConfigurablesService service) {
        this.service = service;
    }

    @GetMapping
    public List<PermisosDeRol> listar() {
        return service.listar();
    }

    @PutMapping("/{rol}/{codigo}")
    public PermisosDeRol cambiar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @PathVariable Rol rol, @PathVariable String codigo, @Valid @RequestBody Cambio cambio) {
        return service.cambiar(rol, codigo, cambio.concedido(), cambio.motivo(), s.userId());
    }
}
