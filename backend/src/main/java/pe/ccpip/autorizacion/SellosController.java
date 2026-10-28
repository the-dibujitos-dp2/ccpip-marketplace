package pe.ccpip.autorizacion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.Publico;
import pe.ccpip.autorizacion.SellosService.SellosProducto;

/** CCPIP-38 (RF-15): los sellos de un producto del catalogo y su respaldo, sin sesion. */
@Publico
@RestController
public class SellosController {

    private final SellosService service;

    public SellosController(SellosService service) {
        this.service = service;
    }

    @GetMapping("/api/productos/{id}/sellos")
    public SellosProducto consultar(@PathVariable long id) {
        return service.consultar(id);
    }
}
