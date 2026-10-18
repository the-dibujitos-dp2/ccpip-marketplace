package pe.ccpip.merma;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.merma.MermaModelos.Causa;
import pe.ccpip.merma.MermaModelos.DatosMerma;
import pe.ccpip.merma.MermaModelos.MermaRegistrada;
import pe.ccpip.merma.MermaModelos.PaginaMerma;
import pe.ccpip.productor.ProductorActual;

/**
 * Merma del punto de acopio (A-04, A-07) y del almacen del productor (CCPIP-75, CCPIP-76). Cada uno
 * marca y consulta solo lo suyo; el alcance lo aplica el servidor (RNF-05).
 */
@Validated
@RestController
public class MermaController {

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
                                @RequestParam(defaultValue = "0") @Min(0) int pagina) {
        return merma.dePuntos(s.userId(), pagina);
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
                                   @RequestParam(defaultValue = "0") @Min(0) int pagina) {
        return merma.deProductor(productorActual.id(s), s.userId(), pagina);
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
}
