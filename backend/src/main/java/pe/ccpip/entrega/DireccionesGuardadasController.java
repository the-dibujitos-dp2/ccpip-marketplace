package pe.ccpip.entrega;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import pe.ccpip.entrega.EntregaModelos.DireccionGuardada;
import pe.ccpip.entrega.EntregaModelos.NuevaDireccion;

/** CCPIP-65: las direcciones de entrega habituales del comprador de la sesion. */
@RolesPermitidos(Rol.CONSUMIDOR)
@RestController
@RequestMapping("/api/cuenta/direcciones")
public class DireccionesGuardadasController {

    private final DireccionesGuardadasService service;

    public DireccionesGuardadasController(DireccionesGuardadasService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DireccionGuardada guardar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                     @Valid @RequestBody NuevaDireccion nueva) {
        return service.guardar(s.userId(), nueva);
    }

    @PutMapping("/{id}")
    public DireccionGuardada modificar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                       @PathVariable long id, @Valid @RequestBody NuevaDireccion nueva) {
        return service.modificar(s.userId(), id, nueva);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> retirar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @PathVariable long id) {
        service.retirar(s.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
