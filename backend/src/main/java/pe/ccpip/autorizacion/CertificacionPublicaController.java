package pe.ccpip.autorizacion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.Publico;
import pe.ccpip.autorizacion.CertificacionPublicaService.Certificacion;

/** CCPIP-365 (RF-255): evidencias publicas de certificacion de un producto del catalogo, sin sesion. */
@Publico
@RestController
public class CertificacionPublicaController {

    private final CertificacionPublicaService service;

    public CertificacionPublicaController(CertificacionPublicaService service) {
        this.service = service;
    }

    @GetMapping("/api/productos/{id}/certificacion")
    public Certificacion consultar(@PathVariable long id) {
        return service.consultar(id);
    }
}
