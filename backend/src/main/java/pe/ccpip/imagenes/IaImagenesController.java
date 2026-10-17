package pe.ccpip.imagenes;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.productor.ProductorActual;

/**
 * IA sobre las fotos del productor (PW-04). Solo fotos propias y vigentes: la de otro productor
 * no existe para el (404). Nada de lo que responde bloquea la publicacion ni cambia una foto sin
 * una decision expresa.
 */
@RestController
@RequestMapping("/api/productor")
@RolesPermitidos(Rol.PRODUCTOR)
public class IaImagenesController {

    private final IaImagenService service;
    private final ProductorActual productorActual;

    public IaImagenesController(IaImagenService service, ProductorActual productorActual) {
        this.service = service;
        this.productorActual = productorActual;
    }

    /** CCPIP-144: pedir la validacion de correspondencia. 503 si la IA no esta disponible. */
    @PostMapping("/imagenes/{id}/validacion")
    public IaImagenService.Validacion validar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                              @PathVariable UUID id) {
        return service.validar(productorActual.id(s), s.userId(), id);
    }

    /** CCPIP-168: la recomendacion vigente de una foto. */
    @GetMapping("/imagenes/{id}/validacion")
    public IaImagenService.Validacion validacion(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @PathVariable UUID id) {
        return service.ultimaValidacion(productorActual.id(s), id);
    }

    /** CCPIP-168: las recomendaciones de todas las fotos de una publicacion. */
    @GetMapping("/imagenes/validaciones")
    public List<IaImagenService.Validacion> validaciones(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                         @RequestParam long productoId) {
        return service.validacionesDelProducto(productorActual.id(s), productoId);
    }

    /** CCPIP-176: pedir expresamente una version mejorada. La original no cambia. */
    @PostMapping("/imagenes/{id}/mejora")
    public ResponseEntity<IaImagenService.Comparacion> mejorar(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.mejorar(productorActual.id(s), s.userId(), id));
    }

    /** CCPIP-272: comparar la original con la mejorada pendiente. */
    @GetMapping("/imagenes/{id}/mejora")
    public IaImagenService.Comparacion comparar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                @PathVariable UUID id) {
        return service.comparacion(productorActual.id(s), id);
    }

    /** CCPIP-282: usar la mejorada. Devuelve la imagen nueva que toma el lugar de la original. */
    @PostMapping("/imagenes/{id}/mejora/aceptar")
    public ImagenService.Imagen aceptar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @PathVariable UUID id) {
        return service.aceptar(productorActual.id(s), s.userId(), id);
    }

    /** CCPIP-281: conservar la original. */
    @PostMapping("/imagenes/{id}/mejora/rechazar")
    public ResponseEntity<Void> rechazar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                         @PathVariable UUID id) {
        service.rechazar(productorActual.id(s), s.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /** La variante mejorada solo la ve su dueno; como todo /api, sale con no-store. */
    @GetMapping("/mejoras/{mejoraId}")
    public ResponseEntity<byte[]> verMejora(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                            @PathVariable UUID mejoraId, @RequestParam(required = false) String tam) {
        return service.contenidoMejora(productorActual.id(s), mejoraId, "min".equals(tam))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
