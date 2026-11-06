package pe.ccpip.entrega;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.entrega.ProductoMalogradoService.Accion;
import pe.ccpip.entrega.ProductoMalogradoService.Malogrado;
import pe.ccpip.entrega.ProductoMalogradoService.OpcionesLinea;
import pe.ccpip.entrega.ProductoMalogradoService.Resultado;

/**
 * CCPIP-485 y CCPIP-486: el administrador del punto registra un producto malogrado de una linea de un
 * pedido aun no entregado y lo reemplaza con otro lote o lo reembolsa. Despues de guardar, avisa al
 * comprador por correo (un correo caido no lo deshace).
 */
@RestController
@RequestMapping("/api/acopio/pedidos/{numero}/lineas/{itemId}/malogrado")
@RolesPermitidos(Rol.ADMIN_PUNTO)
public class ProductoMalogradoController {

    public record Datos(@NotNull(message = "Elige el lote malogrado") Long loteId,
                        @NotNull(message = "Indica cuánto se malogró")
                        @DecimalMin(value = "0.001", message = "La cantidad debe ser mayor que cero")
                        @Digits(integer = 9, fraction = 3, message = "Usa como máximo tres decimales") BigDecimal cantidad,
                        @NotNull(message = "Elige la causa") Long motivoId,
                        @Size(max = 200, message = "El detalle es demasiado largo") String detalle,
                        @NotNull(message = "Elige si se reemplaza o se reembolsa") Accion accion,
                        Long loteReemplazoId,
                        @NotNull(message = "Falta la clave del envío") UUID clave) {
    }

    private final ProductoMalogradoService service;
    private final AvisoProductoMalogrado aviso;

    public ProductoMalogradoController(ProductoMalogradoService service, AvisoProductoMalogrado aviso) {
        this.service = service;
        this.aviso = aviso;
    }

    @GetMapping
    public OpcionesLinea opciones(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @PathVariable String numero, @PathVariable long itemId) {
        return service.opciones(s.userId(), numeroValido(numero), itemId);
    }

    @PostMapping
    public Resultado registrar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @PathVariable String numero, @PathVariable long itemId, @Valid @RequestBody Datos d) {
        Resultado r = service.registrar(s.userId(), numeroValido(numero), itemId, new Malogrado(d.loteId(), d.cantidad(),
                d.motivoId(), d.detalle(), d.accion(), d.loteReemplazoId(), d.clave()));
        if (!r.repetida()) {
            aviso.avisar(r.incidenciaId());
        }
        return r;
    }

    private static String numeroValido(String numero) {
        if (!numero.matches("[A-Za-z0-9-]{1,20}")) {
            throw ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe en tu punto de acopio.");
        }
        return numero.toUpperCase(Locale.ROOT);
    }
}
