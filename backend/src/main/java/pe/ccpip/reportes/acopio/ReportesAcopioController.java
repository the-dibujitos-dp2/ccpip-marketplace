package pe.ccpip.reportes.acopio;

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
import pe.ccpip.reportes.ReportesModelos.Agrupacion;
import pe.ccpip.reportes.exportar.Descarga;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteEntradas;
import pe.ccpip.reportes.acopio.ReportesAcopioModelos.ReporteInventario;
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

    /** CCPIP-343 (RF-233): inventario de sus puntos por periodo, con lo vendido que aun espera entrega. */
    @GetMapping("/inventario")
    public ReporteInventario inventario(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(required = false) Agrupacion agrupacion,
                                        @RequestParam(required = false) Long productor,
                                        @RequestParam(required = false) Long producto) {
        return service.inventario(s.userId(), desde, hasta, agrupacion, productor, producto);
    }

    /**
     * CCPIP-325, CCPIP-378, CCPIP-380, CCPIP-382: entradas, salidas, ventas e inventario de sus puntos
     * en un solo PDF, con los mismos filtros que la pantalla. {@code lang} (o Accept-Language) elige el idioma.
     */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                      @RequestParam(required = false) Agrupacion agrupacion,
                                      @RequestParam(required = false) Long productor,
                                      @RequestParam(required = false) Long producto,
                                      @RequestParam(required = false) String lang,
                                      @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Idioma idioma = Descarga.idioma(lang, acceptLanguage);
        return Descarga.pdf(service.documento(s.userId(), desde, hasta, agrupacion, productor, producto, idioma),
                "reporte-punto-de-acopio", idioma);
    }

    /**
     * CCPIP-377, CCPIP-379, CCPIP-381, CCPIP-383: los mismos cuatro reportes en Excel, una hoja por seccion
     * y con las cifras como numeros para que el administrador pueda seguir calculando.
     */
    @GetMapping("/xlsx")
    public ResponseEntity<byte[]> excel(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                        @RequestParam(required = false) Agrupacion agrupacion,
                                        @RequestParam(required = false) Long productor,
                                        @RequestParam(required = false) Long producto,
                                        @RequestParam(required = false) String lang,
                                        @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Idioma idioma = Descarga.idioma(lang, acceptLanguage);
        return Descarga.xlsx(service.documento(s.userId(), desde, hasta, agrupacion, productor, producto, idioma),
                "reporte-punto-de-acopio", idioma);
    }
}
