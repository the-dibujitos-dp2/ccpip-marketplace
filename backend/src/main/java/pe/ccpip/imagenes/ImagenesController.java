package pe.ccpip.imagenes;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import pe.ccpip.auth.access.Publico;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.productor.ProductorActual;

@RestController
public class ImagenesController {

    private final ImagenService service;
    private final ProductorActual productorActual;

    public ImagenesController(ImagenService service, ProductorActual productorActual) {
        this.service = service;
        this.productorActual = productorActual;
    }

    /**
     * Imagen publica. El id es aleatorio y el contenido nunca cambia (una foto nueva tiene otro
     * id), asi que el navegador la guarda en cache para siempre: clave con conexion lenta.
     */
    @Publico
    @GetMapping("/api/imagenes/{id}")
    public ResponseEntity<byte[]> ver(@PathVariable UUID id, @RequestParam(required = false) String tam) {
        return service.contenido(id, "min".equals(tam))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @PostMapping(value = "/api/productor/imagenes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImagenService.Imagen> subir(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session,
            @RequestParam MultipartFile archivo,
            @RequestParam ImagenService.Tipo tipo,
            @RequestParam(required = false) String categoria,
            @RequestParam(required = false) Long productoId) throws IOException {
        if (archivo.isEmpty()) {
            throw ErrorNegocio.invalido("ARCHIVO_VACIO", "El archivo está vacío.");
        }
        ImagenService.Imagen imagen = service.subir(productorActual.id(session), session.userId(),
                tipo, categoria, productoId, archivo.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(imagen);
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @PutMapping("/api/productor/imagenes/{id}/principal")
    public ResponseEntity<Void> principal(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session, @PathVariable UUID id) {
        service.marcarPrincipal(productorActual.id(session), id);
        return ResponseEntity.noContent().build();
    }

    /** Quitar una foto si se permite; lo que no existe es borrar productos (RNF-17). */
    @RolesPermitidos(Rol.PRODUCTOR)
    @DeleteMapping("/api/productor/imagenes/{id}")
    public ResponseEntity<Void> eliminar(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession session, @PathVariable UUID id) {
        service.eliminar(productorActual.id(session), id);
        return ResponseEntity.noContent().build();
    }
}
