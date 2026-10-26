package pe.ccpip.moderacion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.moderacion.ModeracionModelos.Bandeja;
import pe.ccpip.moderacion.ModeracionModelos.Estado;
import pe.ccpip.moderacion.ModeracionModelos.Expediente;

/**
 * Moderacion de la publicacion de ofertas: el subadministrador revisa lo que los productores piden
 * publicar (CCPIP-46). Es una accion de gobierno del sub administrador, como las autorizaciones: el
 * superadministrador recibe 403 (RNF-04).
 */
@RolesPermitidos(Rol.SUB_ADMIN)
@RestController
@RequestMapping("/api/admin/moderacion")
public class ModeracionController {

    private final ModeracionService service;

    public ModeracionController(ModeracionService service) {
        this.service = service;
    }

    /** CCPIP-46: las solicitudes PENDIENTE (por defecto), APROBADA o RECHAZADA. */
    @GetMapping
    public Bandeja bandeja(@RequestParam(defaultValue = "PENDIENTE") Estado estado) {
        return service.bandeja(estado);
    }

    /** CCPIP-46: la oferta, su autorizacion de venta con sus evidencias y el historial de solicitudes. */
    @GetMapping("/{id}")
    public Expediente expediente(@PathVariable long id) {
        return service.expediente(id);
    }
}
