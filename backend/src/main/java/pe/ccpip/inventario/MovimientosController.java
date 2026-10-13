package pe.ccpip.inventario;

import java.util.Locale;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.inventario.InventarioModelos.Grupo;
import pe.ccpip.inventario.InventarioModelos.Movimientos;

/**
 * Movimientos del punto de acopio (Figma A-03). Solo el administrador de punto: el super
 * administrador no ve el stock de los acopios (Figma SA-08).
 */
@RolesPermitidos(Rol.ADMIN_PUNTO)
@Validated
@RestController
@RequestMapping("/api/acopio/movimientos")
public class MovimientosController {

    private final MovimientosService movimientos;

    public MovimientosController(MovimientosService movimientos) {
        this.movimientos = movimientos;
    }

    /**
     * CCPIP-158: incluye el movimiento SALIDA_VENTA que genero cada compra confirmada, con su numero
     * de pedido. {@code tipo} es la pestana: ENTRADAS, SALIDAS o AJUSTES; sin el, todos.
     */
    @GetMapping
    public Movimientos listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                              @RequestParam(required = false) String tipo,
                              @RequestParam(required = false) @Size(max = 20) String pedido,
                              @RequestParam(defaultValue = "0") @Min(0) int pagina) {
        Grupo grupo = null;
        if (tipo != null && !tipo.isBlank()) {
            try {
                grupo = Grupo.valueOf(tipo.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ErrorNegocio.invalido("TIPO_INVALIDO", "Tipo de movimiento desconocido.");
            }
        }
        return movimientos.listar(s.userId(), grupo, pedido, pagina);
    }
}
