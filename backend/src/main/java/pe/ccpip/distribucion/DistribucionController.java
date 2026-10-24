package pe.ccpip.distribucion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.distribucion.DistribucionModelos.Disponibles;

/** CCPIP-107 (DW-01): los pedidos que el distribuidor puede asumir. */
@RolesPermitidos(Rol.DISTRIBUIDOR)
@RestController
@RequestMapping("/api/distribuidor/pedidos")
public class DistribucionController {

    private final DistribucionService distribucion;

    public DistribucionController(DistribucionService distribucion) {
        this.distribucion = distribucion;
    }

    /**
     * Una pagina de los pedidos preparados disponibles, con origen, destino y antiguedad; el que lleva mas
     * esperando va primero. {@code punto} filtra por punto de acopio; {@code cursor} es el {@code siguiente} de la
     * pagina anterior (422 {@code CURSOR_INVALIDO} si no lo es); {@code limite}, de 1 a 50 (20 por defecto).
     */
    @GetMapping("/disponibles")
    public Disponibles disponibles(@RequestParam(required = false) Long punto,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limite) {
        return distribucion.disponibles(punto, cursor, limite);
    }
}