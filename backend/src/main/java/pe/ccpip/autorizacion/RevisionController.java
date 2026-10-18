package pe.ccpip.autorizacion;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
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
import pe.ccpip.autorizacion.AutorizacionModelos.Analisis;
import pe.ccpip.autorizacion.AutorizacionModelos.Bandeja;
import pe.ccpip.autorizacion.AutorizacionModelos.Estado;
import pe.ccpip.autorizacion.AutorizacionModelos.Expediente;
import pe.ccpip.autorizacion.AutorizacionModelos.NuevaDecision;

/**
 * S-01 y S-02: el subadministrador revisa las solicitudes de autorizacion, pide el analisis preliminar
 * (CCPIP-50), aprueba (CCPIP-174, CCPIP-37) o rechaza (CCPIP-284, CCPIP-297) con motivo y revoca lo
 * aprobado (CCPIP-39). Es una accion de gobierno del sub administrador: el superadministrador supervisa
 * en modo lectura y aqui recibe 403 (RNF-04). Cada decision se avisa al productor una vez guardada.
 */
@RolesPermitidos(Rol.SUB_ADMIN)
@RestController
@RequestMapping("/api/admin/autorizaciones")
public class RevisionController {

    private final RevisionService service;
    private final AvisoAutorizacion aviso;

    public RevisionController(RevisionService service, AvisoAutorizacion aviso) {
        this.service = service;
        this.aviso = aviso;
    }

    @GetMapping
    public Bandeja bandeja(@RequestParam(defaultValue = "SOLICITADA") Estado estado) {
        return service.bandeja(estado);
    }

    @GetMapping("/{id}")
    public Expediente expediente(@PathVariable long id) {
        return service.expediente(id);
    }

    @PostMapping("/{id}/analisis")
    public Analisis analizar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @PathVariable long id) {
        return service.analizar(id, s.userId());
    }

    @PostMapping("/{id}/aprobar")
    public Expediente aprobar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                              @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.aprobar(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.APROBADA, decision);
    }

    @PostMapping("/{id}/rechazar")
    public Expediente rechazar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.rechazar(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.RECHAZADA, decision);
    }

    @PostMapping("/{id}/revocar")
    public Expediente revocar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                              @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.revocar(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.REVOCADA, decision);
    }

    /** El servicio ya confirmo la transaccion: el aviso sale solo si la decision quedo guardada. */
    private Expediente avisar(Expediente exp, AvisoAutorizacion.Tipo tipo, NuevaDecision decision) {
        aviso.notificar(exp.productor().id(), tipo, exp.producto(), decision.motivo().strip());
        return exp;
    }

    @GetMapping("/{id}/evidencias/{evidenciaId}/archivo")
    public ResponseEntity<byte[]> archivo(@PathVariable long id, @PathVariable UUID evidenciaId) {
        return EvidenciasController.servir(service.archivo(id, evidenciaId));
    }
}
