package pe.ccpip.reportes;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.ReportesModelos.Reporte;

/**
 * Reportes de la plataforma (SA-05): solo el superadministrador. Son agregados de la operacion; no
 * exponen compradores ni el detalle comercial de cada productor (SA-08).
 */
@RolesPermitidos(Rol.SUPER_ADMIN)
@RestController
@RequestMapping("/api/admin/reportes")
public class ReportesController {

    private final ReportesService service;

    public ReportesController(ReportesService service) {
        this.service = service;
    }

    /** CCPIP-345 (RF-235): ventas acumuladas por periodo, con su desglose B2C y B2B. */
    @GetMapping("/ventas")
    public Reporte ventas(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                          @RequestParam(required = false) Agrupacion agrupacion) {
        return service.ventas(desde, hasta, agrupacion);
    }

    /** CCPIP-346 (RF-236): comisiones de la Camara acumuladas por periodo, con su desglose B2C y B2B. */
    @GetMapping("/comisiones")
    public Reporte comisiones(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                              @RequestParam(required = false) Agrupacion agrupacion) {
        return service.comisiones(desde, hasta, agrupacion);
    }

    /** CCPIP-93 (RF-71): ingresos (lo cobrado) acumulados por periodo, con su desglose B2C y B2B. */
    @GetMapping("/ingresos")
    public Reporte ingresos(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                            @RequestParam(required = false) Agrupacion agrupacion) {
        return service.ingresos(desde, hasta, agrupacion);
    }
}
