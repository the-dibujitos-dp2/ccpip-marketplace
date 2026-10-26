package pe.ccpip.video;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import pe.ccpip.auth.access.Publico;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.imagenes.ImagenStorage;
import pe.ccpip.productor.ProductorActual;
import pe.ccpip.video.VideosService.Video;

/**
 * Videos de la tienda: el productor los sube (CCPIP-118), los consulta (CCPIP-390), los reemplaza (CCPIP-391) y
 * los retira (CCPIP-392); cualquiera ve los aprobados.
 */
@RestController
public class VideosController {

    private static final Pattern RANGO = Pattern.compile("bytes=(\\d{0,15})-(\\d{0,15})");
    static final long PARTE = 2L * 1024 * 1024;

    private final VideosService service;
    private final ProductorActual productorActual;
    private final ImagenStorage storage;

    public VideosController(VideosService service, ProductorActual productorActual, ImagenStorage storage) {
        this.service = service;
        this.productorActual = productorActual;
        this.storage = storage;
    }

    /**
     * El archivo del video. Con Range se responde por partes de hasta {@value #PARTE} bytes, que el navegador
     * va pidiendo al reproducir o adelantar: nunca se carga el video entero en memoria. Sin Range sale
     * completo, por streaming. Los aprobados se guardan en cache publica; los demas no se guardan.
     */
    @Publico
    @GetMapping("/api/videos/{id}")
    public ResponseEntity<?> ver(
            @RequestAttribute(name = SessionAuthFilter.SESSION_ATTRIBUTE, required = false) AuthenticatedSession sesion,
            @PathVariable long id, @RequestHeader(name = HttpHeaders.RANGE, required = false) String rango) throws IOException {
        VideosService.Archivo archivo = service.archivo(id, sesion);
        long total = storage.tamano(archivo.clave()).orElseThrow(VideosService::noEncontrado);
        if (rango == null) {
            return cabeceras(ResponseEntity.ok(), archivo).contentLength(total)
                    .body(new InputStreamResource(storage.leerRango(archivo.clave(), 0, total - 1)));
        }
        long[] parte = rangoPedido(rango, total);
        if (parte.length == 0) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total).build();
        }
        byte[] bytes;
        try (InputStream in = storage.leerRango(archivo.clave(), parte[0], parte[1])) {
            bytes = in.readAllBytes();
        }
        return cabeceras(ResponseEntity.status(HttpStatus.PARTIAL_CONTENT), archivo)
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + parte[0] + "-" + parte[1] + "/" + total)
                .contentLength(bytes.length)
                .body(bytes);
    }

    private static ResponseEntity.BodyBuilder cabeceras(ResponseEntity.BodyBuilder respuesta, VideosService.Archivo archivo) {
        return respuesta.contentType(MediaType.parseMediaType(archivo.contentType()))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .cacheControl(archivo.publico() ? CacheControl.maxAge(Duration.ofDays(1)).cachePublic() : CacheControl.noStore());
    }

    /** El rango pedido, recortado a {@value #PARTE} bytes; vacio si no se puede atender. */
    static long[] rangoPedido(String rango, long total) {
        Matcher m = RANGO.matcher(rango.trim());
        if (!m.matches() || m.group(1).isEmpty() && m.group(2).isEmpty()) {
            return new long[0];
        }
        long desde;
        long hasta;
        if (m.group(1).isEmpty()) {
            desde = Math.max(0, total - Long.parseLong(m.group(2)));
            hasta = total - 1;
        } else {
            desde = Long.parseLong(m.group(1));
            hasta = m.group(2).isEmpty() ? total - 1 : Math.min(total - 1, Long.parseLong(m.group(2)));
        }
        if (desde > hasta) {
            return new long[0];
        }
        return new long[] {desde, Math.min(hasta, desde + PARTE - 1)};
    }

    /** CCPIP-390: los videos de su propia tienda, con su estado y el motivo si se rechazaron. */
    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/videos")
    public List<Video> propios(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession sesion) {
        return service.propios(productorActual.id(sesion));
    }

    /** CCPIP-118: sube un video propio; queda en revision del subadministrador. */
    @RolesPermitidos(Rol.PRODUCTOR)
    @PostMapping(value = "/api/productor/videos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Video> subir(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession sesion,
                                       @RequestParam String titulo, @RequestParam MultipartFile archivo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.subir(productorActual.id(sesion), sesion.userId(), titulo, archivo));
    }

    /** CCPIP-391: otro video en lugar de uno propio; el nuevo pasa por la revision. {@code titulo} es opcional. */
    @RolesPermitidos(Rol.PRODUCTOR)
    @PostMapping(value = "/api/productor/videos/{id}/reemplazo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Video> reemplazar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession sesion,
                                            @PathVariable long id, @RequestParam(required = false) String titulo,
                                            @RequestParam MultipartFile archivo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.reemplazar(productorActual.id(sesion), sesion.userId(), id, titulo, archivo));
    }

    /** CCPIP-392: retiro logico de un video propio; deja de verse pero no se borra. */
    @RolesPermitidos(Rol.PRODUCTOR)
    @DeleteMapping("/api/productor/videos/{id}")
    public ResponseEntity<Void> retirar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession sesion,
                                        @PathVariable long id) {
        service.retirarPropio(productorActual.id(sesion), sesion.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
