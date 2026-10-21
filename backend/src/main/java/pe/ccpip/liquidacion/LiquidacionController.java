package pe.ccpip.liquidacion;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/**
 * CCPIP-92 (RF-70): la liquidacion del productor de la sesion (RNF-05); nunca recibe un id de productor.
 * CCPIP-326 (RF-214): la misma liquidacion exportada en PDF.
 */
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

    /** CCPIP-326: el PDF del mismo periodo, con los mismos importes que la consulta. */
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        Liquidacion liquidacion = service.consultar(actual.id(s), desde, hasta);
        return descarga(LiquidacionPdf.generar(liquidacion), MediaType.APPLICATION_PDF, nombre(liquidacion, "pdf"));
    }

    /** "liquidacion-2026-11-01-al-2026-11-30.pdf" */
    static String nombre(Liquidacion l, String extension) {
        return "liquidacion-" + l.desde() + "-al-" + l.hasta() + "." + extension;
    }

    /** Descarga privada (RNF-08): sin cache compartida y sin que el navegador adivine el tipo. El nombre es ASCII. */
    static ResponseEntity<byte[]> descarga(byte[] contenido, MediaType tipo, String nombre) {
        return ResponseEntity.ok()
                .contentType(tipo)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(nombre).build().toString())
                .body(contenido);
    }
}
