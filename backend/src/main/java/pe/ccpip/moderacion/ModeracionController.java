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
import pe.ccpip.auth.access.Permiso;
import pe.ccpip.auth.access.PermisoConfigurable;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.autorizacion.AvisoAutorizacion;
import pe.ccpip.moderacion.ModeracionModelos.Bandeja;
import pe.ccpip.moderacion.ModeracionModelos.BandejaVideos;
import pe.ccpip.moderacion.ModeracionModelos.Estado;
import pe.ccpip.moderacion.ModeracionModelos.Expediente;
import pe.ccpip.moderacion.ModeracionModelos.ExpedienteVideo;
import pe.ccpip.moderacion.ModeracionModelos.NuevaDecision;

/**
 * Moderacion de la publicacion de ofertas: el subadministrador revisa lo que los productores piden
 * publicar (CCPIP-46) y la aprueba (CCPIP-331) o la rechaza (CCPIP-332), siempre con motivo. Los videos de
 * las tiendas pasan por la misma revision (CCPIP-118). Es una accion de gobierno del sub
 * administrador, como las autorizaciones: el superadministrador recibe 403 (RNF-04). Cada decision se
 * avisa al productor una vez guardada.
 */
@RolesPermitidos(Rol.SUB_ADMIN)
@RestController
@RequestMapping("/api/admin/moderacion")
public class ModeracionController {

    private final ModeracionService service;
    private final ModeracionVideosService videos;
    private final AvisoAutorizacion aviso;

    public ModeracionController(ModeracionService service, ModeracionVideosService videos, AvisoAutorizacion aviso) {
        this.service = service;
        this.videos = videos;
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
    @PermisoConfigurable(Permiso.MODERAR_PUBLICACIONES)
    @PostMapping("/{id}/aprobar")
    public Expediente aprobar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                              @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.aprobar(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.PUBLICACION_APROBADA,
                decision);
    }

    /** CCPIP-332: rechazar con motivo; la oferta vuelve a su estado anterior y el productor ve el motivo. */
    @PermisoConfigurable(Permiso.MODERAR_PUBLICACIONES)
    @PostMapping("/{id}/rechazar")
    public Expediente rechazar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.rechazar(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.PUBLICACION_RECHAZADA,
                decision);
    }

    /** El servicio ya confirmo la transaccion: el aviso sale solo si la decision quedo guardada. */
    private Expediente avisar(Expediente exp, AvisoAutorizacion.Tipo tipo, NuevaDecision decision) {
        aviso.notificar(exp.productor().id(), tipo, exp.oferta().nombre(), decision.motivo().strip(), exp.id());
        return exp;
    }

    // ---------- Videos de la tienda (CCPIP-118) ----------

    /** Los videos PENDIENTE (por defecto), APROBADA o RECHAZADA. */
    @GetMapping("/videos")
    public BandejaVideos videos(@RequestParam(defaultValue = "PENDIENTE") Estado estado) {
        return videos.bandeja(estado);
    }

    @GetMapping("/videos/{id}")
    public ExpedienteVideo video(@PathVariable long id) {
        return videos.expediente(id);
    }

    /** Aprobar o rechazar un video, con motivo; el productor recibe el aviso. */
    @PermisoConfigurable(Permiso.MODERAR_PUBLICACIONES)
    @PostMapping("/videos/{id}/{accion:aprobar|rechazar}")
    public ExpedienteVideo decidirVideo(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @PathVariable long id, @PathVariable String accion,
                                        @Valid @RequestBody NuevaDecision decision) {
        boolean aprobar = "aprobar".equals(accion);
        ExpedienteVideo v = aprobar ? videos.aprobar(id, s.userId(), decision.motivo())
                : videos.rechazar(id, s.userId(), decision.motivo());
        aviso.notificar(v.productor().id(), aprobar ? AvisoAutorizacion.Tipo.VIDEO_APROBADO : AvisoAutorizacion.Tipo.VIDEO_RECHAZADO,
                v.titulo(), decision.motivo().strip(), v.id());
        return v;
    }
}
