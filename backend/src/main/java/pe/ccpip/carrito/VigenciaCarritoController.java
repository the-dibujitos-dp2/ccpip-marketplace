package pe.ccpip.carrito;

import org.springframework.web.bind.annotation.GetMapping;
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
import pe.ccpip.carrito.VigenciaCarritoService.Vigencia;

/**
 * SA-02 "Parametros de la plataforma" (CCPIP-142, RF-109): solo el superadministrador consulta y
 * cambia la vida del carrito, como la comision (CCPIP-90).
 */
@RolesPermitidos(Rol.SUPER_ADMIN)
@RestController
@RequestMapping("/api/admin/parametros/vigencia-carrito")
public class VigenciaCarritoController {

    /**
     * Horas enteras. Los limites solo atajan errores de digitacion (de una hora a 30 dias); el valor lo
     * decide la Camara.
     */
    public record NuevaVigencia(@NotNull(message = "Indica cuántas horas se guarda el carrito")
                                @Min(value = 1, message = "El carrito se guarda al menos una hora")
                                @Max(value = 720, message = "El carrito se guarda como máximo 30 días (720 horas)")
                                Integer horas) {
    }

    private final VigenciaCarritoService service;

    public VigenciaCarritoController(VigenciaCarritoService service) {
        this.service = service;
    }

    @GetMapping
    public Vigencia consultar() {
        return service.vigente();
    }

    @PutMapping
    public Vigencia configurar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @Valid @RequestBody NuevaVigencia nueva) {
        return service.configurar(s.userId(), nueva.horas());
    }
}
