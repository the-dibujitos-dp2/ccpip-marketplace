package pe.ccpip.merma;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.comun.Idioma;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.merma.MermaModelos.Causa;
import pe.ccpip.merma.MermaModelos.DatosMerma;
import pe.ccpip.merma.MermaModelos.FiltroMerma;
import pe.ccpip.merma.MermaModelos.FiltroReporteMerma;
import pe.ccpip.merma.MermaModelos.MermaRegistrada;
import pe.ccpip.merma.MermaModelos.PaginaMerma;
import pe.ccpip.merma.MermaModelos.ReporteMerma;
import pe.ccpip.productor.ProductorActual;

/**
 * Merma del punto de acopio (A-04, A-07) y del almacen del productor (CCPIP-75, CCPIP-76). Cada uno
 * marca y consulta solo lo suyo; el alcance lo aplica el servidor (RNF-05).
 */
@Validated
@RestController
public class MermaController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final MermaService merma;
    private final MotivosMermaService motivos;
    private final ProductorActual productorActual;

    public MermaController(MermaService merma, MotivosMermaService motivos, ProductorActual productorActual) {
        this.merma = merma;
        this.motivos = motivos;
        this.productorActual = productorActual;
    }

    /** Las causas que ofrece "Marcar como merma": solo las activas del catalogo. */
    @RolesPermitidos({Rol.PRODUCTOR, Rol.ADMIN_PUNTO})
    @GetMapping("/api/merma/causas")
    public List<Causa> causas() {
        return motivos.activas();
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @GetMapping("/api/acopio/merma")
    public PaginaMerma dePuntos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @RequestParam(defaultValue = "0") @Min(0) int pagina,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                @RequestParam(required = false) String lote, @RequestParam(required = false) String producto,
                                @RequestParam(required = false) String ubicacion, @RequestParam(required = false) Long causa,
                                @RequestParam(required = false) Long productor) {
        Long productoId = entero(producto);
        if (causa != null || productor != null || productoId != null) {
            return merma.dePuntos(s.userId(), pagina, new FiltroMerma(causa, productoId, productor, desde, hasta));
        }
        return merma.dePuntos(s.userId(), pagina, filtros(desde, hasta, lote, producto, ubicacion));
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @GetMapping("/api/acopio/merma/pdf")
    public ResponseEntity<byte[]> pdfDePuntos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                               @RequestParam(required = false) String lote, @RequestParam(required = false) String producto,
                                               @RequestParam(required = false) String ubicacion, @RequestParam(required = false) String lang,
                                               @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Idioma idioma = idioma(lang, acceptLanguage);
        ReporteMerma reporte = merma.reporteDePuntos(s.userId(), filtros(desde, hasta, lote, producto, ubicacion), idioma);
        return descarga(MermaPdf.generar(reporte), MediaType.APPLICATION_PDF, "reporte-merma-acopio.pdf", idioma);
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @GetMapping("/api/acopio/merma/xlsx")
    public ResponseEntity<byte[]> excelDePuntos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                 @RequestParam(required = false) String lote, @RequestParam(required = false) String producto,
                                                 @RequestParam(required = false) String ubicacion, @RequestParam(required = false) String lang,
                                                 @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Idioma idioma = idioma(lang, acceptLanguage);
        ReporteMerma reporte = merma.reporteDePuntos(s.userId(), filtros(desde, hasta, lote, producto, ubicacion), idioma);
        return descarga(MermaExcel.generar(reporte), XLSX, "reporte-merma-acopio.xlsx", idioma);
    }

    /** 201 la primera vez; 200 si es el mismo envio otra vez (no marca de nuevo). */
    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @PostMapping("/api/acopio/merma")
    public ResponseEntity<MermaRegistrada> marcarEnPunto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                         @Valid @RequestBody DatosMerma datos) {
        return respuesta(merma.marcarEnPunto(s.userId(), datos));
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/merma")
    public PaginaMerma deProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @RequestParam(defaultValue = "0") @Min(0) int pagina,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                   @RequestParam(required = false) String lote, @RequestParam(required = false) String producto,
                                   @RequestParam(required = false) String ubicacion) {
        return merma.deProductor(productorActual.id(s), s.userId(), pagina, filtros(desde, hasta, lote, producto, ubicacion));
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/merma/pdf")
    public ResponseEntity<byte[]> pdfDeProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                  @RequestParam(required = false) String lote, @RequestParam(required = false) String producto,
                                                  @RequestParam(required = false) String ubicacion, @RequestParam(required = false) String lang,
                                                  @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Idioma idioma = idioma(lang, acceptLanguage);
        ReporteMerma reporte = merma.reporteDeProductor(productorActual.id(s), s.userId(), filtros(desde, hasta, lote, producto, ubicacion), idioma);
        return descarga(MermaPdf.generar(reporte), MediaType.APPLICATION_PDF, "reporte-merma-productor.pdf", idioma);
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/merma/xlsx")
    public ResponseEntity<byte[]> excelDeProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                                    @RequestParam(required = false) String lote, @RequestParam(required = false) String producto,
                                                    @RequestParam(required = false) String ubicacion, @RequestParam(required = false) String lang,
                                                    @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Idioma idioma = idioma(lang, acceptLanguage);
        ReporteMerma reporte = merma.reporteDeProductor(productorActual.id(s), s.userId(), filtros(desde, hasta, lote, producto, ubicacion), idioma);
        return descarga(MermaExcel.generar(reporte), XLSX, "reporte-merma-productor.xlsx", idioma);
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @PostMapping("/api/productor/merma")
    public ResponseEntity<MermaRegistrada> marcarEnAlmacen(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                           @Valid @RequestBody DatosMerma datos) {
        return respuesta(merma.marcarEnAlmacen(productorActual.id(s), s.userId(), datos));
    }

    private static ResponseEntity<MermaRegistrada> respuesta(MermaRegistrada r) {
        return ResponseEntity.status(r.repetida() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    private static FiltroReporteMerma filtros(LocalDate desde, LocalDate hasta, String lote, String producto, String ubicacion) {
        return new FiltroReporteMerma(desde, hasta, lote, producto, ubicacion);
    }

    /** The legacy point-list product filter uses an id; report text remains a text filter. */
    private static Long entero(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(valor);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Explicit language affects only presentation, never role or data scope. */
    private static Idioma idioma(String lang, String acceptLanguage) {
        return lang == null ? Idioma.deCabecera(acceptLanguage) : Idioma.deCodigo(lang).orElse(Idioma.ES);
    }

    /** Descargas privadas: no cache compartida y tipo explícito. */
    private static ResponseEntity<byte[]> descarga(byte[] contenido, MediaType tipo, String nombre, Idioma idioma) {
        return ResponseEntity.ok().contentType(tipo).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_LANGUAGE, idioma.codigo())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
                .body(contenido);
    }
}
