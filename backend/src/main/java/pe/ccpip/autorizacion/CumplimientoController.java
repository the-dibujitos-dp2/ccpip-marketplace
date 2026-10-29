package pe.ccpip.autorizacion;

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
import pe.ccpip.autorizacion.CumplimientoService.Cumplimiento;
import pe.ccpip.autorizacion.CumplimientoService.NuevaDeclaracion;
import pe.ccpip.productor.ProductorActual;

/** CCPIP-34 (RF-11): el productor declara por producto propio las reglas que cumple (RNF-05). */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor/productos/{productoId}/cumplimiento")
public class CumplimientoController {

    private final CumplimientoService service;
    private final ProductorActual actual;

    public CumplimientoController(CumplimientoService service, ProductorActual actual) {
        this.service = service;
        this.actual = actual;
    }

    @GetMapping
    public Cumplimiento consultar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @PathVariable long productoId) {
        return service.consultar(actual.id(s), productoId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Cumplimiento declarar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @PathVariable long productoId, @Valid @RequestBody NuevaDeclaracion nueva) {
        return service.declarar(actual.id(s), s.userId(), productoId, nueva);
    }
}
