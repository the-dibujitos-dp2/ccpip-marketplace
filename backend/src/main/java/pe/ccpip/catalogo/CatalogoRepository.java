package pe.ccpip.catalogo;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.ccpip.catalogo.CatalogoModelos.Codigo;
import pe.ccpip.catalogo.CatalogoModelos.Cosecha;
import pe.ccpip.catalogo.CatalogoModelos.Faceta;
import pe.ccpip.catalogo.CatalogoModelos.HistoriaPublica;
import pe.ccpip.catalogo.CatalogoModelos.Imagen;
import pe.ccpip.catalogo.CatalogoModelos.PartesHistoria;
import pe.ccpip.catalogo.CatalogoModelos.ProductoDetalle;
import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;
import pe.ccpip.catalogo.CatalogoModelos.TiendaResumen;
import pe.ccpip.imagenes.ImagenService;

/**
 * Consultas del catalogo publico. Un producto es visible si esta publicado y su tienda tambien.
 * Esta disponible si tiene lotes vigentes con saldo en un punto de acopio: lo que sigue en el
 * almacen del productor o ya vencio no se vende (CCPIP-41, RNF-15).
 */
@Repository
public class CatalogoRepository {

    static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /** Saldo vendible del producto p. */
    static final String EN_VENTA = """
            (SELECT coalesce(sum(l.cantidad_actual), 0) FROM lote l JOIN ubicacion u ON u.id = l.ubicacion_id
             WHERE l.producto_id = p.id AND u.tipo = 'PUNTO_ACOPIO'
               AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= %s))""".formatted(HOY);

    static final String VISIBLE = """
            FROM producto p
            JOIN productor pr ON pr.id = p.productor_id AND pr.activo AND pr.tienda_publicada
            LEFT JOIN linea_producto l ON l.id = p.linea_id
            LEFT JOIN unidad_medida um ON um.id = p.unidad_medida_id
            WHERE p.estado = 'PUBLICADO'
            """;

    private static final String COLUMNAS = """
            p.id, p.nombre, p.modalidad, p.precio, p.precio_mayorista, p.contenido,
            um.codigo AS um_codigo, um.nombre AS um_nombre, l.codigo AS l_codigo, l.nombre AS l_nombre,
            (SELECT i.id FROM imagen i WHERE i.producto_id = p.id AND i.principal) AS imagen,
            (SELECT array_agg(x.codigo ORDER BY x.orden) FROM producto_linea pl JOIN linea_producto x ON x.id = pl.linea_id
             WHERE pl.producto_id = p.id) AS lineas,
            %s > 0 AS disponible,
            pr.id AS pr_id, coalesce(pr.nombre_tienda, pr.comunidad) AS pr_nombre, pr.pueblo, pr.comunidad,
            pr.region, pr.localidad, pr.origen_verificado
            """.formatted(EN_VENTA);

    /** Criterios de CW-02. Las listas vacias no filtran. */
    public record Filtro(String termino, List<String> lineas, String modalidad, List<String> regiones, Long tienda,
                         BigDecimal precioMin, BigDecimal precioMax, boolean soloDisponibles) {

        Filtro sinFacetas() {
            return new Filtro(termino, List.of(), null, List.of(), null, null, null, soloDisponibles);
        }
    }

