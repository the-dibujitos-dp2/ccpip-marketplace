package pe.ccpip.reportes;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.reportes.ExistenciasAgregadasService.Existencias;

/**
 * CCPIP-354 (RF-244): existencias agregadas de los puntos de acopio, solo para el superadministrador y
 * solo de lectura. El detalle por productor y lote sigue siendo del administrador de cada punto (SA-08).
 */
@RolesPermitidos(Rol.SUPER_ADMIN)
@RestController
public class ExistenciasAgregadasController {

    private final ExistenciasAgregadasService service;

    public ExistenciasAgregadasController(ExistenciasAgregadasService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/reportes/existencias")
    public Existencias consultar() {
        return service.consultar();
    }
}
