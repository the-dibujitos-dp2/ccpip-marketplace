package pe.ccpip.favoritos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;
import pe.ccpip.catalogo.CatalogoModelos.TiendaTarjeta;
import pe.ccpip.catalogo.TraductorCatalogo;
import pe.ccpip.comun.Idioma;
import pe.ccpip.favoritos.FavoritosModelos.ComunidadFavorita;
import pe.ccpip.favoritos.FavoritosModelos.Favoritos;
import pe.ccpip.favoritos.FavoritosModelos.ProductoFavorito;
import pe.ccpip.favoritos.FavoritosModelos.ProductorFavorito;

/**
 * Favoritos del consumidor de la sesion: productos, productores y comunidades. Guardar es idempotente (PUT);
 * retirar algo que no es su favorito responde 404, sin distinguir si es de otro consumidor (RNF-05).
 */
@RolesPermitidos(Rol.CONSUMIDOR)
@RestController
@RequestMapping("/api/cuenta/favoritos")
public class FavoritosController {

    private final FavoritosService service;
    private final TraductorCatalogo traductor;

    public FavoritosController(FavoritosService service, TraductorCatalogo traductor) {
        this.service = service;
        this.traductor = traductor;
    }

    /** CCPIP-327, CCPIP-385 y CCPIP-386: con los nombres de productos y tiendas en el idioma pedido. */
    @GetMapping
    public Favoritos listar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            HttpServletResponse response) {
        Idioma idioma = Idioma.deCabecera(acceptLanguage);
        response.setHeader(HttpHeaders.CONTENT_LANGUAGE, idioma.codigo());
        response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);
        return traducir(service.listar(s.userId()), idioma);
    }

    @PutMapping("/productos/{id}")
    public ResponseEntity<Void> guardarProducto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                @PathVariable long id) {
        service.guardarProducto(s.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/productos/{id}")
    public ResponseEntity<Void> retirarProducto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                @PathVariable long id) {
        service.retirarProducto(s.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/productores/{id}")
    public ResponseEntity<Void> guardarProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @PathVariable long id) {
        service.guardarProductor(s.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/productores/{id}")
    public ResponseEntity<Void> retirarProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @PathVariable long id) {
        service.retirarProductor(s.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /** `tiendaId`: una tienda de la comunidad; se guarda la comunidad, no la tienda. */
    @PutMapping("/comunidades/{tiendaId}")
    public ResponseEntity<Void> guardarComunidad(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @PathVariable long tiendaId) {
        service.guardarComunidad(s.userId(), tiendaId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/comunidades/{tiendaId}")
    public ResponseEntity<Void> retirarComunidad(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                                 @PathVariable long tiendaId) {
        service.retirarComunidad(s.userId(), tiendaId);
        return ResponseEntity.noContent().build();
    }

    private Favoritos traducir(Favoritos f, Idioma idioma) {
        if (idioma == Idioma.ES) {
            return f;
        }
        List<ProductoResumen> ofertas = traductor.resumenes(
                f.productos().stream().map(ProductoFavorito::oferta).filter(Objects::nonNull).toList(), idioma);
        List<TiendaTarjeta> tarjetas = new ArrayList<>(f.productores().stream().map(ProductorFavorito::tienda)
                .filter(Objects::nonNull).toList());
        f.comunidades().forEach(c -> tarjetas.addAll(c.tiendas()));
        List<TiendaTarjeta> traducidas = traductor.tarjetas(tarjetas, idioma);

        Map<Long, ProductoResumen> ofertaPorId = ofertas.stream().collect(Collectors.toMap(ProductoResumen::id, Function.identity()));
        Map<Long, TiendaTarjeta> tarjetaPorId = traducidas.stream()
                .collect(Collectors.toMap(TiendaTarjeta::id, Function.identity(), (a, b) -> a));
        return new Favoritos(
                f.productos().stream().map(p -> p.oferta() == null ? p
                        : new ProductoFavorito(p.id(), ofertaPorId.get(p.id()).nombre(), p.guardadoEn(), ofertaPorId.get(p.id()))).toList(),
                f.productores().stream().map(p -> p.tienda() == null ? p
                        : new ProductorFavorito(p.id(), tarjetaPorId.get(p.id()).nombre(), p.guardadoEn(), tarjetaPorId.get(p.id()))).toList(),
                f.comunidades().stream().map(c -> new ComunidadFavorita(c.tiendaId(), c.comunidad(), c.region(), c.guardadoEn(),
                        c.tiendas().stream().map(t -> tarjetaPorId.get(t.id())).toList())).toList());
    }
}
