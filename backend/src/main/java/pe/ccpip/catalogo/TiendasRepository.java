package pe.ccpip.catalogo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.ccpip.catalogo.CatalogoModelos.Faceta;
import pe.ccpip.catalogo.CatalogoModelos.GaleriaItem;
import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;
import pe.ccpip.catalogo.CatalogoModelos.TiendaDetalle;
import pe.ccpip.catalogo.CatalogoModelos.TiendaTarjeta;
import pe.ccpip.imagenes.ImagenService;
import pe.ccpip.video.VideosService;

/** Tiendas publicadas de las comunidades (CCPIP-153, CCPIP-154, CCPIP-56). */
@Repository
public class TiendasRepository {

    private static final String TARJETA = """
            SELECT pr.id, coalesce(pr.nombre_tienda, pr.comunidad) AS nombre, pr.pueblo, pr.comunidad, pr.region,
                   pr.localidad, pr.cita, pr.origen_verificado,
                   (SELECT i.id FROM imagen i WHERE i.productor_id = pr.id AND i.tipo = 'PORTADA') AS portada,
                   (SELECT count(*) FROM producto p WHERE p.productor_id = pr.id AND p.estado = 'PUBLICADO') AS productos
            FROM productor pr
            WHERE pr.activo AND pr.tienda_publicada
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogoRepository catalogo;
    private final VideosService videos;

    public TiendasRepository(NamedParameterJdbcTemplate jdbc, CatalogoRepository catalogo, VideosService videos) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
        this.videos = videos;
    }

    public List<TiendaTarjeta> listar(Integer limite) {
        MapSqlParameterSource p = new MapSqlParameterSource("limite", limite == null ? 1000 : limite);
        return jdbc.query(TARJETA + " ORDER BY pr.origen_verificado DESC, productos DESC, nombre LIMIT :limite", p,
                TiendasRepository::tarjeta);
    }

    /** CCPIP-385: las tiendas aun publicadas entre `ids`, por id. */
    public Map<Long, TiendaTarjeta> tarjetas(Collection<Long> ids) {
        Map<Long, TiendaTarjeta> tarjetas = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.query(TARJETA + " AND pr.id IN (:ids)", new MapSqlParameterSource("ids", ids),
                    (RowCallbackHandler) rs -> {
                        TiendaTarjeta t = tarjeta(rs, 0);
                        tarjetas.put(t.id(), t);
                    });
        }
        return tarjetas;
    }

    /** CCPIP-386: las tiendas publicadas de una comunidad, sin distinguir mayusculas, tildes ni espacios al borde. */
    public List<TiendaTarjeta> deComunidad(String comunidad) {
        return jdbc.query(TARJETA + """
                 AND lower(inmutable_unaccent(btrim(pr.comunidad))) = lower(inmutable_unaccent(btrim(:comunidad)))
                ORDER BY nombre""", new MapSqlParameterSource("comunidad", comunidad), TiendasRepository::tarjeta);
    }

    private static TiendaTarjeta tarjeta(ResultSet rs, int row) throws SQLException {
        Object portada = rs.getObject("portada");
        return new TiendaTarjeta(rs.getLong("id"), rs.getString("nombre"), rs.getString("pueblo"),
                rs.getString("comunidad"), rs.getString("region"), rs.getString("localidad"),
                rs.getString("cita"), rs.getBoolean("origen_verificado"),
                portada == null ? null : ImagenService.url((UUID) portada), rs.getLong("productos"));
    }

    /** CCPIP-363: la comunidad existe, esta activa y tiene su tienda publicada. */
    public boolean publicada(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM productor pr WHERE pr.id = :id AND pr.activo AND pr.tienda_publicada)""",
                new MapSqlParameterSource("id", id), Boolean.class));
    }

    public Optional<TiendaDetalle> detalle(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        return jdbc.query("""
                SELECT pr.*, (SELECT i.id FROM imagen i WHERE i.productor_id = pr.id AND i.tipo = 'PORTADA') AS portada
                FROM productor pr WHERE pr.id = :id AND pr.activo AND pr.tienda_publicada""", p,
                (rs, row) -> {
                    List<ProductoResumen> productos = catalogo.deTienda(id);
                    Object portada = rs.getObject("portada");
                    OffsetDateTime desde = rs.getObject("tienda_publicada_en", OffsetDateTime.class);
                    return new TiendaDetalle(id, coalesce(rs.getString("nombre_tienda"), rs.getString("comunidad")),
                            rs.getString("descripcion_corta"), rs.getString("historia"), rs.getString("cita"),
                            rs.getString("pueblo"), rs.getString("comunidad"), rs.getString("region"),
                            rs.getString("localidad"), (Integer) rs.getObject("socios"), rs.getBoolean("origen_verificado"),
                            desde == null ? null : desde.getYear(),
                            portada == null ? null : ImagenService.url((UUID) portada), galeria(id),
                            videos.publicados(id), lineas(id), productos, destacados(id), null);
                }).stream().findFirst();
    }

    private List<GaleriaItem> galeria(long productorId) {
        return jdbc.query("""
                SELECT id, categoria FROM imagen WHERE productor_id = :id AND tipo = 'GALERIA' ORDER BY orden, subida_en""",
                new MapSqlParameterSource("id", productorId), (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new GaleriaItem(id.toString(), rs.getString("categoria"), ImagenService.url(id),
                            ImagenService.urlMiniatura(id));
                });
    }

    /** Pestanas de CW-04: lineas con conteo de productos publicados de la tienda. */
    private List<Faceta> lineas(long productorId) {
        return jdbc.query("""
                SELECT lp.codigo, lp.nombre, count(DISTINCT p.id) AS total
                FROM producto p JOIN producto_linea pl ON pl.producto_id = p.id JOIN linea_producto lp ON lp.id = pl.linea_id
                WHERE p.productor_id = :id AND p.estado = 'PUBLICADO'
                GROUP BY lp.codigo, lp.nombre, lp.orden ORDER BY lp.orden""",
                new MapSqlParameterSource("id", productorId),
                (rs, row) -> new Faceta(rs.getString("codigo"), rs.getString("nombre"), rs.getLong("total")));
    }

    private List<Long> destacados(long productorId) {
        return jdbc.queryForList("""
                SELECT id FROM producto WHERE productor_id = :id AND estado = 'PUBLICADO' AND destacado_tienda ORDER BY nombre""",
                new MapSqlParameterSource("id", productorId), Long.class);
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : b;
    }
}
