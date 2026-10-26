package pe.ccpip.moderacion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.autorizacion.AvisoAutorizacion;
import pe.ccpip.moderacion.ModeracionModelos.Bandeja;
import pe.ccpip.moderacion.ModeracionModelos.Estado;
import pe.ccpip.moderacion.ModeracionModelos.Expediente;
import pe.ccpip.moderacion.ModeracionModelos.NuevaDecision;

/**
 * Moderacion de la publicacion de ofertas: el subadministrador revisa lo que los productores piden
 * publicar (CCPIP-46) y la aprueba con motivo (CCPIP-331). Es una accion de gobierno del sub
 * administrador, como las autorizaciones: el superadministrador recibe 403 (RNF-04). Cada decision se
 * avisa al productor una vez guardada.
 */
@RolesPermitidos(Rol.SUB_ADMIN)
@RestController
@RequestMapping("/api/admin/moderacion")
public class ModeracionController {

    private final ModeracionService service;
    private final AvisoAutorizacion aviso;

    public ModeracionController(ModeracionService service, AvisoAutorizacion aviso) {
        this.service = service;
        this.aviso = aviso;
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

    /** CCPIP-331: aprobar con motivo; la oferta pasa al catalogo. */
    @PostMapping("/{id}/aprobar")
    public Expediente aprobar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                              @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.aprobar(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.PUBLICACION_APROBADA,
                decision);
    }

    /** El servicio ya confirmo la transaccion: el aviso sale solo si la decision quedo guardada. */
    private Expediente avisar(Expediente exp, AvisoAutorizacion.Tipo tipo, NuevaDecision decision) {
        aviso.notificar(exp.productor().id(), tipo, exp.oferta().nombre(), decision.motivo().strip(), exp.id());
        return exp;
    }
}
