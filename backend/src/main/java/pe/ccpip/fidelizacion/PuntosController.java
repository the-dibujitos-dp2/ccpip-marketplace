package pe.ccpip.fidelizacion;

import java.math.BigDecimal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.fidelizacion.BeneficiosModelos.MisPuntos;
import pe.ccpip.fidelizacion.FidelizacionModelos.PuntosPedido;

/**
 * El modulo de puntos del comprador: saldo y movimientos (CCPIP-173, 374), su nivel (CCPIP-88), el catalogo de cupones
 * y los que ya canjeo (CCPIP-89). Solo los propios: todo sale de la sesion.
 */
@RestController
@RequestMapping("/api/puntos")
@RolesPermitidos(Rol.CONSUMIDOR)
public class PuntosController {

    private static final BigDecimal MONTO_MAXIMO = BigDecimal.valueOf(10_000_000);

    private final CuponesService cupones;
    private final PuntosService puntos;

    public PuntosController(CuponesService cupones, PuntosService puntos) {
        this.cupones = cupones;
        this.puntos = puntos;
    }

    /** Cuantos puntos ganaria con una compra de {@code monto} soles en productos (carrito y pago). */
    @GetMapping("/estimacion")
    public PuntosPedido estimacion(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @RequestParam BigDecimal monto) {
        if (monto.signum() < 0 || monto.compareTo(MONTO_MAXIMO) > 0) {
            throw ErrorNegocio.invalido("MONTO_INVALIDO", "El monto de la compra no es válido.");
        }
        return puntos.estimar(s.userId(), monto);
    }

    @GetMapping
    public MisPuntos misPuntos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return cupones.misPuntos(s.userId());
    }

    @PostMapping("/cupones/{id}/canjear")
    public MisPuntos canjear(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @PathVariable long id) {
        return cupones.canjear(s.userId(), id);
    }
}
