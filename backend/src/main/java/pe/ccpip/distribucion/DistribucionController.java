package pe.ccpip.distribucion;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.distribucion.DistribucionModelos.Disponibles;
import pe.ccpip.distribucion.DistribucionModelos.EntregaDelDistribuidor;
import pe.ccpip.distribucion.DistribucionUbicacionesModelos.UbicacionesPedido;

/**
 * CCPIP-107, CCPIP-108, CCPIP-317 y CCPIP-109 (DW-01 a DW-03): los pedidos que el distribuidor puede asumir,
 * asumirlos, los que ya tiene asignados y retirarlos del punto.
 */
@RolesPermitidos(Rol.DISTRIBUIDOR)
@RestController
@RequestMapping("/api/distribuidor/pedidos")
public class DistribucionController {

    private final DistribucionService distribucion;

    public DistribucionController(DistribucionService distribucion) {
        this.distribucion = distribucion;
    }

    /**
     * Una pagina de los pedidos preparados disponibles, con origen, destino y antiguedad; el que lleva mas
     * esperando va primero. {@code punto} filtra por punto de acopio; {@code cursor} es el {@code siguiente} de la
     * pagina anterior (422 {@code CURSOR_INVALIDO} si no lo es); {@code limite}, de 1 a 50 (20 por defecto).
     */
    @GetMapping("/disponibles")
    public Disponibles disponibles(@RequestParam(required = false) Long punto,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limite) {
        return distribucion.disponibles(punto, cursor, limite);
    }

    /**
     * CCPIP-317 (RF-199, DW-03): las entregas asignadas al distribuidor de la sesion, con origen, destino y estado: las que
     * estan en curso y las que entrego hoy. Nunca las de otro distribuidor.
     */
    @GetMapping
    public List<EntregaDelDistribuidor> asignadas(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return distribucion.asignadas(s.userId());
    }

    /** CCPIP-110 (RF-57): ubicaciones registradas de un pedido propio a domicilio, sin contactos ni rutas. */
    @GetMapping("/{numero}/ubicaciones")
    public UbicacionesPedido ubicaciones(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                         @PathVariable String numero) {
        return distribucion.ubicaciones(s.userId(), numeroValido(numero));
    }

    /**
     * CCPIP-108 (RF-55): el distribuidor asume la entrega de un pedido disponible. La asignacion es exclusiva: si
     * dos la piden a la vez, solo uno la obtiene y el otro recibe 409 {@code PEDIDO_YA_ASUMIDO}.
     */
    @PostMapping("/{numero}/asumir")
    public EntregaDelDistribuidor asumir(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                         @PathVariable String numero) {
        return distribucion.asumir(s.userId(), numeroValido(numero));
    }

    /**
     * CCPIP-109 (RF-56): el distribuidor registra que retiro del punto de acopio la entrega que asumio. Solo la suya
     * (409 {@code PEDIDO_NO_ASIGNADO}) y mientras siga asignada (409 {@code PEDIDO_NO_RETIRABLE}); repetirlo no cambia
     * nada. No se vuelve a descontar el stock.
     */
    @PostMapping("/{numero}/retirar")
    public EntregaDelDistribuidor retirar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                          @PathVariable String numero) {
        return distribucion.retirar(s.userId(), numeroValido(numero));
    }

    /** Registra que la entrega propia retirada ya está en camino. */
    @PostMapping("/{numero}/en-camino")
    public EntregaDelDistribuidor enCamino(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                           @PathVariable String numero) {
        return distribucion.enCamino(s.userId(), numeroValido(numero));
    }

    /** Registra que la entrega propia ya llegó al destinatario. */
    @PostMapping("/{numero}/entregar")
    public EntregaDelDistribuidor entregar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                           @PathVariable String numero) {
        return distribucion.entregar(s.userId(), numeroValido(numero));
    }

    /** Un numero de pedido solo tiene letras, digitos y guiones (p. ej. MC-00148); se busca sin distinguir mayusculas. */
    private static String numeroValido(String numero) {
        if (!numero.matches("[A-Za-z0-9-]{1,20}")) {
            throw ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "El pedido no existe o no se entrega a domicilio.");
        }
        return numero.toUpperCase(Locale.ROOT);
    }
}
