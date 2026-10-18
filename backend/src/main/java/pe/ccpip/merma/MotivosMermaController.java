package pe.ccpip.merma;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.merma.MermaModelos.DatosMotivo;
import pe.ccpip.merma.MermaModelos.Motivo;

/** Catalogo de causas de merma del superadministrador (CCPIP-77, 298, 299 y 300). */
@RolesPermitidos(Rol.SUPER_ADMIN)
@RestController
@RequestMapping("/api/admin/motivos-merma")
public class MotivosMermaController {

    private final MotivosMermaService motivos;

    public MotivosMermaController(MotivosMermaService motivos) {
        this.motivos = motivos;
    }

    @GetMapping
    public List<Motivo> listar() {
        return motivos.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Motivo crear(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                        @Valid @RequestBody DatosMotivo datos) {
        return motivos.crear(s.userId(), datos);
    }

    @PutMapping("/{id}")
    public Motivo modificar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                            @PathVariable long id, @Valid @RequestBody DatosMotivo datos) {
        return motivos.modificar(s.userId(), id, datos);
    }

    @PostMapping("/{id}/desactivar")
    public Motivo desactivar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @PathVariable long id) {
        return motivos.desactivar(s.userId(), id);
    }

    @PostMapping("/{id}/activar")
    public Motivo activar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                          @PathVariable long id) {
        return motivos.activar(s.userId(), id);
    }
}
