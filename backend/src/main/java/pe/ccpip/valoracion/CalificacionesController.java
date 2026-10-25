package pe.ccpip.valoracion;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.Publico;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.valoracion.ValoracionModelos.CalificacionesPedido;
import pe.ccpip.valoracion.ValoracionModelos.MiCalificacionProducto;
import pe.ccpip.valoracion.ValoracionModelos.NuevaCalificacion;
import pe.ccpip.valoracion.ValoracionModelos.ResumenCalificaciones;

/**
 * CCPIP-81: el comprador califica los productos de su pedido recibido. CCPIP-82: el resumen publico de
 * calificaciones de la ficha, que no requiere sesion. CCPIP-412: si el comprador puede calificar el
 * producto desde la ficha. El acceso se declara en cada metodo.
 */
@RestController
public class CalificacionesController {

    private final CalificacionesService service;

    public CalificacionesController(CalificacionesService service) {
        this.service = service;
    }

    @RolesPermitidos(Rol.CONSUMIDOR)
    @GetMapping("/api/pedidos/{id}/calificaciones")
    public CalificacionesPedido delPedido(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                          @PathVariable long id) {
        return service.delPedido(s.userId(), id);
    }

    @RolesPermitidos(Rol.CONSUMIDOR)
    @PostMapping("/api/pedidos/{id}/calificaciones")
    @ResponseStatus(HttpStatus.CREATED)
    public CalificacionesPedido calificar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                          @PathVariable long id, @Valid @RequestBody NuevaCalificacion calificacion) {
        return service.calificar(s.userId(), id, calificacion);
    }

    @Publico
    @GetMapping("/api/productos/{id}/calificaciones")
    public ResumenCalificaciones resumen(@PathVariable long id) {
        return service.resumen(id);
    }

    /** CCPIP-412: si el comprador de la sesion puede calificar el producto desde la ficha. */
    @RolesPermitidos(Rol.CONSUMIDOR)
    @GetMapping("/api/productos/{id}/calificaciones/mia")
    public MiCalificacionProducto miCalificacion(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @PathVariable long id) {
        return service.miCalificacion(s.userId(), id);
    }
}
