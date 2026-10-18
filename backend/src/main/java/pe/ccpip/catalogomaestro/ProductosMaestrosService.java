package pe.ccpip.catalogomaestro;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogomaestro.CatalogoComun.Accion;
import pe.ccpip.catalogomaestro.CatalogoComun.Entidad;
import pe.ccpip.catalogomaestro.CatalogoComun.Tabla;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.DatosProductoMaestro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.EstadoFiltro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.LineaDelProducto;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.NuevoProductoMaestro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.ProductoMaestro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.ProductosMaestros;
import pe.ccpip.comun.ErrorNegocio;

/**
 * Productos del catalogo maestro de CCPIP-42 ({@code producto_maestro}): consultar (CCPIP-351), crear (CCPIP-312), modificar sin alterar ofertas
 * historicas (CCPIP-313) y desactivar conservando referencias historicas (CCPIP-314).
 *
 * <p>Un producto maestro no es una oferta: no tiene precio, unidad, stock ni productor. Las ofertas ({@code producto})
 * lo referencian por {@code producto_maestro_id} y guardan su propio nombre, precio y lineas; aqui nunca se
 * escriben, asi que editar o desactivar el maestro no cambia ninguna oferta ni pedido.
 */
@Service
public class ProductosMaestrosService {

    private static final String SELECT = """
            SELECT m.id, m.codigo, m.nombre, m.descripcion, m.activo, m.actualizado_en, m.desactivado_en,
                   l.codigo AS linea_codigo, l.nombre AS linea_nombre, l.activo AS linea_activa,
                   (SELECT count(*) FROM producto p WHERE p.producto_maestro_id = m.id) AS ofertas,
                   (SELECT count(*) FROM producto p WHERE p.producto_maestro_id = m.id AND p.estado = 'PUBLICADO') AS publicadas
            FROM producto_maestro m
            JOIN linea_producto l ON l.id = m.linea_id
            WHERE true""";

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogoComun comun;

    public ProductosMaestrosService(NamedParameterJdbcTemplate jdbc, CatalogoComun comun) {
        this.jdbc = jdbc;
        this.comun = comun;
    }

