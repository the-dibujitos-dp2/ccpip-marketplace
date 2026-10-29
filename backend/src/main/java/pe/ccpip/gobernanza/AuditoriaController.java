package pe.ccpip.gobernanza;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.gobernanza.AuditoriaModelos.Entidad;
import pe.ccpip.gobernanza.AuditoriaModelos.Filtro;
import pe.ccpip.gobernanza.AuditoriaModelos.PaginaAuditoria;
import pe.ccpip.gobernanza.AuditoriaModelos.Resultado;

/**
 * CCPIP-97 (RF-84): el superadministrador consulta y filtra la auditoria de operaciones sensibles: quien
 * (usuario y rol), que accion, sobre que entidad, cuando y con que resultado. Solo lectura: no hay forma
 * de editar ni borrar un registro (RNF-07).
 */
@RolesPermitidos(Rol.SUPER_ADMIN)
@RestController
@Validated
public class AuditoriaController {

    private final AuditoriaService service;

    public AuditoriaController(AuditoriaService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/auditoria")
    public PaginaAuditoria auditoria(@RequestParam(defaultValue = "0") @Min(0) int pagina,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                     @RequestParam(required = false) @Size(max = 120) String usuario,
                                     @RequestParam(required = false) Rol rol,
                                     @RequestParam(required = false) @Size(max = 60) String accion,
                                     @RequestParam(required = false) Entidad entidad,
                                     @RequestParam(required = false) Resultado resultado) {
        return service.consultar(new Filtro(desde, hasta, usuario, rol, accion, entidad, resultado), pagina);
    }
}
