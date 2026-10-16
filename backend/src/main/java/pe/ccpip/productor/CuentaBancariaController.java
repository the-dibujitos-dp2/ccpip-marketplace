package pe.ccpip.productor;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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
import pe.ccpip.productor.CuentaBancariaModelos.Cuenta;
import pe.ccpip.productor.CuentaBancariaModelos.Datos;
import pe.ccpip.productor.CuentaBancariaModelos.Desactivacion;
import pe.ccpip.productor.CuentaBancariaModelos.Reactivacion;

/** PW-19 y PW-20: la cuenta bancaria de la sesion. Nunca se recibe un id de productor (RNF-05). */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor/cuenta-bancaria")
public class CuentaBancariaController {

    private final CuentaBancariaService service;
    private final ProductorActual productorActual;

    public CuentaBancariaController(CuentaBancariaService service, ProductorActual productorActual) {
        this.service = service;
        this.productorActual = productorActual;
    }

    @GetMapping
    public Cuenta consultar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return service.consultar(productorActual.id(s));
    }

    /** Bancos y cajas que se pueden elegir. */
    @GetMapping("/bancos")
    public List<String> bancos() {
        return CuentaBancariaModelos.BANCOS;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Cuenta registrar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                            @Valid @RequestBody Datos datos) {
        return service.registrar(productorActual.id(s), datos);
    }

    @PutMapping
    public Cuenta actualizar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @Valid @RequestBody Datos datos) {
        return service.actualizar(productorActual.id(s), datos);
    }

    @PostMapping("/desactivar")
    public Cuenta desactivar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @Valid @RequestBody Desactivacion desactivacion) {
        return service.desactivar(productorActual.id(s), desactivacion.motivo());
    }

    @PostMapping("/reactivar")
    public Cuenta reactivar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                            @Valid @RequestBody Reactivacion reactivacion) {
        return service.reactivar(productorActual.id(s), s.userId(), reactivacion.datos(), reactivacion.contrasena());
    }
}
