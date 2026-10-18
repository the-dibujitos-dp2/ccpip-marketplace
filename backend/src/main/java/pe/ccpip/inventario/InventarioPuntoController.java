package pe.ccpip.inventario;

import java.util.List;

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
import pe.ccpip.inventario.InventarioModelos.FiltroEstado;
import pe.ccpip.inventario.InventarioModelos.InventarioPunto;
import pe.ccpip.inventario.InventarioModelos.LoteOpcion;

/**
 * Inventario del punto de acopio (Figma A-02, CCPIP-162). Solo el administrador de punto y solo de
 * los puntos que tiene asignados (RNF-05).
 */
@RolesPermitidos(Rol.ADMIN_PUNTO)
@Validated
@RestController
@RequestMapping("/api/acopio/inventario")
public class InventarioPuntoController {

    private final InventarioPuntoService inventario;

    public InventarioPuntoController(InventarioPuntoService inventario) {
        this.inventario = inventario;
    }

    /** Lotes por producto, productor y estado. {@code estado}: ACTIVOS (por defecto), AGOTADOS o TODOS. */
    @GetMapping
    public InventarioPunto listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @RequestParam(required = false) @Size(max = 100) String q,
                                  @RequestParam(required = false) Long productor,
                                  @RequestParam(defaultValue = "ACTIVOS") FiltroEstado estado,
                                  @RequestParam(defaultValue = "0") @Min(0) int pagina) {
        return inventario.listar(s.userId(), q, productor, estado, pagina);
    }

    /** CCPIP-121 y CCPIP-287: los lotes que el formulario de entrada y salida ofrece. */
    @GetMapping("/lotes")
    public List<LoteOpcion> lotes(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return inventario.lotes(s.userId());
    }
}
