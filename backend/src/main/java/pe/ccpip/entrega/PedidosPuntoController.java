package pe.ccpip.entrega;

import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    /** Lo que el administrador verifica en el mostrador (CCPIP-71): quien recoge y su celular. */
    public record Recojo(@NotBlank(message = "Indica quién recoge") @Size(max = 160, message = "El nombre es demasiado largo") String persona,
                         @NotBlank(message = "Indica el celular de contacto") @Size(max = 20, message = "El celular es demasiado largo") String celular) {
    }

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

    /** CCPIP-71: entrega un pedido listo al comprador o la persona autorizada, validando persona y celular. */
    @PostMapping("/{numero}/recojo")
    public PedidoPunto registrarRecojo(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                       @PathVariable String numero, @Valid @RequestBody Recojo recojo) {
        return pedidos.registrarRecojo(s.userId(), numeroValido(numero), recojo.persona(), recojo.celular());
    }

    /** Un numero de pedido solo tiene letras, digitos y guiones (p. ej. MC-00148); se busca sin distinguir mayusculas. */
    private static String numeroValido(String numero) {
        if (!numero.matches("[A-Za-z0-9-]{1,20}")) {
            throw ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe en tu punto de acopio.");
        }
        return numero.toUpperCase(Locale.ROOT);
    }
}
