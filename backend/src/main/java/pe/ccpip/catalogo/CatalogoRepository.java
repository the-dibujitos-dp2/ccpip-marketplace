package pe.ccpip.catalogo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import pe.ccpip.catalogo.CatalogoModelos.Codigo;
import pe.ccpip.catalogo.CatalogoModelos.ProductoDetalle;
import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;
import pe.ccpip.catalogo.CatalogoModelos.ProductorResumen;

@Repository
public class CatalogoRepository {

    private static final String COLUMNAS = """
            p.id, p.nombre, p.precio,
            um.codigo AS um_codigo, um.nombre AS um_nombre, l.codigo AS l_codigo, l.nombre AS l_nombre,
            pr.id AS pr_id, pr.comunidad, pr.region, pr.origen_verificado
            """;

    /**
     * Solo productos publicados, de productores activos y con stock (RNF-15: el stock que ve el
     * consumidor es la suma de todas las ubicaciones).
     */
    private static final String VISIBLES_CON_STOCK = """
            FROM producto p
            JOIN productor pr ON pr.id = p.productor_id AND pr.activo
            JOIN linea_producto l ON l.id = p.linea_id
            JOIN unidad_medida um ON um.id = p.unidad_medida_id
            WHERE p.estado = 'PUBLICADO'
              AND (SELECT coalesce(sum(s.cantidad), 0) FROM stock s WHERE s.producto_id = p.id) > 0
            """;

    /** RNF-22: misma expresion que el indice producto_busqueda_idx, insensible a mayusculas y tildes. */
    private static final String FILTRO_TEXTO = """
              AND (lower(inmutable_unaccent(p.nombre)) LIKE ? ESCAPE '\\'
                   OR lower(inmutable_unaccent(coalesce(p.descripcion, ''))) LIKE ? ESCAPE '\\')
            """;

    private final JdbcTemplate jdbc;

    public CatalogoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long contar(String termino) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT count(*) " + VISIBLES_CON_STOCK + filtro(termino, args);
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public List<ProductoResumen> buscar(String termino, int pagina, int tamano) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT " + COLUMNAS + VISIBLES_CON_STOCK + filtro(termino, args)
                + " ORDER BY p.nombre, p.id LIMIT ? OFFSET ?";
        args.add(tamano);
        args.add((long) pagina * tamano);
        return jdbc.query(sql, CatalogoRepository::resumen, args.toArray());
    }

    public Optional<ProductoDetalle> detalle(long id) {
        return jdbc.query("""
                SELECT p.id, p.nombre, p.descripcion, p.historia_origen, p.precio,
                       um.codigo AS um_codigo, um.nombre AS um_nombre, l.codigo AS l_codigo, l.nombre AS l_nombre,
                       pr.id AS pr_id, pr.comunidad, pr.region, pr.origen_verificado,
                       (SELECT coalesce(sum(s.cantidad), 0) FROM stock s WHERE s.producto_id = p.id) > 0 AS disponible
                FROM producto p
                JOIN productor pr ON pr.id = p.productor_id AND pr.activo
                JOIN linea_producto l ON l.id = p.linea_id
                JOIN unidad_medida um ON um.id = p.unidad_medida_id
                WHERE p.id = ? AND p.estado = 'PUBLICADO'""",
                (rs, row) -> new ProductoDetalle(
                        rs.getLong("id"),
                        rs.getString("nombre"),
                        rs.getString("descripcion"),
                        blankToNull(rs.getString("historia_origen")),
                        rs.getBigDecimal("precio"),
                        new Codigo(rs.getString("um_codigo"), rs.getString("um_nombre")),
                        new Codigo(rs.getString("l_codigo"), rs.getString("l_nombre")),
                        productor(rs),
                        rs.getBoolean("disponible")),
                id).stream().findFirst();
    }

    private static String filtro(String termino, List<Object> args) {
        if (termino == null) {
            return "";
        }
        String patron = "%" + escapeLike(termino) + "%";
        args.add(patron);
        args.add(patron);
        // El patron tambien pasa por unaccent/lower en SQL para que "CAFÉ" encuentre "Cafe"
        return FILTRO_TEXTO.replace("LIKE ?", "LIKE lower(inmutable_unaccent(?))");
    }

    /** Un "%" o "_" escrito por el usuario se busca literal, no como comodin. */
    static String escapeLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ProductoResumen resumen(ResultSet rs, int row) throws SQLException {
        return new ProductoResumen(
                rs.getLong("id"),
                rs.getString("nombre"),
                rs.getBigDecimal("precio"),
                new Codigo(rs.getString("um_codigo"), rs.getString("um_nombre")),
                new Codigo(rs.getString("l_codigo"), rs.getString("l_nombre")),
                productor(rs));
    }

    private static ProductorResumen productor(ResultSet rs) throws SQLException {
        return new ProductorResumen(rs.getLong("pr_id"), rs.getString("comunidad"), rs.getString("region"),
                rs.getBoolean("origen_verificado"));
    }

    private static String blankToNull(String texto) {
        return texto == null || texto.isBlank() ? null : texto;
    }
}
