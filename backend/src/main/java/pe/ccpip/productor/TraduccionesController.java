package pe.ccpip.productor;

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
import pe.ccpip.productor.TraduccionesProductorService.Aprobar;
import pe.ccpip.productor.TraduccionesProductorService.TraduccionesContenido;

/**
 * CCPIP-147 y CCPIP-170: el productor consulta, pide y publica la traduccion de su producto (PW-04)
 * y de su tienda (PW-08). {@code idioma} es el de destino ("en").
 */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor")
public class TraduccionesController {

    private final TraduccionesProductorService service;
    private final ProductorActual productorActual;

    public TraduccionesController(TraduccionesProductorService service, ProductorActual productorActual) {
        this.service = service;
        this.productorActual = productorActual;
    }

    @GetMapping("/productos/{id}/traducciones/{idioma}")
    public TraduccionesContenido deProducto(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session,
            @PathVariable long id, @PathVariable String idioma) {
        return service.deProducto(productorActual.id(session), id, idioma);
    }

    /** Traduccion automatica; 503 si no hay proveedor, sin tocar el original. */
    @PostMapping("/productos/{id}/traducciones/{idioma}")
    public TraduccionesContenido traducirProducto(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session,
            @PathVariable long id, @PathVariable String idioma) {
        return service.traducirProducto(productorActual.id(session), id, idioma);
    }

    @PostMapping("/productos/{id}/traducciones/{idioma}/aprobar")
    public TraduccionesContenido aprobarProducto(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session,
            @PathVariable long id, @PathVariable String idioma, @Valid @RequestBody(required = false) Aprobar datos) {
        return service.aprobarProducto(productorActual.id(session), id, idioma, datos);
    }

    @GetMapping("/tienda/traducciones/{idioma}")
    public TraduccionesContenido deTienda(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session,
            @PathVariable String idioma) {
        return service.deTienda(productorActual.id(session), idioma);
    }

    @PostMapping("/tienda/traducciones/{idioma}")
    public TraduccionesContenido traducirTienda(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session,
            @PathVariable String idioma) {
        return service.traducirTienda(productorActual.id(session), idioma);
    }

    @PostMapping("/tienda/traducciones/{idioma}/aprobar")
    public TraduccionesContenido aprobarTienda(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session,
            @PathVariable String idioma, @Valid @RequestBody(required = false) Aprobar datos) {
        return service.aprobarTienda(productorActual.id(session), idioma, datos);
    }
}
