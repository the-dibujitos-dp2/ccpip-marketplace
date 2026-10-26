package pe.ccpip.reportes.gestion;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.Idioma;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.exportar.Descarga;
import pe.ccpip.reportes.exportar.DocumentoReporte;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteCalificaciones;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteIndicadores;
import pe.ccpip.reportes.gestion.GestionModelos.ReporteOrigenDestino;
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

    /**
     * CCPIP-123 (RF-87) y CCPIP-352 (RF-242): calificaciones de productos y de productores en el rango, con el
     * promedio solo desde el minimo configurado y la tendencia frente al periodo anterior. {@code linea} es el
     * codigo de una linea de producto.
     */
    @GetMapping("/calificaciones")
    public ReporteCalificaciones calificaciones(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                               @RequestParam(required = false) String linea) {
        return service.calificaciones(desde, hasta, linea);
    }

    /** CCPIP-80 (RF-66): origen (punto y productor) y destino (departamento) de las ventas del rango. */
    @GetMapping("/origen-destino")
    public ReporteOrigenDestino origenDestino(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return service.origenDestino(desde, hasta);
    }

    /**
     * Indicadores agregados de la plataforma en el rango frente al periodo anterior, con su serie por dia,
     * semana o mes. CCPIP-348 (RF-238): ventas; CCPIP-349 (RF-239): productores activos; CCPIP-350 (RF-240):
     * productos publicados; CCPIP-94 (RF-81): actividad.
     */
    @GetMapping("/indicadores")
    public ReporteIndicadores indicadores(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                          @RequestParam(required = false) Agrupacion agrupacion) {
        return service.indicadores(desde, hasta, agrupacion);
    }

    /**
     * La pestaña que se ve en PDF o Excel, con sus filtros: {@code reporte} es productores, puntos,
     * calificaciones, origen-destino o indicadores; {@code formato}, pdf o xlsx. {@code lang} (o
     * Accept-Language) elige el idioma de los textos.
     */
    @GetMapping("/{reporte}/{formato}")
    public ResponseEntity<byte[]> exportar(@PathVariable String reporte, @PathVariable String formato,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                           @RequestParam(required = false) Agrupacion agrupacion,
                                           @RequestParam(required = false) String linea,
                                           @RequestParam(required = false) String lang,
                                           @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        if (!formato.equals("pdf") && !formato.equals("xlsx")) {
            throw ErrorNegocio.noEncontrado("FORMATO_NO_ENCONTRADO", "Solo se exporta en PDF o Excel.");
        }
        Idioma idioma = Descarga.idioma(lang, acceptLanguage);
        DocumentoReporte documento = service.documento(reporte, desde, hasta, agrupacion, linea, idioma);
        return formato.equals("pdf") ? Descarga.pdf(documento, "reporte-" + reporte, idioma)
                : Descarga.xlsx(documento, "reporte-" + reporte, idioma);
    }
}
