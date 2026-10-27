package pe.ccpip.inventario;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.inventario.InventarioModelos.DatosMovimiento;
import pe.ccpip.inventario.InventarioModelos.FiltroMovimientos;
import pe.ccpip.inventario.InventarioModelos.Grupo;
import pe.ccpip.inventario.InventarioModelos.MovimientoRegistrado;
import pe.ccpip.inventario.InventarioModelos.Movimientos;

/**
 * Movimientos del punto de acopio (Figma A-03): consulta y registro de entradas y salidas. Solo
 * el administrador de punto: el super administrador no ve el stock de los acopios (Figma SA-08).
 */
@RolesPermitidos(Rol.ADMIN_PUNTO)
@Validated
@RestController
@RequestMapping("/api/acopio/movimientos")
public class MovimientosController {

    private final MovimientosService movimientos;
    private final MovimientoManualService manuales;

    public MovimientosController(MovimientosService movimientos, MovimientoManualService manuales) {
        this.movimientos = movimientos;
        this.manuales = manuales;
    }

    /**
     * CCPIP-158: incluye el movimiento SALIDA_VENTA que genero cada compra confirmada, con su numero
     * de pedido. {@code tipo} es la pestana: ENTRADAS, SALIDAS o AJUSTES; sin el, todos. CCPIP-417:
     * {@code q} busca en el producto, el lote y el pedido; {@code desde} y {@code hasta} acotan el dia
     * (uno solo con ambos iguales) y {@code cantidadMin} y {@code cantidadMax}, la cantidad.
     */
    @GetMapping
    public Movimientos listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                              @RequestParam(required = false) String tipo,
                              @RequestParam(required = false) @Size(max = 20) String pedido,
                              @RequestParam(required = false) @Size(max = 100) String q,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                              @RequestParam(required = false) BigDecimal cantidadMin,
                              @RequestParam(required = false) BigDecimal cantidadMax,
                              @RequestParam(defaultValue = "0") @Min(0) int pagina) {
        Grupo grupo = null;
        if (tipo != null && !tipo.isBlank()) {
            try {
                grupo = Grupo.valueOf(tipo.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ErrorNegocio.invalido("TIPO_INVALIDO", "Tipo de movimiento desconocido.");
            }
        }
        return movimientos.listar(s.userId(), new FiltroMovimientos(grupo, pedido, q, desde, hasta, cantidadMin, cantidadMax), pagina);
    }

    /**
     * CCPIP-121 (RF-59) y CCPIP-287 (RF-208): registra una entrada o salida en un lote de su punto.
     * 201 con el movimiento nuevo; 200 si la misma clave ya se habia registrado (no mueve nada).
     */
    @PostMapping
    public ResponseEntity<MovimientoRegistrado> registrar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                          @Valid @RequestBody DatosMovimiento datos) {
        MovimientoRegistrado r = manuales.registrarEnPunto(s.userId(), datos);
        return ResponseEntity.status(r.repetido() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }
}
