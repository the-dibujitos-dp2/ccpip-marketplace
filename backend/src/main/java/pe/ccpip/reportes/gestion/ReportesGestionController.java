package pe.ccpip.reportes.gestion;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteProductores;
import pe.ccpip.reportes.gestion.GestionModelos.ReportePuntos;

/**
 * Reportes de gestion (SA-05, SA-10): solo el superadministrador, y solo lectura. Son agregados de la
 * operacion; no exponen compradores.
 */
@RolesPermitidos(Rol.SUPER_ADMIN)
@RestController
@RequestMapping("/api/admin/reportes")
public class ReportesGestionController {

    private final ReportesGestionService service;

    public ReportesGestionController(ReportesGestionService service) {
        this.service = service;
    }

    /** CCPIP-122 (RF-86): productores con mayores ventas en el rango, con su desglose B2C y B2B. */
    @GetMapping("/productores")
    public ReporteProductores productores(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.productores(desde, hasta);
    }

    /** CCPIP-355 (RF-245) y CCPIP-356 (RF-246): ventas y denuncias agregadas de cada punto de acopio en el rango. */
    @GetMapping("/puntos")
    public ReportePuntos puntos(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.puntos(desde, hasta);
    }
}
