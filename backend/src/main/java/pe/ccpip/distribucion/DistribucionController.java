package pe.ccpip.distribucion;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.distribucion.DistribucionModelos.PedidoDisponible;

/** CCPIP-107 (DW-01): los pedidos que el distribuidor puede asumir. */
@RolesPermitidos(Rol.DISTRIBUIDOR)
@RestController
@RequestMapping("/api/distribuidor/pedidos")
public class DistribucionController {

    private final DistribucionService distribucion;

    public DistribucionController(DistribucionService distribucion) {
        this.distribucion = distribucion;
    }

    /** Pedidos preparados disponibles, con origen, destino y antiguedad; {@code punto} filtra por punto de acopio. */
    @GetMapping("/disponibles")
    public List<PedidoDisponible> disponibles(@RequestParam(required = false) Long punto) {
        return distribucion.disponibles(punto);
    }
}
