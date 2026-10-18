package pe.ccpip.catalogomaestro;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.DatosLinea;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.DatosProductoMaestro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.EstadoFiltro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.Linea;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.Lineas;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.NuevaLinea;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.NuevoProductoMaestro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.ProductoMaestro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.ProductosMaestros;

/**
 * Catalogo maestro (Figma, modulo "lineas" del superadministrador). Lo administra la Camara a traves del
 * superadministrador: el sub administrador, el productor y los demas roles reciben 403 (RNF-04, RNF-05).
 */
@RestController
@RequestMapping("/api/admin/catalogo")
@RolesPermitidos(Rol.SUPER_ADMIN)
public class CatalogoMaestroController {

    private final LineasMaestrasService lineas;
    private final ProductosMaestrosService productos;

    public CatalogoMaestroController(LineasMaestrasService lineas, ProductosMaestrosService productos) {
        this.lineas = lineas;
        this.productos = productos;
    }

    // --- Lineas (CCPIP-96, 309, 310, 311) ---

    @GetMapping("/lineas")
    public Lineas lineas(@RequestParam(defaultValue = "TODOS") EstadoFiltro estado,
                         @RequestParam(required = false) String q) {
        return lineas.listar(estado, q);
    }

    @PostMapping("/lineas")
    @ResponseStatus(HttpStatus.CREATED)
    public Linea crearLinea(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                            @Valid @RequestBody NuevaLinea nueva) {
        return lineas.crear(s.userId(), nueva);
    }

    @PutMapping("/lineas/{id}")
    public Linea modificarLinea(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @PathVariable int id, @Valid @RequestBody DatosLinea datos) {
        return lineas.modificar(s.userId(), id, datos);
    }

    @PostMapping("/lineas/{id}/desactivar")
    public Linea desactivarLinea(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @PathVariable int id) {
        return lineas.desactivar(s.userId(), id);
    }

    @PostMapping("/lineas/{id}/reactivar")
    public Linea reactivarLinea(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                @PathVariable int id) {
        return lineas.reactivar(s.userId(), id);
    }

    // --- Productos (CCPIP-351, 312, 313, 314) ---

    @GetMapping("/productos")
    public ProductosMaestros productos(@RequestParam(defaultValue = "TODOS") EstadoFiltro estado,
                                       @RequestParam(required = false) String linea,
                                       @RequestParam(required = false) String q) {
        return productos.listar(estado, linea, q);
    }

    @PostMapping("/productos")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductoMaestro crearProducto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                         @Valid @RequestBody NuevoProductoMaestro nuevo) {
        return productos.crear(s.userId(), nuevo);
    }

    @PutMapping("/productos/{id}")
    public ProductoMaestro modificarProducto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                             @PathVariable long id, @Valid @RequestBody DatosProductoMaestro datos) {
        return productos.modificar(s.userId(), id, datos);
    }

    @PostMapping("/productos/{id}/desactivar")
    public ProductoMaestro desactivarProducto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                              @PathVariable long id) {
        return productos.desactivar(s.userId(), id);
    }

    @PostMapping("/productos/{id}/reactivar")
    public ProductoMaestro reactivarProducto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                             @PathVariable long id) {
        return productos.reactivar(s.userId(), id);
    }
}
