package pe.ccpip.catalogomaestro;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.catalogomaestro.UnidadesSolicitadasService.Aprobacion;
import pe.ccpip.catalogomaestro.UnidadesSolicitadasService.Estado;
import pe.ccpip.catalogomaestro.UnidadesSolicitadasService.NuevaSolicitud;
import pe.ccpip.catalogomaestro.UnidadesSolicitadasService.Rechazo;
import pe.ccpip.catalogomaestro.UnidadesSolicitadasService.Solicitud;
import pe.ccpip.productor.ProductorActual;

/**
 * Unidades de medida que piden los productores (CCPIP-119) y que la Camara aprueba (CCPIP-137) o rechaza
 * (CCPIP-353). Pedir es del productor, sobre su propia comunidad; decidir es del superadministrador, que
 * administra el catalogo cerrado (como en CatalogoMaestroController).
 */
@RestController
public class UnidadesSolicitadasController {

    private final UnidadesSolicitadasService service;
    private final ProductorActual actual;

    public UnidadesSolicitadasController(UnidadesSolicitadasService service, ProductorActual actual) {
        this.service = service;
        this.actual = actual;
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @PostMapping("/api/productor/unidades/solicitudes")
    @ResponseStatus(HttpStatus.CREATED)
    public Solicitud solicitar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @Valid @RequestBody NuevaSolicitud nueva) {
        return service.solicitar(actual.id(s), s.userId(), nueva);
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/unidades/solicitudes")
    public List<Solicitud> propias(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return service.deProductor(actual.id(s));
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @GetMapping("/api/admin/catalogo/unidades/solicitudes")
    public List<Solicitud> listar(@RequestParam(defaultValue = "PENDIENTE") Estado estado) {
        return service.listar(estado);
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @PostMapping("/api/admin/catalogo/unidades/solicitudes/{id}/aprobar")
    public Solicitud aprobar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @PathVariable long id, @Valid @RequestBody Aprobacion aprobacion) {
        return service.aprobar(id, s.userId(), aprobacion);
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @PostMapping("/api/admin/catalogo/unidades/solicitudes/{id}/rechazar")
    public Solicitud rechazar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                              @PathVariable long id, @Valid @RequestBody Rechazo rechazo) {
        return service.rechazar(id, s.userId(), rechazo);
    }
}
