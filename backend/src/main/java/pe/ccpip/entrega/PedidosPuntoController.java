package pe.ccpip.entrega;

import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.entrega.EntregaModelos.PedidoPunto;
import pe.ccpip.entrega.EntregaModelos.PedidosPunto;

/** Pedidos y estados de entrega del punto de acopio (Figma A-05). Solo el administrador de punto. */
@RolesPermitidos(Rol.ADMIN_PUNTO)
@RestController
@RequestMapping("/api/acopio/pedidos")
public class PedidosPuntoController {

    private final PedidosPuntoService pedidos;

    public PedidosPuntoController(PedidosPuntoService pedidos) {
        this.pedidos = pedidos;
    }

    @GetMapping
    public PedidosPunto listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return pedidos.listar(s.userId());
    }

    /** CCPIP-159: marca un pedido confirmado de su punto como listo para recojo. */
    @PostMapping("/{numero}/listo")
    public PedidoPunto marcarListo(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @PathVariable String numero) {
        return pedidos.marcarListo(s.userId(), numeroValido(numero));
    }

    /** Un numero de pedido solo tiene letras, digitos y guiones (p. ej. MC-00148); se busca sin distinguir mayusculas. */
    private static String numeroValido(String numero) {
        if (!numero.matches("[A-Za-z0-9-]{1,20}")) {
            throw ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe en tu punto de acopio.");
        }
        return numero.toUpperCase(Locale.ROOT);
    }
}
