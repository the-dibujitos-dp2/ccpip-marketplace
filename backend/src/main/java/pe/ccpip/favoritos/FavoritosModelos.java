package pe.ccpip.favoritos;

import java.time.OffsetDateTime;
import java.util.List;

import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;
import pe.ccpip.catalogo.CatalogoModelos.TiendaTarjeta;

/** Lo que el consumidor ve de sus favoritos (CCPIP-327, CCPIP-385, CCPIP-386). */
public final class FavoritosModelos {

    private FavoritosModelos() {
    }

    /** Los tres tipos de favoritos del consumidor de la sesion, el ultimo guardado primero. */
    public record Favoritos(List<ProductoFavorito> productos, List<ProductorFavorito> productores,
                            List<ComunidadFavorita> comunidades) {
    }

    /** `oferta` es null si el producto ya no esta publicado: el favorito se conserva hasta que lo retire. */
    public record ProductoFavorito(long id, String nombre, OffsetDateTime guardadoEn, ProductoResumen oferta) {
    }

    /** `tienda` es null si la tienda ya no esta publicada. */
    public record ProductorFavorito(long id, String nombre, OffsetDateTime guardadoEn, TiendaTarjeta tienda) {
    }

    /** `tiendaId`: la tienda desde la que se guardo. `tiendas`: las publicadas hoy de esa comunidad. */
    public record ComunidadFavorita(long tiendaId, String comunidad, String region, OffsetDateTime guardadoEn,
                                    List<TiendaTarjeta> tiendas) {
    }
}