    /** CCPIP-351: productos por linea y nombre. {@code linea}, si llega, debe existir (activa o no). */
    @Transactional(readOnly = true)
    public ProductosMaestros listar(EstadoFiltro estado, String linea, String q) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder(SELECT).append(CatalogoComun.porEstado(estado, "m"))
                .append(CatalogoComun.porTexto(q, "m.nombre", p));
        String codigoLinea = CatalogoComun.limpio(linea);
        if (codigoLinea != null) {
            p.addValue("linea", lineaDe(codigoLinea).id());
            sql.append(" AND m.linea_id = :linea");
        }
        sql.append(" ORDER BY l.orden, l.nombre, m.nombre");
        return new ProductosMaestros(comun.contadores(Tabla.PRODUCTO), jdbc.query(sql.toString(), p, (rs, row) -> producto(rs)));
    }

    /** CCPIP-312: nace activo, en una linea activa. No se repiten el codigo ni el nombre dentro de la linea. */
    @Transactional
    public ProductoMaestro crear(long usuarioId, NuevoProductoMaestro nuevo) {
        String codigo = nuevo.codigo().strip().toUpperCase(Locale.ROOT);
        MapSqlParameterSource p = new MapSqlParameterSource("codigo", codigo);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM producto_maestro WHERE codigo = :codigo)", p, Boolean.class))) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CODIGO_DUPLICADO", "Ya existe un producto con el código " + codigo + ".");
        }
        DatosProductoMaestro d = nuevo.datos();
        LineaElegida linea = lineaActiva(d.linea(), null);
        validarNombre(d.nombre(), linea.id(), null);
        Long id = jdbc.queryForObject("""
                INSERT INTO producto_maestro (codigo, nombre, descripcion, linea_id)
                VALUES (:codigo, :nombre, :descripcion, :linea) RETURNING id""", parametros(d, linea, p), Long.class);
        ProductoMaestro creado = obtener(Objects.requireNonNull(id));
        comun.auditar(Entidad.PRODUCTO, creado.id(), usuarioId, Accion.CREAR, foto(creado));
        return creado;
    }

    /**
     * CCPIP-313: solo cambia la fila del catalogo maestro. Las ofertas vinculadas conservan su nombre,
     * precio y lineas, y los pedidos su copia de precio (RNF-13). Puede quedarse en su linea aunque esta
     * se haya desactivado, pero no pasar a otra linea inactiva.
     */
    @Transactional
    public ProductoMaestro modificar(long usuarioId, long id, DatosProductoMaestro d) {
        ProductoMaestro antes = obtener(id);
        LineaElegida linea = lineaActiva(d.linea(), antes.linea().codigo());
        validarNombre(d.nombre(), linea.id(), id);
        jdbc.update("""
                UPDATE producto_maestro SET nombre = :nombre, descripcion = :descripcion, linea_id = :linea,
                       actualizado_en = now()
                WHERE id = :id""", parametros(d, linea, new MapSqlParameterSource("id", id)));
        ProductoMaestro despues = obtener(id);
        Map<String, Object> cambios = CatalogoComun.diferencias(foto(antes), foto(despues));
        if (!cambios.isEmpty()) {
            comun.auditar(Entidad.PRODUCTO, id, usuarioId, Accion.EDITAR, cambios);
        }
        return despues;
    }

    /**
     * CCPIP-314: desactivar no borra ni desvincula. Deja de ofrecerse como referencia; las ofertas que lo
     * referencian, sus pedidos y su historial se conservan tal cual.
     */
    @Transactional
    public ProductoMaestro desactivar(long usuarioId, long id) {
        obtener(id);
        comun.cambiarActivo(Tabla.PRODUCTO, id, false, "PRODUCTO_YA_INACTIVO", "El producto ya está desactivado.");
        ProductoMaestro producto = obtener(id);
        Map<String, Object> conservado = new LinkedHashMap<>();
        conservado.put("ofertasConservadas", producto.ofertas());
        conservado.put("ofertasPublicadas", producto.ofertasPublicadas());
        comun.auditar(Entidad.PRODUCTO, id, usuarioId, Accion.DESACTIVAR, conservado);
        return producto;
    }

    /** Vuelve a ofrecerlo como referencia. Su linea debe estar activa. */
    @Transactional
    public ProductoMaestro reactivar(long usuarioId, long id) {
        ProductoMaestro actual = obtener(id);
        if (actual.activo()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PRODUCTO_YA_ACTIVO", "El producto ya está activo.");
        }
        if (!actual.linea().activa()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "LINEA_INACTIVA",
                    "La línea " + actual.linea().nombre() + " está desactivada. Reactívala primero.");
        }
        comun.cambiarActivo(Tabla.PRODUCTO, id, true, "PRODUCTO_YA_ACTIVO", "El producto ya está activo.");
        comun.auditar(Entidad.PRODUCTO, id, usuarioId, Accion.REACTIVAR, null);
        return obtener(id);
    }

    ProductoMaestro obtener(long id) {
        return jdbc.query(SELECT + " AND m.id = :id", new MapSqlParameterSource("id", id), (rs, row) -> producto(rs))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe en el catálogo maestro."));
    }

    private record LineaElegida(short id, String nombre, boolean activa) {
    }

    private LineaElegida lineaDe(String codigo) {
        return jdbc.query("SELECT id, nombre, activo FROM linea_producto WHERE codigo = :codigo",
                        new MapSqlParameterSource("codigo", codigo.strip()),
                        (rs, row) -> new LineaElegida(rs.getShort("id"), rs.getString("nombre"), rs.getBoolean("activo")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.invalido("LINEA_INVALIDA", "La línea " + codigo.strip() + " no existe."));
    }

    /** La linea elegida debe estar activa, salvo que sea la que el producto ya tiene. */
    private LineaElegida lineaActiva(String codigo, String actual) {
        LineaElegida linea = lineaDe(codigo);
        if (!linea.activa() && !codigo.strip().equals(actual)) {
            throw ErrorNegocio.invalido("LINEA_INACTIVA", "La línea " + linea.nombre() + " está desactivada. Elige una línea activa.");
        }
        return linea;
    }

    private void validarNombre(String nombre, short linea, Long excepto) {
        Boolean repetido = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM producto_maestro
                               WHERE linea_id = :linea AND lower(inmutable_unaccent(nombre)) = lower(inmutable_unaccent(:nombre))
                                 AND id IS DISTINCT FROM :excepto)""",
                new MapSqlParameterSource("nombre", nombre.strip()).addValue("linea", linea).addValue("excepto", excepto),
                Boolean.class);
        if (Boolean.TRUE.equals(repetido)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PRODUCTO_DUPLICADO", "Ya existe un producto con ese nombre en la línea.");
        }
    }

    private MapSqlParameterSource parametros(DatosProductoMaestro d, LineaElegida linea, MapSqlParameterSource p) {
        return p.addValue("nombre", d.nombre().strip()).addValue("descripcion", CatalogoComun.limpio(d.descripcion()))
                .addValue("linea", linea.id());
    }

    private static Map<String, Object> foto(ProductoMaestro m) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("codigo", m.codigo());
        f.put("nombre", m.nombre());
        f.put("descripcion", m.descripcion());
        f.put("linea", m.linea().codigo());
        return f;
    }

    private static ProductoMaestro producto(ResultSet rs) throws SQLException {
        return new ProductoMaestro(rs.getLong("id"), rs.getString("codigo"), rs.getString("nombre"), rs.getString("descripcion"),
                new LineaDelProducto(rs.getString("linea_codigo"), rs.getString("linea_nombre"), rs.getBoolean("linea_activa")),
                rs.getBoolean("activo"),
                rs.getLong("ofertas"), rs.getLong("publicadas"), CatalogoComun.instante(rs, "actualizado_en"),
                CatalogoComun.instante(rs, "desactivado_en"));
    }
}
