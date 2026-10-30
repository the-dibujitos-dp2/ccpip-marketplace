package pe.ccpip.reportes.productor;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.Idioma;
import pe.ccpip.productor.ProductorActual;
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.exportar.Descarga;
import pe.ccpip.reportes.productor.ReportesProductorModelos.Modalidad;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteConsumo;
import pe.ccpip.reportes.productor.ReportesProductorModelos.ReporteInventario;
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

    /**
     * CCPIP-135 (RF-105): consumo de sus productos por modalidad y frecuencia de compra, con los filtros de las
     * ventas. Solo conteos: nunca quien compro.
     */
    @GetMapping("/consumo")
    public ReporteConsumo consumo(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                  @RequestParam(required = false) Agrupacion agrupacion,
                                  @RequestParam(required = false) Long producto,
                                  @RequestParam(required = false) Modalidad modalidad) {
        return service.consumo(productorActual.id(s), desde, hasta, agrupacion, producto, modalidad);
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

    /** CCPIP-334 (RF-224): existencias de sus productos por periodo, con filtro de producto. */
    @GetMapping("/inventario")
    public ReporteInventario inventario(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(required = false) Agrupacion agrupacion,
                                        @RequestParam(required = false) Long producto) {
        return service.inventario(productorActual.id(s), desde, hasta, agrupacion, producto);
    }

    /**
     * CCPIP-115 (RF-26): el reporte de sus productos en PDF: ventas, produccion e inventario del periodo con
     * los mismos filtros que la pantalla. {@code lang} (o Accept-Language) elige el idioma de los textos.
     */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                      @RequestParam(required = false) Agrupacion agrupacion,
                                      @RequestParam(required = false) Long producto,
                                      @RequestParam(required = false) Modalidad modalidad,
                                      @RequestParam(required = false) String lang,
                                      @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Idioma idioma = Descarga.idioma(lang, acceptLanguage);
        return Descarga.pdf(service.documento(productorActual.id(s), desde, hasta, agrupacion, producto, modalidad, idioma),
                "reporte-productos", idioma);
    }

    /**
     * CCPIP-335 (RF-225): el mismo reporte en Excel, una hoja por seccion y con las cifras como numeros para
     * que el productor pueda seguir calculando.
     */
    @GetMapping("/xlsx")
    public ResponseEntity<byte[]> excel(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(required = false) Agrupacion agrupacion,
                                        @RequestParam(required = false) Long producto,
                                        @RequestParam(required = false) Modalidad modalidad,
                                        @RequestParam(required = false) String lang,
                                        @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Idioma idioma = Descarga.idioma(lang, acceptLanguage);
        return Descarga.xlsx(service.documento(productorActual.id(s), desde, hasta, agrupacion, producto, modalidad, idioma),
                "reporte-productos", idioma);
    }
}
