package pe.ccpip.cobertura;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.cobertura.CoberturaModelos.CoberturaPunto;

/**
 * CCPIP-401: consulta la cobertura declarada de los puntos propios. CCPIP-149: declara distritos del catalogo
 * territorial. CCPIP-403: desactiva una zona.
 */
@RolesPermitidos(Rol.ADMIN_PUNTO)
@RestController
@RequestMapping("/api/acopio/coberturas")
public class CoberturasController {
    private final CoberturasService coberturas;
    public CoberturasController(CoberturasService coberturas) { this.coberturas = coberturas; }

    /** CCPIP-149: el punto propio y los distritos (ubigeo INEI de seis digitos) que pasa a atender. */
    public record Declaracion(@NotNull(message = "Elige el punto de acopio") @Positive Long puntoId,
                              @NotEmpty(message = "Elige al menos un distrito")
                              @Size(max = 100, message = "Declara como maximo 100 distritos a la vez")
                              List<@NotNull @Pattern(regexp = "^[0-9]{6}$", message = "El ubigeo tiene seis digitos") String> ubigeos) { }

    @PostMapping
    public CoberturaPunto declarar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession sesion,
                                   @Valid @RequestBody Declaracion declaracion) {
        return coberturas.declarar(sesion.userId(), declaracion.puntoId(), declaracion.ubigeos());
    }
    @GetMapping
    public List<CoberturaPunto> listar(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession sesion,
            @RequestParam(required = false) Long punto) {
        if (punto != null && punto <= 0) {
            throw pe.ccpip.comun.ErrorNegocio.invalido("PUNTO_INVALIDO", "El punto debe ser un identificador positivo.");
        }
        return coberturas.listar(sesion.userId(), punto);
    }
    @PatchMapping("/{zonaId}/desactivar")
    public ResponseEntity<Void> desactivar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession sesion, @PathVariable long zonaId) {
        coberturas.desactivar(sesion.userId(), zonaId);
        return ResponseEntity.noContent().build();
    }
}
