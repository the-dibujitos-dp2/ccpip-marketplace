package pe.ccpip.valoracion;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.valoracion.ValoracionModelos.MiDenuncia;
import pe.ccpip.valoracion.ValoracionModelos.NuevaDenuncia;

/** CCPIP-83: el comprador presenta denuncias y sigue las suyas. Solo las propias (RNF-05). */
@RolesPermitidos(Rol.CONSUMIDOR)
@RestController
public class DenunciasController {

    private final DenunciasService service;

    public DenunciasController(DenunciasService service) {
        this.service = service;
    }

    @PostMapping("/api/denuncias")
    @ResponseStatus(HttpStatus.CREATED)
    public MiDenuncia registrar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @Valid @RequestBody NuevaDenuncia denuncia) {
        return service.registrar(s.userId(), denuncia);
    }

    @GetMapping("/api/denuncias")
    public List<MiDenuncia> misDenuncias(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return service.misDenuncias(s.userId());
    }
}
