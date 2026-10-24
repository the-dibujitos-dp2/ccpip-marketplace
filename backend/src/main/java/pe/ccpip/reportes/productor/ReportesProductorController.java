package pe.ccpip.reportes.productor;

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
import pe.ccpip.productor.ProductorActual;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.Modalidad;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteProduccion;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteVentas;

/**
 * Pantalla «Reportes» del productor: estadisticas de sus productos por periodo. Solo el productor y solo
 * con sus productos; el id de productor sale de la sesion, nunca de la pantalla (RNF-05).
 */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor/reportes")
public class ReportesProductorController {

    private final ReportesProductorService service;
    private final ProductorActual productorActual;

    public ReportesProductorController(ReportesProductorService service, ProductorActual productorActual) {
        this.service = service;
        this.productorActual = productorActual;
    }

    /** CCPIP-114 (RF-25): ventas de sus productos por periodo, con filtros de producto y modalidad. */
    @GetMapping("/ventas")
    public ReporteVentas ventas(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                @RequestParam(required = false) Agrupacion agrupacion,
                                @RequestParam(required = false) Long producto,
                                @RequestParam(required = false) Modalidad modalidad) {
        return service.ventas(productorActual.id(s), desde, hasta, agrupacion, producto, modalidad);
    }

    /** CCPIP-333 (RF-223): produccion de sus productos por periodo, con filtro de producto. */
    @GetMapping("/produccion")
    public ReporteProduccion produccion(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(required = false) Agrupacion agrupacion,
                                        @RequestParam(required = false) Long producto) {
        return service.produccion(productorActual.id(s), desde, hasta, agrupacion, producto);
    }
}
