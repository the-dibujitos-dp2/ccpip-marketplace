package pe.ccpip.reportes.exportar;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import pe.ccpip.comun.Idioma;

/**
 * La descarga de un reporte exportado: privada (sin cache compartida), con tipo explicito y el idioma de sus
 * textos, como el reporte de merma (CCPIP-78). Comun a los reportes del productor, del punto de acopio y de
 * gestion.
 */
public final class Descarga {

    public static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private Descarga() {
    }

    /** El idioma de los textos: {@code lang} si viene (un enlace de descarga no lleva Accept-Language). */
    public static Idioma idioma(String lang, String acceptLanguage) {
        return lang == null ? Idioma.deCabecera(acceptLanguage) : Idioma.deCodigo(lang).orElse(Idioma.ES);
    }

    public static ResponseEntity<byte[]> pdf(DocumentoReporte documento, String nombre, Idioma idioma) {
        return archivo(ReportePdf.generar(documento), MediaType.APPLICATION_PDF, nombre + ".pdf", idioma);
    }

    public static ResponseEntity<byte[]> xlsx(DocumentoReporte documento, String nombre, Idioma idioma) {
        return archivo(ReporteXlsx.generar(documento), XLSX, nombre + ".xlsx", idioma);
    }

    private static ResponseEntity<byte[]> archivo(byte[] contenido, MediaType tipo, String nombre, Idioma idioma) {
        return ResponseEntity.ok().contentType(tipo).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_LANGUAGE, idioma.codigo())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre).build().toString())
                .body(contenido);
    }
}
