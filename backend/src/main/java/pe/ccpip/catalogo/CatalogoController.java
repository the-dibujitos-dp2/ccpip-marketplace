package pe.ccpip.catalogo;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.Publico;
import pe.ccpip.catalogo.CatalogoModelos.Pagina;
import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;

/** Catalogo publico: no requiere sesion (CCPIP-53). */
@Publico
@RestController
@RequestMapping("/api/productos")
public class CatalogoController {

    private final CatalogoRepository repository;

    public CatalogoController(CatalogoRepository repository) {
        this.repository = repository;
    }

    /**
     * CCPIP-52: busca en nombre y descripcion, sin distinguir mayusculas ni tildes, paginado.
     * Sin termino lista todo el catalogo disponible.
     */
    @GetMapping
    public Pagina<ProductoResumen> buscar(
            @RequestParam(name = "q", required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "12") @Min(1) @Max(48) int tamano) {
        String termino = q == null || q.isBlank() ? null : q.trim();
        long total = repository.contar(termino);
        return Pagina.of(repository.buscar(termino, pagina, tamano), pagina, tamano, total);
    }

    /** CCPIP-53 y CCPIP-152: ficha con historia de origen. Un producto sin stock se ve igual. */
    @GetMapping("/{id}")
    public ResponseEntity<?> detalle(@PathVariable long id) {
        return repository.detalle(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", "PRODUCTO_NO_ENCONTRADO",
                        "error", "El producto no existe o ya no esta disponible")));
    }
}
