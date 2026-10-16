package pe.ccpip.interes;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.interes.InteresModelos.Interes;
import pe.ccpip.interes.InteresModelos.InteresOferta;
import pe.ccpip.interes.InteresModelos.NuevoInteres;
import pe.ccpip.productor.ProductorActual;

/**
 * CW-11 (CCPIP-105): el consumidor registra, cambia o retira su interes en una oferta. PW-01 (CCPIP-106):
 * el productor ve los interesados en sus ofertas. Siempre de la sesion, nunca por id de otra persona.
 */
@RestController
public class InteresController {

    private final InteresService service;
    private final ProductorActual productorActual;

    public InteresController(InteresService service, ProductorActual productorActual) {
        this.service = service;
        this.productorActual = productorActual;
    }

    /** El interes del comprador en la oferta; 204 si no tiene uno pendiente. */
    @RolesPermitidos(Rol.CONSUMIDOR)
    @GetMapping("/api/productos/{id}/interes")
    public ResponseEntity<Interes> consultar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                             @PathVariable long id) {
        return service.consultar(s.userId(), id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @RolesPermitidos(Rol.CONSUMIDOR)
    @PutMapping("/api/productos/{id}/interes")
    public Interes registrar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @PathVariable long id, @Valid @RequestBody NuevoInteres interes) {
        return service.registrar(s.userId(), id, interes.cantidad());
    }

    @RolesPermitidos(Rol.CONSUMIDOR)
    @DeleteMapping("/api/productos/{id}/interes")
    public ResponseEntity<Void> retirar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @PathVariable long id) {
        service.retirar(s.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/intereses")
    public List<InteresOferta> deProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return service.deProductor(productorActual.id(s));
    }
}
