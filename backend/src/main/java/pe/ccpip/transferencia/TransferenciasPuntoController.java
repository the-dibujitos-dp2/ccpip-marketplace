package pe.ccpip.transferencia;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.transferencia.TransferenciaModelos.Transferencia;
import pe.ccpip.transferencia.TransferenciaModelos.TransferenciasPunto;

/**
 * Transferencias del punto de acopio (CCPIP-278, RF-178; Figma A-01 y A-03 "Transferencias por
 * recibir"). Solo el administrador de punto, y solo de los puntos que tiene asignados (RNF-05).
 */
@RolesPermitidos(Rol.ADMIN_PUNTO)
@RestController
@RequestMapping("/api/acopio/transferencias")
public class TransferenciasPuntoController {

    private final TransferenciaService transferencias;

    public TransferenciasPuntoController(TransferenciaService transferencias) {
        this.transferencias = transferencias;
    }

    @GetMapping
    public TransferenciasPunto listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return transferencias.listarPunto(s.userId());
    }

    /** El detalle con su historial de estados. */
    @GetMapping("/{id}")
    public Transferencia detalle(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @PathVariable long id) {
        return transferencias.detallePunto(s.userId(), id);
    }

    /** Registra la recepcion en el punto de destino: las unidades entran a un lote del punto. */
    @PostMapping("/{id}/recibir")
    public Transferencia recibir(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @PathVariable long id) {
        return transferencias.recibir(s.userId(), id);
    }
}
