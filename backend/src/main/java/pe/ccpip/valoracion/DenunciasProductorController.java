package pe.ccpip.valoracion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.productor.ProductorActual;
import pe.ccpip.valoracion.ValoracionModelos.DenunciaRecibida;
import pe.ccpip.valoracion.ValoracionModelos.DenunciasRecibidas;
import pe.ccpip.valoracion.ValoracionModelos.NuevaRespuesta;

/**
 * CCPIP-116: el productor consulta las denuncias recibidas, anonimizadas en el servidor. CCPIP-323: las
 * responde. El productor sale de la sesion, nunca de la peticion (RNF-05).
 */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor/denuncias")
public class DenunciasProductorController {

    private final ProductorActual actual;
    private final DenunciasService service;

    public DenunciasProductorController(ProductorActual actual, DenunciasService service) {
        this.actual = actual;
        this.service = service;
    }

    @GetMapping
    public DenunciasRecibidas recibidas(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return service.recibidas(actual.id(s));
    }

    @PostMapping("/{id}/respuesta")
    public DenunciaRecibida responder(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                      @PathVariable long id, @Valid @RequestBody NuevaRespuesta respuesta) {
        return service.responder(actual.id(s), s.userId(), id, respuesta.texto());
    }
}
