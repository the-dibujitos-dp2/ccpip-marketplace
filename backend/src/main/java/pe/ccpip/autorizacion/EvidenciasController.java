package pe.ccpip.autorizacion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.autorizacion.AutorizacionModelos.Evidencia;
import pe.ccpip.autorizacion.AutorizacionModelos.EvidenciasProducto;
import pe.ccpip.autorizacion.AutorizacionModelos.NuevaEvidencia;
import pe.ccpip.autorizacion.AutorizacionModelos.TipoEvidencia;
import pe.ccpip.productor.ProductorActual;

/**
 * CCPIP-36 (RF-13): evidencias del producto cuya autorizacion solicita el productor. Todo opera sobre
 * el productor de la sesion (RNF-05); los archivos se sirven solo con sesion (RNF-08).
 */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
public class EvidenciasController {

    private final EvidenciasService service;
    private final ProductorActual actual;

    public EvidenciasController(EvidenciasService service, ProductorActual actual) {
        this.service = service;
        this.actual = actual;
    }

    @GetMapping("/api/productor/productos/{productoId}/evidencias")
    public EvidenciasProducto listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                     @PathVariable long productoId) {
        return service.listar(actual.id(s), productoId);
    }

    @PostMapping(value = "/api/productor/productos/{productoId}/evidencias", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Evidencia> subir(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
            @PathVariable long productoId,
            @RequestParam MultipartFile archivo,
            @RequestParam TipoEvidencia tipo,
            @RequestParam String titulo,
            @RequestParam(required = false) String emisor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate vigenteDesde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate vigenteHasta)
            throws IOException {
        Evidencia evidencia = service.subir(actual.id(s), s.userId(), productoId,
                new NuevaEvidencia(tipo, titulo, emisor, vigenteDesde, vigenteHasta),
                archivo.getOriginalFilename(), archivo.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(evidencia);
    }

    @DeleteMapping("/api/productor/productos/{productoId}/evidencias/{evidenciaId}")
    public ResponseEntity<Void> retirar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @PathVariable long productoId, @PathVariable UUID evidenciaId) {
        service.retirar(actual.id(s), s.userId(), productoId, evidenciaId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/productor/evidencias/{evidenciaId}/archivo")
    public ResponseEntity<byte[]> archivo(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                          @PathVariable UUID evidenciaId) {
        return servir(service.archivoPropio(actual.id(s), evidenciaId));
    }

    /**
     * Respuesta privada (RNF-08): sin cache compartida, con el tipo detectado al subir y sin que el
     * navegador lo adivine. Lo que no existe o es ajeno responde 404.
     */
    static ResponseEntity<byte[]> servir(Optional<EvidenciasService.Archivo> archivo) {
        return archivo.map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(a.contentType()))
                        .cacheControl(CacheControl.noStore())
                        .header("X-Content-Type-Options", "nosniff")
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                                .filename(a.nombre(), StandardCharsets.UTF_8).build().toString())
                        .body(a.contenido()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
