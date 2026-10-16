package pe.ccpip.comision;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import pe.ccpip.comision.ComisionModelos.ComisionesPorPedido;
import pe.ccpip.comision.ComisionModelos.Estado;
import pe.ccpip.comision.ComisionModelos.NuevaComision;

/**
 * SA-02 (CCPIP-90): solo el superadministrador configura la comision. La consulta por pedido (CCPIP-91)
 * es de la administracion de la Camara: superadministrador y sub administradores.
 */
@RestController
@RequestMapping("/api/admin/comision")
public class ComisionController {

    private final ComisionService service;
    private final ComisionesPorPedidoService porPedido;

    public ComisionController(ComisionService service, ComisionesPorPedidoService porPedido) {
        this.service = service;
        this.porPedido = porPedido;
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @GetMapping
    public Estado estado() {
        return service.estado();
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Estado programar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                            @Valid @RequestBody NuevaComision nueva) {
        return service.programar(s.userId(), nueva);
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @PostMapping("/{id}/cancelar")
    public Estado cancelar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                           @PathVariable long id) {
        return service.cancelar(s.userId(), id);
    }

    @RolesPermitidos({Rol.SUB_ADMIN, Rol.SUPER_ADMIN})
    @GetMapping("/pedidos")
    public ComisionesPorPedido pedidos(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return porPedido.consultar(desde, hasta);
    }
}