    public enum Orden { RELEVANCIA, PRECIO_ASC, PRECIO_DESC, NOVEDADES }

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long contar(Filtro f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) " + VISIBLE + where(f, p), p, Long.class);
        return total == null ? 0 : total;
    }

    public long contarComunidades(Filtro f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(DISTINCT pr.id) " + VISIBLE + where(f, p), p, Long.class);
        return total == null ? 0 : total;
    }

    public List<ProductoResumen> buscar(Filtro f, Orden orden, int pagina, int tamano) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = "SELECT " + COLUMNAS + VISIBLE + where(f, p) + " ORDER BY " + ordenSql(orden, f, p)
                + " LIMIT :limite OFFSET :desde";
        p.addValue("limite", tamano).addValue("desde", (long) pagina * tamano);
        return jdbc.query(sql, p, CatalogoRepository::resumen);
    }

    /** Conteos para los filtros de CW-02, sobre la busqueda sin los demas filtros. */
    public List<Faceta> faceta(Filtro f, String columna, String nombre) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        return jdbc.query("SELECT " + columna + " AS codigo, " + nombre + " AS nombre, count(*) AS total "
                        + VISIBLE + where(f.sinFacetas(), p) + " AND " + columna + " IS NOT NULL GROUP BY 1, 2 ORDER BY 3 DESC, 2",
                p, (rs, row) -> new Faceta(rs.getString("codigo"), rs.getString("nombre"), rs.getLong("total")));
    }

    /** Un producto puede estar en varias lineas: se cuenta en cada una (CCPIP-45). */
    public List<Faceta> facetaLineas(Filtro f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        return jdbc.query("""
                SELECT lp.codigo, lp.nombre, count(DISTINCT p.id) AS total
                """ + VISIBLE.replace("WHERE p.estado", """
                JOIN producto_linea pl ON pl.producto_id = p.id
                JOIN linea_producto lp ON lp.id = pl.linea_id
                WHERE p.estado""") + where(f.sinFacetas(), p) + " GROUP BY lp.codigo, lp.nombre, lp.orden ORDER BY lp.orden",
                p, (rs, row) -> new Faceta(rs.getString("codigo"), rs.getString("nombre"), rs.getLong("total")));
    }

    public List<Faceta> facetaModalidades(Filtro f) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        return jdbc.query("""
                SELECT m.codigo, m.nombre, count(*) AS total
                """ + VISIBLE.replace("WHERE p.estado", """
                JOIN (VALUES ('MINORISTA', 'Minorista (por unidad)'), ('MAYORISTA', 'Mayorista (por volumen)')) AS m(codigo, nombre)
                  ON p.modalidad IN (m.codigo, 'AMBAS')
                WHERE p.estado""") + where(f.sinFacetas(), p) + " GROUP BY m.codigo, m.nombre ORDER BY m.codigo DESC",
                p, (rs, row) -> new Faceta(rs.getString("codigo"), rs.getString("nombre"), rs.getLong("total")));
    }

    public Optional<ProductoDetalle> detalle(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        return jdbc.query("""
                SELECT %s, p.descripcion, p.cantidad_minima_mayorista
                %s AND p.id = :id""".formatted(COLUMNAS, VISIBLE), p,
                (rs, row) -> {
                    ProductoResumen r = resumen(rs, row);
                    return new ProductoDetalle(r.id(), r.nombre(), rs.getString("descripcion"), r.contenido(), r.unidad(),
                            r.linea(), lineas(r.id()), r.modalidad(), r.precio(), r.precioMayorista(),
                            (Integer) rs.getObject("cantidad_minima_mayorista"), r.disponible(), imagenes(r.id()),
                            r.tienda(), historia(r.id()), cosecha(r.id()), relacionados(r));
                }).stream().findFirst();
    }

    public List<ProductoResumen> deTienda(long tiendaId) {
        MapSqlParameterSource p = new MapSqlParameterSource("tienda", tiendaId);
        return jdbc.query("SELECT " + COLUMNAS + VISIBLE + " AND pr.id = :tienda ORDER BY p.destacado_tienda DESC, p.nombre",
                p, CatalogoRepository::resumen);
    }

    public List<ProductoResumen> destacados(int cuantos) {
        MapSqlParameterSource p = new MapSqlParameterSource("n", cuantos);
        return jdbc.query("SELECT " + COLUMNAS + VISIBLE + " AND " + EN_VENTA + " > 0"
                + " ORDER BY p.destacado_tienda DESC, p.publicado_en DESC NULLS LAST, p.id DESC LIMIT :n",
                p, CatalogoRepository::resumen);
    }

    public List<ProductoResumen> novedades(int cuantos) {
        MapSqlParameterSource p = new MapSqlParameterSource("n", cuantos);
        return jdbc.query("SELECT " + COLUMNAS + VISIBLE + " AND " + EN_VENTA + " > 0"
                + " ORDER BY p.publicado_en DESC NULLS LAST, p.id DESC LIMIT :n", p, CatalogoRepository::resumen);
    }

    // ---------- Partes de la ficha ----------

    private List<Codigo> lineas(long productoId) {
        return jdbc.query("""
                SELECT lp.codigo, lp.nombre FROM producto_linea pl JOIN linea_producto lp ON lp.id = pl.linea_id
                WHERE pl.producto_id = :id ORDER BY lp.orden""", new MapSqlParameterSource("id", productoId),
                (rs, row) -> new Codigo(rs.getString("codigo"), rs.getString("nombre")));
    }

    private List<Imagen> imagenes(long productoId) {
        return jdbc.query("SELECT id FROM imagen WHERE producto_id = :id ORDER BY principal DESC, orden, subida_en",
                new MapSqlParameterSource("id", productoId), (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Imagen(id.toString(), ImagenService.url(id), ImagenService.urlMiniatura(id));
                });
    }

    /** Solo la historia aprobada por el productor (CCPIP-49). */
    private HistoriaPublica historia(long productoId) {
        return jdbc.query("""
                SELECT titulo, texto_final, asistida, quienes, como, significado, cuidado
                FROM producto_historia WHERE producto_id = :id AND aprobada_en IS NOT NULL
                  AND texto_final IS NOT NULL AND btrim(texto_final) <> ''""",
                new MapSqlParameterSource("id", productoId), (rs, row) -> {
                    PartesHistoria partes = new PartesHistoria(rs.getString("quienes"), rs.getString("cuidado"),
                            rs.getString("como"), rs.getString("significado"));
                    boolean hayPartes = partes.comunidad() != null || partes.cultivo() != null
                            || partes.proceso() != null || partes.significado() != null;
                    return new HistoriaPublica(rs.getString("titulo"), rs.getString("texto_final"),
                            rs.getBoolean("asistida"), hayPartes ? partes : null);
                }).stream().findFirst().orElse(null);
    }

    /** Una agregacion siempre devuelve una fila; sin lotes vigentes no hay cosecha que mostrar. */
    private Cosecha cosecha(long productoId) {
        List<Cosecha> fila = jdbc.query("""
                SELECT max(l.fecha_produccion) AS produccion, min(l.fecha_vencimiento) AS vence
                FROM lote l JOIN ubicacion u ON u.id = l.ubicacion_id
                WHERE l.producto_id = :id AND l.cantidad_actual > 0 AND u.tipo = 'PUNTO_ACOPIO'
                  AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= %s)""".formatted(HOY),
                new MapSqlParameterSource("id", productoId), (rs, row) -> {
                    LocalDate produccion = rs.getObject("produccion", LocalDate.class);
                    LocalDate vence = rs.getObject("vence", LocalDate.class);
                    return produccion == null && vence == null ? null : new Cosecha(produccion, vence);
                });
        return fila.isEmpty() ? null : fila.get(0);
    }

    private List<ProductoResumen> relacionados(ProductoResumen r) {
        if (r.linea() == null) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", r.id()).addValue("linea", r.linea().codigo());
        return jdbc.query("SELECT " + COLUMNAS + VISIBLE + """
                 AND p.id <> :id AND EXISTS (SELECT 1 FROM producto_linea pl JOIN linea_producto x ON x.id = pl.linea_id
                                             WHERE pl.producto_id = p.id AND x.codigo = :linea)
                ORDER BY (%s > 0) DESC, p.publicado_en DESC NULLS LAST LIMIT 4""".formatted(EN_VENTA),
                p, CatalogoRepository::resumen);
    }

    // ---------- SQL dinamico ----------

    private static String where(Filtro f, MapSqlParameterSource p) {
        List<String> c = new ArrayList<>();
        if (f.termino() != null) {
            // RNF-22: insensible a mayusculas y tildes, en nombre y descripcion (CCPIP-52)
            p.addValue("patron", "%" + escapeLike(f.termino()) + "%");
            c.add("""
                    (lower(inmutable_unaccent(p.nombre)) LIKE lower(inmutable_unaccent(:patron)) ESCAPE '\\'
                     OR lower(inmutable_unaccent(coalesce(p.descripcion, ''))) LIKE lower(inmutable_unaccent(:patron)) ESCAPE '\\')""");
        }
        if (!f.lineas().isEmpty()) {
            p.addValue("lineas", f.lineas());
            c.add("""
                    EXISTS (SELECT 1 FROM producto_linea pl JOIN linea_producto x ON x.id = pl.linea_id
                            WHERE pl.producto_id = p.id AND x.codigo IN (:lineas))""");
        }
        if (f.modalidad() != null) {
            p.addValue("modalidad", f.modalidad());
            c.add("p.modalidad IN (:modalidad, 'AMBAS')");
        }
        if (!f.regiones().isEmpty()) {
            p.addValue("regiones", f.regiones());
            c.add("pr.region IN (:regiones)");
        }
        if (f.tienda() != null) {
            p.addValue("tienda", f.tienda());
            c.add("pr.id = :tienda");
        }
        if (f.precioMin() != null) {
            p.addValue("precioMin", f.precioMin());
            c.add("coalesce(p.precio, p.precio_mayorista) >= :precioMin");
        }
        if (f.precioMax() != null) {
            p.addValue("precioMax", f.precioMax());
            c.add("coalesce(p.precio, p.precio_mayorista) <= :precioMax");
        }
        if (f.soloDisponibles()) {
            c.add(EN_VENTA + " > 0");
        }
        return c.isEmpty() ? "" : " AND " + String.join(" AND ", c);
    }

    private static String ordenSql(Orden orden, Filtro f, MapSqlParameterSource p) {
        return switch (orden) {
            case PRECIO_ASC -> "coalesce(p.precio, p.precio_mayorista), p.nombre, p.id";
            case PRECIO_DESC -> "coalesce(p.precio, p.precio_mayorista) DESC, p.nombre, p.id";
            case NOVEDADES -> "p.publicado_en DESC NULLS LAST, p.id DESC";
            // Relevancia: primero lo que coincide en el nombre y lo disponible
            case RELEVANCIA -> (f.termino() != null
                    ? "(lower(inmutable_unaccent(p.nombre)) LIKE lower(inmutable_unaccent(:patron)) ESCAPE '\\') DESC, "
                    : "") + "(" + EN_VENTA + " > 0) DESC, p.nombre, p.id";
        };
    }

    /** Un "%" o "_" escrito por el usuario se busca literal, no como comodin. */
    static String escapeLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static List<String> lineas(java.sql.Array arreglo) throws SQLException {
        return arreglo == null ? List.of() : List.of((String[]) arreglo.getArray());
    }

    static ProductoResumen resumen(ResultSet rs, int row) throws SQLException {
        Object imagen = rs.getObject("imagen");
        return new ProductoResumen(
                rs.getLong("id"),
                rs.getString("nombre"),
                rs.getString("modalidad"),
                rs.getBigDecimal("precio"),
                rs.getBigDecimal("precio_mayorista"),
                rs.getString("contenido"),
                rs.getString("um_codigo") == null ? null : new Codigo(rs.getString("um_codigo"), rs.getString("um_nombre")),
                rs.getString("l_codigo") == null ? null : new Codigo(rs.getString("l_codigo"), rs.getString("l_nombre")),
                lineas(rs.getArray("lineas")),
                imagen == null ? null : ImagenService.urlMiniatura((UUID) imagen),
                rs.getBoolean("disponible"),
                new TiendaResumen(rs.getLong("pr_id"), rs.getString("pr_nombre"), rs.getString("pueblo"),
                        rs.getString("comunidad"), rs.getString("region"), rs.getString("localidad"),
                        rs.getBoolean("origen_verificado")));
    }
}
