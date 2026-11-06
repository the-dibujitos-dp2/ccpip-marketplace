package pe.ccpip.entrega;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.entrega.CapacidadesService.CapacidadPunto;

/**
 * CCPIP-484: el superadministrador consulta, fija y quita la capacidad diaria de entregas a domicilio de
 * cada punto de acopio. Quitarla deja al punto sin limite.
 */
@RestController
@RequestMapping("/api/admin/capacidades-entrega")
@RolesPermitidos(Rol.SUPER_ADMIN)
public class CapacidadesController {

    public record Capacidad(@NotNull(message = "Indica la capacidad diaria")
                            @Min(value = 1, message = "La capacidad debe ser de al menos 1 pedido")
                            @Max(value = 10000, message = "La capacidad es demasiado alta") Integer maximoDiario) {
    }

    private final CapacidadesService service;

    public CapacidadesController(CapacidadesService service) {
        this.service = service;
    }

    @GetMapping
    public List<CapacidadPunto> listar() {
        return service.listar();
    }

    @PutMapping("/{puntoId}")
    public CapacidadPunto fijar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @PathVariable long puntoId, @Valid @RequestBody Capacidad capacidad) {
        return service.fijar(puntoId, capacidad.maximoDiario(), s.userId());
    }

    @DeleteMapping("/{puntoId}")
    public CapacidadPunto quitar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @PathVariable long puntoId) {
        return service.fijar(puntoId, null, s.userId());
    }
}
