package pe.ccpip.entrega;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.entrega.TarifasEnvioService.TarifaDistrito;

/** CCPIP-482: el superadministrador consulta y fija la tarifa de envio a domicilio de cada distrito. */
@RestController
@RequestMapping("/api/admin/tarifas-envio")
@RolesPermitidos(Rol.SUPER_ADMIN)
public class TarifasEnvioController {

    public record Tarifa(@NotNull(message = "Indica la tarifa")
                         @DecimalMin(value = "0.00", message = "La tarifa no puede ser negativa")
                         @DecimalMax(value = "999.99", message = "La tarifa es demasiado alta")
                         @Digits(integer = 3, fraction = 2, message = "Usa como máximo dos decimales") BigDecimal monto) {
    }

    private final TarifasEnvioService service;

    public TarifasEnvioController(TarifasEnvioService service) {
        this.service = service;
    }

    @GetMapping
    public List<TarifaDistrito> listar() {
        return service.listar();
    }

    @PutMapping("/{ubigeo}")
    public TarifaDistrito fijar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @PathVariable String ubigeo, @Valid @RequestBody Tarifa tarifa) {
        return service.fijar(ubigeo, tarifa.monto(), s.userId());
    }
}
