package pe.ccpip.favoritos;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;
import pe.ccpip.catalogo.CatalogoModelos.TiendaTarjeta;
import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.catalogo.TiendasRepository;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.favoritos.FavoritosModelos.ComunidadFavorita;
import pe.ccpip.favoritos.FavoritosModelos.Favoritos;
import pe.ccpip.favoritos.FavoritosModelos.ProductoFavorito;
import pe.ccpip.favoritos.FavoritosModelos.ProductorFavorito;

/**
 * Favoritos del consumidor: productos (CCPIP-177, CCPIP-327, CCPIP-396), productores (CCPIP-368, CCPIP-385,
 * CCPIP-397) y comunidades (CCPIP-369, CCPIP-386, CCPIP-398). Solo los propios: el consumidor sale de la
 * sesion y nunca de la peticion (RNF-05). Un favorito no registra cantidad, intencion de compra, pago ni
 * reserva, y retirarlo no toca el producto, la tienda ni la comunidad.
 */
@Service
public class FavoritosService {

    private static final String FAVORITO_NO_ENCONTRADO = "FAVORITO_NO_ENCONTRADO";
    private static final String NO_ES_FAVORITO = "No lo tienes en tus favoritos.";

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogoRepository catalogo;
    private final TiendasRepository tiendas;

