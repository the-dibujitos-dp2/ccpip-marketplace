package pe.ccpip.productor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;

@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor/tienda")
public class TiendaController {

    private final TiendaService service;
    private final ProductorActual productorActual;

    public TiendaController(TiendaService service, ProductorActual productorActual) {
        this.service = service;
        this.productorActual = productorActual;
    }

    @GetMapping
    public TiendaService.TiendaEdicion obtener(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session) {
        return service.obtener(productorActual.id(session));
    }

    @PutMapping
    public TiendaService.TiendaEdicion guardar(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session,
            @Valid @RequestBody TiendaService.DatosTienda datos) {
        return service.guardar(productorActual.id(session), datos);
    }

    @PostMapping("/publicar")
    public TiendaService.TiendaEdicion publicar(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session) {
        return service.publicar(productorActual.id(session));
    }
}
