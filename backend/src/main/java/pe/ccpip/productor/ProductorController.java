package pe.ccpip.productor;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.ia.AsistenteRedaccion.Propuesta;
import pe.ccpip.ia.AsistenteRedaccion.Variante;
import pe.ccpip.inventario.InventarioModelos.DatosMovimiento;
import pe.ccpip.inventario.InventarioModelos.MovimientoRegistrado;
import pe.ccpip.inventario.MovimientoManualService;
import pe.ccpip.productor.InventarioProductorModelos.Inventario;
import pe.ccpip.productor.PedidosProductorModelos.Pedidos;
import pe.ccpip.productor.ProductoModelos.AprobarHistoria;
import pe.ccpip.productor.ProductoModelos.DatosHistoria;
import pe.ccpip.productor.ProductoModelos.DatosLote;
import pe.ccpip.productor.ProductoModelos.DatosProducto;
import pe.ccpip.productor.ProductoModelos.Estado;
import pe.ccpip.productor.ProductoModelos.Historia;
import pe.ccpip.productor.ProductoModelos.ListaProductos;
import pe.ccpip.productor.ProductoModelos.Lotes;
import pe.ccpip.productor.ProductoModelos.ProductoEdicion;
import pe.ccpip.productor.ProductoModelos.Ubicacion;

/**
 * API del productor: panel, productos, historia, lotes, inventario, pedidos y redaccion asistida.
 * Todo opera sobre el productor de la sesion (RNF-05). No existe DELETE de productos (RNF-17).
 */
@RolesPermitidos(Rol.PRODUCTOR)
@RestController
@RequestMapping("/api/productor")
public class ProductorController {

    public record PedirDescripcion(Long productoId,
                                   @NotBlank(message = "Escribe el nombre del producto") @Size(max = 200) String nombre,
                                   List<String> lineas, @Size(max = 40) String contenido, @Size(max = 500) String notas) {
    }

    public record PedirHistoria(@Valid DatosHistoria respuestas, Variante variante) {
    }

    private final ProductorActual actual;
    private final ProductosService productos;
    private final HistoriaService historias;
    private final LotesService lotes;
    private final PanelService panel;
    private final PedidosProductorService pedidos;
    private final InventarioProductorService inventario;
    private final MovimientoManualService movimientos;

    public ProductorController(ProductorActual actual, ProductosService productos, HistoriaService historias,
                               LotesService lotes, PanelService panel, PedidosProductorService pedidos,
                               InventarioProductorService inventario, MovimientoManualService movimientos) {
        this.actual = actual;
        this.productos = productos;
        this.historias = historias;
        this.lotes = lotes;
        this.panel = panel;
        this.pedidos = pedidos;
        this.inventario = inventario;
        this.movimientos = movimientos;
    }

    @GetMapping("/panel")
    public PanelService.Panel panel(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return panel.obtener(actual.id(s));
    }

    @GetMapping("/productos")
    public ListaProductos listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @RequestParam(defaultValue = "PUBLICADO") Estado estado,
                                 @RequestParam(required = false) @Size(max = 100) String q) {
        return productos.listar(actual.id(s), estado, q);
    }

    @GetMapping("/productos/{id}")
    public ProductoEdicion obtener(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @PathVariable long id) {
        return productos.obtener(actual.id(s), id);
    }

    @PostMapping("/productos")
    public ResponseEntity<ProductoEdicion> crear(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @Valid @RequestBody DatosProducto datos) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productos.crear(actual.id(s), s.userId(), datos));
    }

    @PutMapping("/productos/{id}")
    public ProductoEdicion editar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @PathVariable long id, @Valid @RequestBody DatosProducto datos) {
        return productos.editar(actual.id(s), s.userId(), id, datos);
    }

    @PostMapping("/productos/{id}/publicar")
    public ProductoEdicion publicar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @PathVariable long id) {
        return productos.publicar(actual.id(s), s.userId(), id);
    }

    @PostMapping("/productos/{id}/retirar")
    public ProductoEdicion retirar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @PathVariable long id) {
        return productos.retirar(actual.id(s), s.userId(), id);
    }

    // ---------- Historia (PW-05) ----------

    @PutMapping("/productos/{id}/historia")
    public Historia guardarHistoria(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @PathVariable long id, @Valid @RequestBody DatosHistoria datos) {
        return historias.guardar(actual.id(s), id, datos);
    }

    @PostMapping("/productos/{id}/historia/propuesta")
    public Propuesta proponerHistoria(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                      @PathVariable long id, @Valid @RequestBody PedirHistoria pedido) {
        return historias.proponer(actual.id(s), id, pedido.respuestas(), pedido.variante());
    }

    @PostMapping("/productos/{id}/historia/aprobar")
    public Historia aprobarHistoria(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @PathVariable long id, @Valid @RequestBody AprobarHistoria datos) {
        return historias.aprobar(actual.id(s), s.userId(), id, datos);
    }

    /** CCPIP-280: descartar el borrador de la IA; queda registrado y no se publica nada. */
    @PostMapping("/productos/{id}/ia/rechazar")
    public ResponseEntity<Void> rechazarBorrador(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @PathVariable long id, @RequestParam HistoriaService.Borrador borrador) {
        historias.rechazar(actual.id(s), s.userId(), id, borrador);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ia/descripcion")
    public Propuesta proponerDescripcion(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                         @Valid @RequestBody PedirDescripcion pedido) {
        return historias.proponerDescripcion(actual.id(s), pedido.productoId(), pedido.nombre(), pedido.lineas(),
                pedido.contenido(), pedido.notas());
    }

    // ---------- Lotes (PW-06) ----------

    @GetMapping("/ubicaciones")
    public List<Ubicacion> ubicaciones(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return lotes.ubicaciones(actual.id(s));
    }

    @GetMapping("/productos/{id}/lotes")
    public Lotes lotes(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s, @PathVariable long id) {
        return lotes.listar(actual.id(s), id);
    }

    @PostMapping("/productos/{id}/lotes")
    public ResponseEntity<Lotes> registrarLote(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                               @PathVariable long id, @Valid @RequestBody DatosLote datos) {
        return ResponseEntity.status(HttpStatus.CREATED).body(lotes.registrar(actual.id(s), s.userId(), id, datos));
    }

    /** CCPIP-136 y CCPIP-162 (PW-16): su almacen virtual unico, por producto, ubicacion, lote y estado. */
    @GetMapping("/inventario")
    public Inventario inventario(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return inventario.obtener(actual.id(s));
    }

    /**
     * CCPIP-283 (RF-203, PW-16): entrada o salida en un lote de su almacen virtual unico. 201 con el
     * movimiento nuevo; 200 si la misma clave ya se habia registrado (no mueve nada).
     */
    @PostMapping("/almacen/movimientos")
    public ResponseEntity<MovimientoRegistrado> registrarMovimiento(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                                    @Valid @RequestBody DatosMovimiento datos) {
        MovimientoRegistrado r = movimientos.registrarEnAlmacen(actual.id(s), s.userId(), datos);
        return ResponseEntity.status(r.repetido() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    /** CCPIP-66 (PW-07): pedidos con publicaciones propias; sin {@code estado}, todos los pagados. */
    @GetMapping("/pedidos")
    public Pedidos pedidos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                           @RequestParam(required = false) PedidosProductorModelos.Estado estado) {
        return pedidos.listar(actual.id(s), estado);
    }
}
