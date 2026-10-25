package pe.ccpip.reportes.acopio;

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
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteSalidas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteVentas;

/**
 * Pantalla «Reportes» del administrador de punto de acopio: lo que paso en sus puntos por periodo. Solo el
 * administrador del punto, y solo con sus puntos (usuario_ubicacion, RNF-05).
 */
@RolesPermitidos(Rol.ADMIN_PUNTO)
@RestController
@RequestMapping("/api/acopio/reportes")
public class ReportesAcopioController {

    private final ReportesAcopioService service;

    public ReportesAcopioController(ReportesAcopioService service) {
        this.service = service;
    }

    /** CCPIP-117 (RF-65): entradas de existencias por periodo, con filtros de productor y producto. */
    @GetMapping("/entradas")
    public ReporteEntradas entradas(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                    @RequestParam(required = false) Agrupacion agrupacion,
                                    @RequestParam(required = false) Long productor,
                                    @RequestParam(required = false) Long producto) {
        return service.entradas(s.userId(), desde, hasta, agrupacion, productor, producto);
    }

    /** CCPIP-341 (RF-231): salidas de existencias por periodo, con las ventas atendidas y por entregar. */
    @GetMapping("/salidas")
    public ReporteSalidas salidas(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                  @RequestParam(required = false) Agrupacion agrupacion,
                                  @RequestParam(required = false) Long productor,
                                  @RequestParam(required = false) Long producto) {
        return service.salidas(s.userId(), desde, hasta, agrupacion, productor, producto);
    }

    /** CCPIP-342 (RF-232): ventas de sus puntos por periodo, con los pedidos atendidos y por entregar. */
    @GetMapping("/ventas")
    public ReporteVentas ventas(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                @RequestParam(required = false) Agrupacion agrupacion,
                                @RequestParam(required = false) Long productor,
                                @RequestParam(required = false) Long producto) {
        return service.ventas(s.userId(), desde, hasta, agrupacion, productor, producto);
    }
}
