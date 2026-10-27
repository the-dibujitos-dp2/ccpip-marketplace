package pe.ccpip.cobertura;

import java.util.List;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.cobertura.CoberturaModelos.CoberturaPunto;

/** CCPIP-401: consulta, nunca modifica la cobertura declarada de los puntos propios. */
@RolesPermitidos(Rol.ADMIN_PUNTO)
@Validated
@RestController
@RequestMapping("/api/acopio/coberturas")
public class CoberturasController {
    private final CoberturasService coberturas;
    public CoberturasController(CoberturasService coberturas) { this.coberturas = coberturas; }
    @GetMapping
    public List<CoberturaPunto> listar(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession sesion,
            @RequestParam(required = false) @Min(1) Long punto) {
        return coberturas.listar(sesion.userId(), punto);
    }
}
