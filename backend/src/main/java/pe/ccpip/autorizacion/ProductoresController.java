package pe.ccpip.autorizacion;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.autorizacion.AutorizacionModelos.FichaProductor;
import pe.ccpip.autorizacion.AutorizacionModelos.NuevaDecision;
import pe.ccpip.autorizacion.AutorizacionModelos.ProductorFila;

/**
 * Productores y los productos que cada uno puede vender (CCPIP-292), habilitacion de su tienda
 * (CCPIP-291) y revocacion o restitucion de su venta (CCPIP-330). Como la revision de solicitudes, es
 * gobierno del sub administrador: el superadministrador recibe 403 (RNF-04).
 */
@RolesPermitidos(Rol.SUB_ADMIN)
@RestController
@RequestMapping("/api/admin/productores")
public class ProductoresController {

    private final ProductoresService service;
    private final AvisoAutorizacion aviso;

    public ProductoresController(ProductoresService service, AvisoAutorizacion aviso) {
        this.service = service;
        this.aviso = aviso;
    }

    @GetMapping
    public List<ProductorFila> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public FichaProductor ficha(@PathVariable long id) {
        return service.ficha(id);
    }

    @PostMapping("/{id}/habilitar-tienda")
    public FichaProductor habilitarTienda(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                          @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.habilitarTienda(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.HABILITAR_TIENDA, decision);
    }

    @PostMapping("/{id}/revocar")
    public FichaProductor revocar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.revocarVenta(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.REVOCAR_VENTA, decision);
    }

    @PostMapping("/{id}/restituir")
    public FichaProductor restituir(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @PathVariable long id, @Valid @RequestBody NuevaDecision decision) {
        return avisar(service.restituirVenta(id, s.userId(), decision.motivo()), AvisoAutorizacion.Tipo.RESTITUIR_VENTA, decision);
    }

    /** El servicio ya confirmo la transaccion: el aviso sale solo si la decision quedo guardada. */
    private FichaProductor avisar(FichaProductor ficha, AvisoAutorizacion.Tipo tipo, NuevaDecision decision) {
        aviso.notificar(ficha.id(), tipo, null, decision.motivo().strip(), ficha.decisiones().get(0).id());
        return ficha;
    }
}
