package pe.ccpip.valoracion;

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
import pe.ccpip.valoracion.MinimoCalificacionesService.Minimo;

/** SA-02 "Parametros de la plataforma": solo el superadministrador consulta y cambia el minimo (CCPIP-82). */
@RolesPermitidos(Rol.SUPER_ADMIN)
@RestController
@RequestMapping("/api/admin/parametros/minimo-calificaciones")
public class MinimoCalificacionesController {

    /** Los limites solo atajan errores de digitacion; el valor lo decide la Camara. */
    public record NuevoMinimo(@NotNull(message = "Indica cuántas calificaciones se necesitan")
                              @Min(value = 1, message = "Se necesita al menos una calificación")
                              @Max(value = 100, message = "Como máximo 100 calificaciones") Integer calificaciones) {
    }

    private final MinimoCalificacionesService service;

    public MinimoCalificacionesController(MinimoCalificacionesService service) {
        this.service = service;
    }

    @GetMapping
    public Minimo consultar() {
        return service.vigente();
    }

    @PutMapping
    public Minimo configurar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @Valid @RequestBody NuevoMinimo nuevo) {
        return service.configurar(s.userId(), nuevo.calificaciones());
    }
}
