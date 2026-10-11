package pe.ccpip.carrito;

import java.math.BigDecimal;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.carrito.CarritoModelos.Carrito;
import pe.ccpip.carrito.CarritoService.Agregado;

/** Carrito del comprador. Opera siempre sobre el carrito del usuario de la sesion (RNF-05). */
@RolesPermitidos(Rol.CONSUMIDOR)
@RestController
@RequestMapping("/api/carrito")
public class CarritoController {

    /**
     * Cantidad en unidades enteras de la presentacion de la oferta, minimo una (CCPIP-58). Es
     * BigDecimal para rechazar 1.5 en vez de que Jackson lo trunque a 1 sin avisar.
     */
    public record AgregarItem(@NotNull(message = "Elige un producto") Long productoId,
                              @NotNull(message = "Indica la cantidad")
                              @DecimalMin(value = "1", message = "La cantidad mínima es una unidad")
                              @Digits(integer = 6, fraction = 0, message = "La cantidad debe ser un número entero de unidades")
                              BigDecimal cantidad) {
    }

    /** CCPIP-268: la cantidad nueva reemplaza a la anterior; mismas reglas que al agregar. */
    public record NuevaCantidad(@NotNull(message = "Indica la cantidad")
                                @DecimalMin(value = "1", message = "La cantidad mínima es una unidad")
                                @Digits(integer = 6, fraction = 0, message = "La cantidad debe ser un número entero de unidades")
                                BigDecimal cantidad) {
    }

    private final CarritoService carrito;

    public CarritoController(CarritoService carrito) {
        this.carrito = carrito;
    }

    /** CCPIP-155: articulos, cantidades, subtotales y total con precio y stock revalidados. */
    @GetMapping
    public Carrito consultar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return carrito.consultar(s.userId());
    }

    /** CCPIP-268: cambia la cantidad de una oferta del carrito y devuelve el carrito recalculado. */
    @PutMapping("/items/{productoId}")
    public Carrito modificar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @PathVariable long productoId, @Valid @RequestBody NuevaCantidad nueva) {
        return carrito.modificar(s.userId(), productoId, nueva.cantidad());
    }

    /** CCPIP-156: quita una oferta del carrito y devuelve el carrito recalculado. */
    @DeleteMapping("/items/{productoId}")
    public Carrito quitar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                          @PathVariable long productoId) {
        return carrito.quitar(s.userId(), productoId);
    }

    /** CCPIP-58: agrega una oferta especifica con su cantidad, sin reservar existencias (RNF-35). */
    @PostMapping("/items")
    public Agregado agregar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                            @Valid @RequestBody AgregarItem item) {
        return carrito.agregar(s.userId(), item.productoId(), item.cantidad());
    }
}
