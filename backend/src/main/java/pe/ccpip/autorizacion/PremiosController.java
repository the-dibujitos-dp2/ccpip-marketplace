package pe.ccpip.autorizacion;

import java.io.IOException;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
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
import pe.ccpip.autorizacion.PremiosModelos.NuevoPremio;
import pe.ccpip.autorizacion.PremiosModelos.Premio;
import pe.ccpip.autorizacion.PremiosModelos.PremiosProducto;
import pe.ccpip.productor.ProductorActual;

/** CCPIP-43 (RF-29): el productor registra, consulta y retira los premios de sus productos. */
@RestController
public class PremiosController {

    private final PremiosService service;
    private final ProductorActual actual;

    public PremiosController(PremiosService service, ProductorActual actual) {
        this.service = service;
        this.actual = actual;
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/productos/{productoId}/premios")
    public PremiosProducto listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @PathVariable long productoId) {
        return service.listar(actual.id(s), productoId);
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @PostMapping(value = "/api/productor/productos/{productoId}/premios", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Premio> registrar(
            @RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
            @PathVariable long productoId,
            @RequestParam MultipartFile archivo,
            @RequestParam(required = false) String titulo,
            @RequestParam(required = false) String emisor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate otorgadoEn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate vigenteHasta,
            @RequestParam(required = false) Long loteId) throws IOException {
        Premio premio = service.registrar(actual.id(s), s.userId(), productoId,
                new NuevoPremio(titulo, emisor, otorgadoEn, vigenteHasta, loteId), archivo.getOriginalFilename(),
                archivo.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(premio);
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @DeleteMapping("/api/productor/productos/{productoId}/premios/{premioId}")
    public ResponseEntity<Void> retirar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                        @PathVariable long productoId, @PathVariable long premioId) {
        service.retirar(actual.id(s), s.userId(), productoId, premioId);
        return ResponseEntity.noContent().build();
    }

    /** El archivo de un premio propio, privado (RNF-08). Lo ajeno o inexistente responde 404. */
    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/premios/{premioId}/archivo")
    public ResponseEntity<byte[]> archivo(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                          @PathVariable long premioId) {
        return EvidenciasController.servir(service.archivoPropio(actual.id(s), premioId));
    }
}
