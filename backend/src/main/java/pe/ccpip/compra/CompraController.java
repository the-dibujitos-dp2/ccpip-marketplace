package pe.ccpip.compra;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.compra.CompraModelos.IntentoPago;
import pe.ccpip.compra.CompraModelos.Pedido;
import pe.ccpip.compra.CompraModelos.PuntoRecojo;
import pe.ccpip.compra.CompraModelos.ResultadoPago;
import pe.ccpip.pago.Tarjeta;

/**
 * Compra del comprador: recojo (CCPIP-157), pago con tarjeta (CCPIP-62), intentos de pago
 * (CCPIP-163) y confirmacion (CCPIP-64). Opera siempre sobre los datos del usuario de la sesion (RNF-05).
 */
@RolesPermitidos(Rol.CONSUMIDOR)
@RestController
public class CompraController {

    /** Nombres de personas: letras (con tildes), espacios, apostrofos, puntos y guiones. */
    static final String NOMBRE = "^\\p{L}[\\p{L}\\s'’.-]*$";

    /** Datos del recojo. Sin persona autorizada, recoge el mismo comprador. */
    public record Recojo(@NotNull(message = "Elige un punto de acopio") Long puntoId,
                         @Size(max = 160, message = "El nombre es demasiado largo")
                         @Pattern(regexp = NOMBRE, message = "Usa solo letras en el nombre de quien recoge") String personaAutorizada,
                         @NotBlank(message = "Indica un celular de contacto")
                         @Size(max = 20, message = "El celular es demasiado largo") String celularContacto) {
    }

    /** Tarjeta tal como la escribe el comprador. No se guarda: solo se usa para cobrar. */
    public record DatosTarjeta(@NotBlank(message = "Indica el número de tarjeta") @Size(max = 30) String numero,
                               @NotBlank(message = "Indica el nombre del titular") @Size(max = 120)
                               @Pattern(regexp = NOMBRE, message = "Usa solo letras en el nombre del titular") String titular,
                               @NotNull(message = "Indica el mes de vencimiento") Integer mes,
                               @NotNull(message = "Indica el año de vencimiento") Integer anio,
                               @NotBlank(message = "Indica el código de seguridad") @Size(max = 4) String cvv) {
        Tarjeta tarjeta() {
            return new Tarjeta(numero, titular, mes, anio, cvv);
        }

        @Override
        public String toString() {
            return tarjeta().toString();
        }
    }

    /** {@code claveIdempotencia}: la genera el navegador por intento; reenviarla no vuelve a cobrar. */
    public record Pago(@NotNull(message = "Falta la clave del intento de pago") UUID claveIdempotencia,
                       @NotNull(message = "Indica los datos de tu tarjeta") @Valid DatosTarjeta tarjeta) {
    }

    private final CompraService compra;
    private final PagoService pagos;

    public CompraController(CompraService compra, PagoService pagos) {
        this.compra = compra;
        this.pagos = pagos;
    }

    /** CCPIP-157: puntos de acopio con existencias para todo el carrito. */
    @GetMapping("/api/compra/puntos")
    public List<PuntoRecojo> puntos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return compra.puntosElegibles(s.userId());
    }

    /** CCPIP-157: crea el pedido para recojo, en espera de su pago. No descuenta stock todavia. */
    @PostMapping("/api/pedidos")
    @ResponseStatus(HttpStatus.CREATED)
    public Pedido confirmar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                            @Valid @RequestBody Recojo recojo) {
        return compra.confirmar(s.userId(), recojo.puntoId(), recojo.personaAutorizada(), recojo.celularContacto());
    }

    /** CCPIP-62: paga el pedido con tarjeta. Aprobado: el pedido queda confirmado y el stock descontado. */
    @PostMapping("/api/pedidos/{id}/pagos")
    public ResultadoPago pagar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                               @PathVariable long id, @Valid @RequestBody Pago pago) {
        return pagos.pagar(s.userId(), id, pago.claveIdempotencia(), pago.tarjeta().tarjeta());
    }

    /** CCPIP-64: el pedido con su numero, articulos e importes. */
    @GetMapping("/api/pedidos/{id}")
    public Pedido pedido(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                         @PathVariable long id) {
        return compra.pedido(s.userId(), id);
    }

    /** CCPIP-163: cada intento de pago del pedido con su estado, sin datos de la tarjeta. */
    @GetMapping("/api/pedidos/{id}/pagos")
    public List<IntentoPago> intentos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                      @PathVariable long id) {
        return compra.pedido(s.userId(), id).pagos();
    }
}