    public FavoritosService(NamedParameterJdbcTemplate jdbc, CatalogoRepository catalogo, TiendasRepository tiendas) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
        this.tiendas = tiendas;
    }

    /** CCPIP-177: guarda una oferta visible en el catalogo. Guardarla otra vez no la duplica. */
    @Transactional
    public void guardarProducto(long usuarioId, long productoId) {
        if (catalogo.resumenes(List.of(productoId)).isEmpty()) {
            throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o no está publicado");
        }
        jdbc.update("""
                INSERT INTO favorito_producto (usuario_id, producto_id) VALUES (:usuario, :producto)
                ON CONFLICT DO NOTHING""", new MapSqlParameterSource("usuario", usuarioId).addValue("producto", productoId));
    }

    /** CCPIP-396: retira el producto de sus favoritos; el producto no cambia. */
    @Transactional
    public void retirarProducto(long usuarioId, long productoId) {
        int borrados = jdbc.update("DELETE FROM favorito_producto WHERE usuario_id = :usuario AND producto_id = :producto",
                new MapSqlParameterSource("usuario", usuarioId).addValue("producto", productoId));
        if (borrados == 0) {
            throw ErrorNegocio.noEncontrado(FAVORITO_NO_ENCONTRADO, NO_ES_FAVORITO);
        }
    }

    /** CCPIP-368: guarda un productor con tienda publicada. Guardarlo otra vez no lo duplica. */
    @Transactional
    public void guardarProductor(long usuarioId, long productorId) {
        exigirTienda(productorId);
        jdbc.update("""
                INSERT INTO favorito_productor (usuario_id, productor_id) VALUES (:usuario, :productor)
                ON CONFLICT DO NOTHING""", new MapSqlParameterSource("usuario", usuarioId).addValue("productor", productorId));
    }

    /** CCPIP-397: retira el productor de sus favoritos; su cuenta y su tienda no cambian. */
    @Transactional
    public void retirarProductor(long usuarioId, long productorId) {
        int borrados = jdbc.update("DELETE FROM favorito_productor WHERE usuario_id = :usuario AND productor_id = :productor",
                new MapSqlParameterSource("usuario", usuarioId).addValue("productor", productorId));
        if (borrados == 0) {
            throw ErrorNegocio.noEncontrado(FAVORITO_NO_ENCONTRADO, NO_ES_FAVORITO);
        }
    }

    /**
     * CCPIP-369: guarda la comunidad de una tienda publicada. La comunidad se reconoce por su nombre: guardarla
     * desde otra tienda de la misma comunidad no la duplica.
     */
    @Transactional
    public void guardarComunidad(long usuarioId, long tiendaId) {
        exigirTienda(tiendaId);
        jdbc.update("""
                INSERT INTO favorito_comunidad (usuario_id, productor_id, comunidad)
                SELECT :usuario, pr.id, btrim(pr.comunidad) FROM productor pr WHERE pr.id = :tienda
                ON CONFLICT (usuario_id, lower(inmutable_unaccent(btrim(comunidad)))) DO NOTHING""",
                new MapSqlParameterSource("usuario", usuarioId).addValue("tienda", tiendaId));
    }

    /** CCPIP-398: retira de sus favoritos la comunidad de esa tienda; la comunidad no cambia. */
    @Transactional
    public void retirarComunidad(long usuarioId, long tiendaId) {
        int borrados = jdbc.update("""
                DELETE FROM favorito_comunidad f USING productor pr
                WHERE f.usuario_id = :usuario AND pr.id = :tienda
                  AND lower(inmutable_unaccent(btrim(f.comunidad))) = lower(inmutable_unaccent(btrim(pr.comunidad)))""",
                new MapSqlParameterSource("usuario", usuarioId).addValue("tienda", tiendaId));
        if (borrados == 0) {
            throw ErrorNegocio.noEncontrado(FAVORITO_NO_ENCONTRADO, NO_ES_FAVORITO);
        }
    }

    /** CCPIP-327, CCPIP-385 y CCPIP-386: sus favoritos, el ultimo guardado primero. */
    @Transactional(readOnly = true)
    public Favoritos listar(long usuarioId) {
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId);

        record Fila(long id, String nombre, OffsetDateTime guardadoEn) {
        }
        List<Fila> productos = jdbc.query("""
                SELECT p.id, p.nombre, f.guardado_en FROM favorito_producto f JOIN producto p ON p.id = f.producto_id
                WHERE f.usuario_id = :usuario ORDER BY f.guardado_en DESC, p.id DESC""", p,
                (rs, row) -> new Fila(rs.getLong("id"), rs.getString("nombre"), rs.getObject("guardado_en", OffsetDateTime.class)));
        Map<Long, ProductoResumen> ofertas = catalogo.resumenes(productos.stream().map(Fila::id).toList());

        List<Fila> productores = jdbc.query("""
                SELECT pr.id, coalesce(pr.nombre_tienda, pr.comunidad) AS nombre, f.guardado_en
                FROM favorito_productor f JOIN productor pr ON pr.id = f.productor_id
                WHERE f.usuario_id = :usuario ORDER BY f.guardado_en DESC, pr.id DESC""", p,
                (rs, row) -> new Fila(rs.getLong("id"), rs.getString("nombre"), rs.getObject("guardado_en", OffsetDateTime.class)));
        Map<Long, TiendaTarjeta> tarjetas = tiendas.tarjetas(productores.stream().map(Fila::id).toList());

        List<ComunidadFavorita> comunidades = jdbc.query("""
                SELECT f.productor_id, f.comunidad, pr.region, f.guardado_en
                FROM favorito_comunidad f JOIN productor pr ON pr.id = f.productor_id
                WHERE f.usuario_id = :usuario ORDER BY f.guardado_en DESC, f.id DESC""", p,
                (rs, row) -> new ComunidadFavorita(rs.getLong("productor_id"), rs.getString("comunidad"),
                        rs.getString("region"), rs.getObject("guardado_en", OffsetDateTime.class),
                        tiendas.deComunidad(rs.getString("comunidad"))));

        return new Favoritos(
                productos.stream().map(f -> new ProductoFavorito(f.id(), f.nombre(), f.guardadoEn(), ofertas.get(f.id()))).toList(),
                productores.stream().map(f -> new ProductorFavorito(f.id(), f.nombre(), f.guardadoEn(), tarjetas.get(f.id()))).toList(),
                comunidades);
    }

    private void exigirTienda(long productorId) {
        if (!tiendas.publicada(productorId)) {
            throw ErrorNegocio.noEncontrado("TIENDA_NO_ENCONTRADA", "La tienda no existe o aún no está publicada");
        }
    }
}
