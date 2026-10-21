package pe.ccpip.liquidacion;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.liquidacion.LiquidacionModelos.Liquidacion;
import pe.ccpip.productor.ProductorActual;

/** CCPIP-92 (RF-70): la liquidacion del productor de la sesion (RNF-05); nunca recibe un id de productor. */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor/liquidaciones")
public class LiquidacionController {

    private final LiquidacionService service;
    private final ProductorActual actual;

    public LiquidacionController(LiquidacionService service, ProductorActual actual) {
        this.service = service;
        this.actual = actual;
    }

    @GetMapping
    public Liquidacion consultar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.consultar(actual.id(s), desde, hasta);
    }
}
