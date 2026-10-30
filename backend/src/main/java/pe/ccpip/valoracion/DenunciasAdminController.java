package pe.ccpip.valoracion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.Permiso;
import pe.ccpip.auth.access.PermisoConfigurable;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.valoracion.ValoracionModelos.BandejaDenuncias;
import pe.ccpip.valoracion.ValoracionModelos.CambioEstado;
import pe.ccpip.valoracion.ValoracionModelos.DenunciaAdmin;
import pe.ccpip.valoracion.ValoracionModelos.ExpedienteProductor;

/**
 * CCPIP-84: denuncias contra productores, separadas de los reportes analiticos del tablero. El
 * superadministrador las consulta en modo lectura; solo el subadministrador cambia su estado (RNF-04).
 */
@RolesPermitidos({Rol.SUB_ADMIN, Rol.SUPER_ADMIN})
@RestController
@RequestMapping("/api/admin/denuncias")
public class DenunciasAdminController {

    private final DenunciasService service;

    public DenunciasAdminController(DenunciasService service) {
        this.service = service;
    }

    @GetMapping
    public BandejaDenuncias bandeja() {
        return service.bandeja();
    }

    @GetMapping("/productores/{productorId}")
    public ExpedienteProductor expediente(@PathVariable long productorId) {
        return service.expediente(productorId);
    }

    @RolesPermitidos(Rol.SUB_ADMIN)
    @PermisoConfigurable(Permiso.GESTIONAR_DENUNCIAS)
    @PostMapping("/{id}/estado")
    public DenunciaAdmin cambiarEstado(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                       @PathVariable long id, @Valid @RequestBody CambioEstado cambio) {
        return service.cambiarEstado(s.userId(), id, cambio);
    }
}
