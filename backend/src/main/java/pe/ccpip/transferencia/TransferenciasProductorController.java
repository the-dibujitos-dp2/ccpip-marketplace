package pe.ccpip.transferencia;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.productor.ProductorActual;
import pe.ccpip.transferencia.TransferenciaModelos.DatosTransferencia;
import pe.ccpip.transferencia.TransferenciaModelos.Tipo;
import pe.ccpip.transferencia.TransferenciaModelos.Transferencia;
import pe.ccpip.transferencia.TransferenciaModelos.Transferencias;
import pe.ccpip.transferencia.TransferenciaModelos.Ubicaciones;
import pe.ccpip.transferencia.TransferenciaModelos.Vista;

/**
 * Transferencias del productor (Figma PW-17 y PW-18): CCPIP-73, 277, 164, 362 y 146. Todo opera
 * sobre el productor de la sesion (RNF-05).
 */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor/transferencias")
public class TransferenciasProductorController {

    private final ProductorActual actual;
    private final TransferenciaService transferencias;

    public TransferenciasProductorController(ProductorActual actual, TransferenciaService transferencias) {
        this.actual = actual;
        this.transferencias = transferencias;
    }

    /** PW-17. {@code vista}: EN_CURSO o HISTORIAL; {@code tipo}: ALMACEN_A_PUNTO o ENTRE_PUNTOS. Sin ellos, todas. */
    @GetMapping
    public Transferencias listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @RequestParam(required = false) String vista,
                                 @RequestParam(required = false) String tipo) {
        return transferencias.listarProductor(actual.id(s), opcion(Vista.class, vista, "VISTA_INVALIDA"),
                opcion(Tipo.class, tipo, "TIPO_INVALIDO"));
    }

    /** CCPIP-146 (RF-112): ubicacion de su almacen y de los puntos de acopio para elegir el destino. */
    @GetMapping("/ubicaciones")
    public Ubicaciones ubicaciones(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return transferencias.ubicaciones(actual.id(s));
    }

    @GetMapping("/{id}")
    public Transferencia detalle(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @PathVariable long id) {
        return transferencias.detalleProductor(actual.id(s), id);
    }

    /** CCPIP-73 (RF-67) y CCPIP-277 (RF-177). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Transferencia crear(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @Valid @RequestBody DatosTransferencia datos) {
        return transferencias.crear(actual.id(s), s.userId(), datos);
    }

    /** CCPIP-164 (RF-133): separa las unidades del lote de origen. */
    @PostMapping("/{id}/preparar")
    public Transferencia preparar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @PathVariable long id) {
        return transferencias.preparar(actual.id(s), s.userId(), id);
    }

    /** CCPIP-362 (RF-252): despacha lo preparado hacia el punto de acopio. */
    @PostMapping("/{id}/despachar")
    public Transferencia despachar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @PathVariable long id) {
        return transferencias.despachar(actual.id(s), s.userId(), id);
    }

    static <E extends Enum<E>> E opcion(Class<E> tipo, String valor, String code) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(tipo, valor.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ErrorNegocio.invalido(code, "Filtro desconocido.");
        }
    }
}
