package pe.ccpip.autorizacion;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.autorizacion.EvidenciasFisicasService.EvidenciaFisica;
import pe.ccpip.autorizacion.EvidenciasFisicasService.NuevaRecepcion;
import pe.ccpip.autorizacion.EvidenciasFisicasService.NuevoEnvio;

/**
 * CCPIP-35 (RF-12) y CCPIP-329 (RF-219): envio y recepcion de evidencias fisicas en el expediente S-02.
 * Son del subadministrador, como las demas acciones sobre una solicitud (RevisionController); el
 * superadministrador recibe 403 (RNF-04).
 */
@RolesPermitidos(Rol.SUB_ADMIN)
@RestController
@RequestMapping("/api/admin/autorizaciones/{id}/evidencias-fisicas")
public class EvidenciasFisicasController {

    private final EvidenciasFisicasService service;

    public EvidenciasFisicasController(EvidenciasFisicasService service) {
        this.service = service;
    }

    @GetMapping
    public List<EvidenciaFisica> deAutorizacion(@PathVariable long id) {
        return service.deAutorizacion(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenciaFisica registrarEnvio(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                          @PathVariable long id, @Valid @RequestBody NuevoEnvio envio) {
        return service.registrarEnvio(id, s.userId(), envio);
    }

    @PostMapping("/{fisicaId}/recepcion")
    public EvidenciaFisica registrarRecepcion(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                              @PathVariable long id, @PathVariable long fisicaId,
                                              @Valid @RequestBody NuevaRecepcion recepcion) {
        return service.registrarRecepcion(id, fisicaId, s.userId(), recepcion);
    }
}
