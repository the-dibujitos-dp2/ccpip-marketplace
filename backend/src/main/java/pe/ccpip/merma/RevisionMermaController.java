package pe.ccpip.merma;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.merma.RevisionMermaService.Aprobacion;
import pe.ccpip.merma.RevisionMermaService.Estado;
import pe.ccpip.merma.RevisionMermaService.Rechazo;
import pe.ccpip.merma.RevisionMermaService.Revision;
import pe.ccpip.merma.RevisionMermaService.Solicitud;

/**
 * CCPIP-120: revision de una merma registrada por error. El punto de acopio la pide sobre la merma de
 * sus puntos; el subadministrador la aprueba o la rechaza (decision del cliente del 05/11/2026). El
 * alcance lo aplica el servidor (RNF-05).
 */
@RestController
public class RevisionMermaController {

    private final RevisionMermaService revisiones;

    public RevisionMermaController(RevisionMermaService revisiones) {
        this.revisiones = revisiones;
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @PostMapping("/api/acopio/merma/{mermaId}/revision")
    public ResponseEntity<Revision> solicitar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                              @PathVariable long mermaId, @RequestBody Solicitud solicitud) {
        return ResponseEntity.status(HttpStatus.CREATED).body(revisiones.solicitar(s.userId(), mermaId, solicitud));
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @GetMapping("/api/acopio/merma/revisiones")
    public List<Revision> dePuntos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return revisiones.dePuntos(s.userId());
    }

    @RolesPermitidos(Rol.SUB_ADMIN)
    @GetMapping("/api/admin/merma/revisiones")
    public List<Revision> listar(@RequestParam(required = false) Estado estado) {
        return revisiones.listar(estado);
    }

    @RolesPermitidos(Rol.SUB_ADMIN)
    @PostMapping("/api/admin/merma/revisiones/{id}/aprobar")
    public Revision aprobar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                            @PathVariable long id, @RequestBody Aprobacion aprobacion) {
        return revisiones.aprobar(s.userId(), id, aprobacion);
    }

    @RolesPermitidos(Rol.SUB_ADMIN)
    @PostMapping("/api/admin/merma/revisiones/{id}/rechazar")
    public Revision rechazar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @PathVariable long id, @RequestBody Rechazo rechazo) {
        return revisiones.rechazar(s.userId(), id, rechazo);
    }
}
