package pe.ccpip.cuentas;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.cuentas.CuentasModelos.Busqueda;
import pe.ccpip.cuentas.CuentasModelos.CambioCuenta;
import pe.ccpip.cuentas.CuentasModelos.CuentaDetalle;
import pe.ccpip.cuentas.CuentasModelos.EstadoCuenta;
import pe.ccpip.cuentas.CuentasModelos.Motivo;
import pe.ccpip.cuentas.CuentasModelos.NuevaCuenta;
import pe.ccpip.cuentas.CuentasModelos.Punto;
import pe.ccpip.cuentas.CuentasModelos.Resultado;
import pe.ccpip.cuentas.CuentasService.ConAviso;

/**
 * SA-03 y SA-04: cuentas de la plataforma. Buscar, consultar, suspender y reactivar es de la
 * administracion de la Camara segun el alcance de su rol (CCPIP-95, 307, 308); crear, modificar y
 * desactivar cuentas administrativas es solo del superadministrador (CCPIP-124, 125, 301 a 306).
 * El rol se valida aqui y el alcance en {@link CuentasService}, siempre en el servidor (RNF-05).
 */
@RestController
@RequestMapping("/api/admin/cuentas")
public class CuentasController {

    private final CuentasService service;
    private final CorreoDeCuenta correo;

    public CuentasController(CuentasService service, CorreoDeCuenta correo) {
        this.service = service;
        this.correo = correo;
    }

    @RolesPermitidos({Rol.SUB_ADMIN, Rol.SUPER_ADMIN})
    @GetMapping
    public Busqueda buscar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) Rol rol,
                           @RequestParam(required = false) EstadoCuenta estado,
                           @RequestParam(defaultValue = "0") int pagina) {
        return service.buscar(s, q, rol, estado, pagina);
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @GetMapping("/puntos")
    public List<Punto> puntos() {
        return service.puntosAsignables();
    }

    @RolesPermitidos({Rol.SUB_ADMIN, Rol.SUPER_ADMIN})
    @GetMapping("/{id}")
    public CuentaDetalle detalle(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @PathVariable long id) {
        return service.detalle(s, id);
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Resultado crear(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                           @Valid @RequestBody NuevaCuenta nueva) {
        return avisar(service.crear(s, nueva));
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @PutMapping("/{id}")
    public CuentaDetalle modificar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @PathVariable long id, @Valid @RequestBody CambioCuenta cambio) {
        return service.modificar(s, id, cambio);
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @PostMapping("/{id}/reenviar-acceso")
    public Resultado reenviarAcceso(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @PathVariable long id) {
        return avisar(service.reenviarAcceso(s, id));
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @PostMapping("/{id}/desactivar")
    public Resultado desactivar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @PathVariable long id, @Valid @RequestBody Motivo motivo) {
        return avisar(service.desactivar(s, id, motivo.motivo()));
    }

    @RolesPermitidos({Rol.SUB_ADMIN, Rol.SUPER_ADMIN})
    @PostMapping("/{id}/suspender")
    public Resultado suspender(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @PathVariable long id, @Valid @RequestBody Motivo motivo) {
        return avisar(service.suspender(s, id, motivo.motivo()));
    }

    @RolesPermitidos({Rol.SUB_ADMIN, Rol.SUPER_ADMIN})
    @PostMapping("/{id}/reactivar")
    public Resultado reactivar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @PathVariable long id, @Valid @RequestBody Motivo motivo) {
        return avisar(service.reactivar(s, id, motivo.motivo()));
    }

    /** El correo sale despues de confirmar la transaccion: un SMTP caido no deshace el cambio. */
    private Resultado avisar(ConAviso hecho) {
        return new Resultado(hecho.cuenta(), correo.enviar(hecho.aviso()));
    }
}
