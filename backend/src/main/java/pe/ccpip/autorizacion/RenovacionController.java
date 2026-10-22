package pe.ccpip.autorizacion;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.autorizacion.RenovacionService.NuevaRenovacion;
import pe.ccpip.autorizacion.RenovacionService.Renovacion;
import pe.ccpip.productor.ProductorActual;

/**
 * CCPIP-321 (RF-205): solicitudes de renovacion de un certificado. Registrarlas es del subadministrador,
 * como las demas acciones sobre una autorizacion (RevisionController); el productor ve las de sus
 * productos (RNF-05).
 */
@RestController
public class RenovacionController {

    private final RenovacionService service;
    private final AvisoRenovacion aviso;
    private final ProductorActual actual;

    public RenovacionController(RenovacionService service, AvisoRenovacion aviso, ProductorActual actual) {
        this.service = service;
        this.aviso = aviso;
        this.actual = actual;
    }

    /** El servicio ya confirmo la transaccion: el aviso sale solo si la solicitud quedo guardada. */
    @RolesPermitidos(Rol.SUB_ADMIN)
    @PostMapping("/api/admin/autorizaciones/{id}/renovaciones")
    @ResponseStatus(HttpStatus.CREATED)
    public Renovacion registrar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @PathVariable long id, @Valid @RequestBody NuevaRenovacion nueva) {
        Renovacion renovacion = service.registrar(id, s.userId(), nueva);
        aviso.notificar(renovacion);
        return renovacion;
    }

    @RolesPermitidos(Rol.SUB_ADMIN)
    @GetMapping("/api/admin/autorizaciones/{id}/renovaciones")
    public List<Renovacion> deAutorizacion(@PathVariable long id) {
        return service.deAutorizacion(id);
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/productos/{productoId}/renovaciones")
    public List<Renovacion> deProducto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                       @PathVariable long productoId) {
        return service.deProducto(actual.id(s), productoId);
    }
}
