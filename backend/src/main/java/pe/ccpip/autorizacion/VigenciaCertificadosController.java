package pe.ccpip.autorizacion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.autorizacion.VigenciaCertificadosService.Estado;
import pe.ccpip.autorizacion.VigenciaCertificadosService.Vigencia;

/**
 * CCPIP-184 (RF-149): la vigencia de los certificados de los productos autorizados, para el
 * subadministrador. Por defecto, los que vencen en los proximos 30 dias.
 */
@RolesPermitidos(Rol.SUB_ADMIN)
@RestController
public class VigenciaCertificadosController {

    private final VigenciaCertificadosService service;

    public VigenciaCertificadosController(VigenciaCertificadosService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/certificados")
    public Vigencia consultar(@RequestParam(defaultValue = "POR_VENCER") Estado estado,
                              @RequestParam(defaultValue = "30") int dias) {
        return service.consultar(estado, dias);
    }
}
