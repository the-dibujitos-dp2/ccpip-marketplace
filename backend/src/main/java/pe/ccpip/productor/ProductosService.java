package pe.ccpip.productor;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.imagenes.ImagenService;
import pe.ccpip.productor.Auditoria.Accion;
import pe.ccpip.productor.ProductoModelos.DatosProducto;
import pe.ccpip.productor.ProductoModelos.Estado;
import pe.ccpip.productor.ProductoModelos.Historia;
import pe.ccpip.productor.ProductoModelos.ListaProductos;
import pe.ccpip.productor.ProductoModelos.Modalidad;
import pe.ccpip.productor.ProductoModelos.ProductoEdicion;
import pe.ccpip.productor.ProductoModelos.ProductoFila;
import pe.ccpip.productor.ProductoModelos.Publicable;
import pe.ccpip.productor.ProductoModelos.Ubicado;

/**
 * Alta, edicion y estado de los productos del productor (CCPIP-40, 47, 160, 45, 132).
 * Toda consulta filtra por el productor de la sesion: un producto ajeno simplemente no
 * existe para el (404, RNF-05). No hay operacion de borrado en ninguna capa (RNF-17).
 */
@Service
public class ProductosService {

    /** Stock en venta por debajo del cual PW-03 marca "Stock bajo". */
    static final BigDecimal STOCK_BAJO = BigDecimal.TEN;
    /** Dias de anticipacion para avisar que un lote vence (PW-01: "segun tu alerta de 15 dias"). */
    public static final int DIAS_ALERTA_VENCIMIENTO = 15;

    /** Stock vendible: lotes vigentes con saldo en puntos de acopio (lo que esta en el almacen aun no se vende). */
    static final String EN_VENTA = """
            (SELECT coalesce(sum(l.cantidad_actual), 0) FROM lote l JOIN ubicacion u ON u.id = l.ubicacion_id
             WHERE l.producto_id = p.id AND u.tipo = 'PUNTO_ACOPIO'
               AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= (now() AT TIME ZONE 'America/Lima')::date))""";

    private final JdbcTemplate jdbc;
    private final ImagenService imagenes;
    private final Auditoria auditoria;

    public ProductosService(JdbcTemplate jdbc, ImagenService imagenes, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.imagenes = imagenes;
        this.auditoria = auditoria;
    }

    // ---------- Consulta ----------

    public ListaProductos listar(long productorId, Estado estado, String q) {
        String termino = q == null || q.isBlank() ? null : "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%";
        List<ProductoFila> filas = jdbc.query("""
                SELECT p.id, p.nombre, p.modalidad, p.precio, p.precio_mayorista, p.estado, l.nombre AS linea, l.codigo AS linea_codigo,
                       (SELECT i.id FROM imagen i WHERE i.producto_id = p.id AND i.principal) AS imagen,
                       %s AS en_venta,
                       EXISTS (SELECT 1 FROM lote x JOIN ubicacion u ON u.id = x.ubicacion_id
                               WHERE x.producto_id = p.id AND x.cantidad_actual > 0 AND u.tipo = 'PUNTO_ACOPIO'
                                 AND x.fecha_vencimiento BETWEEN (now() AT TIME ZONE 'America/Lima')::date
                                     AND (now() AT TIME ZONE 'America/Lima')::date + %d) AS por_vencer
                FROM producto p
                LEFT JOIN linea_producto l ON l.id = p.linea_id
                WHERE p.productor_id = ? AND p.estado = ?
                  AND (?::text IS NULL OR lower(inmutable_unaccent(p.nombre)) LIKE lower(inmutable_unaccent(?)) ESCAPE '\\')
                ORDER BY p.actualizado_en DESC, p.id DESC""".formatted(EN_VENTA, DIAS_ALERTA_VENCIMIENTO),
                (rs, row) -> fila(rs, stockPorUbicacion(rs.getLong("id"))),
                productorId, estado.name(), termino, termino);

        Map<Estado, Integer> conteo = new EnumMap<>(Estado.class);
        for (Estado e : Estado.values()) {
            conteo.put(e, 0);
        }
        jdbc.query("SELECT estado, count(*) AS n FROM producto WHERE productor_id = ? GROUP BY estado",
                rs -> {
                    conteo.put(Estado.valueOf(rs.getString("estado")), rs.getInt("n"));
                }, productorId);
        return new ListaProductos(filas, conteo);
    }

    public ProductoEdicion obtener(long productorId, long id) {
        return jdbc.query("""
                SELECT p.*, um.codigo AS unidad_codigo, %s AS en_venta FROM producto p
                LEFT JOIN unidad_medida um ON um.id = p.unidad_medida_id
                WHERE p.id = ? AND p.productor_id = ?""".formatted(EN_VENTA),
                (rs, row) -> edicion(rs, productorId), id, productorId)
                .stream().findFirst()
                .orElseThrow(ProductosService::noEncontrado);
    }

    // ---------- Alta y edicion ----------

    /** CCPIP-40: el producto nace en borrador y no aparece en el catalogo hasta publicarlo. */
    @Transactional
    public ProductoEdicion crear(long productorId, long usuarioId, DatosProducto datos) {
        validarCoherencia(datos);
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, estado, creado_por, actualizado_por)
                VALUES (?, ?, 'BORRADOR', ?, ?) RETURNING id""", Long.class, productorId, datos.nombre().trim(), usuarioId, usuarioId);
        escribir(id, productorId, usuarioId, datos);
        auditoria.registrar(id, usuarioId, Accion.CREAR, Map.of("nombre", datos.nombre().trim()));
        return obtener(productorId, id);
    }

    /** CCPIP-47: se edita todo menos el estado; queda registro de que cambio, quien y cuando. */
    @Transactional
    public ProductoEdicion editar(long productorId, long usuarioId, long id, DatosProducto datos) {
        ProductoEdicion antes = obtener(productorId, id);
        validarCoherencia(datos);
        escribir(id, productorId, usuarioId, datos);
        ProductoEdicion despues = obtener(productorId, id);
        if (despues.estado() == Estado.PUBLICADO) {
            // Un producto publicado no puede quedar en un estado que no se podria publicar
            List<String> faltan = faltantesDeDatos(despues);
            if (!faltan.isEmpty()) {
                throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCTO_INCOMPLETO",
                        "Un producto publicado debe conservar sus datos completos.", faltan);
            }
        }
        Map<String, Object> cambios = diferencias(antes, despues);
        if (!cambios.isEmpty()) {
            auditoria.registrar(id, usuarioId, Accion.EDITAR, cambios);
        }
        return despues;
    }

    // ---------- Estado (CCPIP-160) ----------

    @Transactional
    public ProductoEdicion publicar(long productorId, long usuarioId, long id) {
        ProductoEdicion p = obtener(productorId, id);
        if (p.estado() == Estado.PUBLICADO) {
            return p;
        }
        if (!p.publicable().listo()) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCTO_NO_PUBLICABLE",
                    "Aún falta completar algunos datos para publicar.", p.publicable().faltan());
        }
        jdbc.update("""
                UPDATE producto SET estado = 'PUBLICADO', publicado_en = coalesce(publicado_en, now()),
                       actualizado_por = ?, actualizado_en = now()
                WHERE id = ?""", usuarioId, id);
        auditoria.registrar(id, usuarioId, p.estado() == Estado.RETIRADO ? Accion.REPUBLICAR : Accion.PUBLICAR, null);
        return obtener(productorId, id);
    }

    /** Retirar no borra: el producto deja el catalogo pero sigue en pedidos e historico. Es reversible. */
    @Transactional
    public ProductoEdicion retirar(long productorId, long usuarioId, long id) {
        ProductoEdicion p = obtener(productorId, id);
        if (p.estado() != Estado.PUBLICADO) {
            throw ErrorNegocio.invalido("SOLO_PUBLICADOS", "Solo se puede despublicar un producto publicado.");
        }
        jdbc.update("UPDATE producto SET estado = 'RETIRADO', actualizado_por = ?, actualizado_en = now() WHERE id = ?",
                usuarioId, id);
        auditoria.registrar(id, usuarioId, Accion.RETIRAR, null);
        return obtener(productorId, id);
    }

    // ---------- Internos ----------

    private void escribir(long id, long productorId, long usuarioId, DatosProducto d) {
        List<Short> lineas = idsLineas(d.lineas());
        Short unidad = d.unidad() == null || d.unidad().isBlank() ? null : idUnidad(d.unidad());
        boolean mayorista = d.modalidad() != Modalidad.MINORISTA;
        boolean minorista = d.modalidad() != Modalidad.MAYORISTA;
        // RNF-29: solo cuenta como asistida si el servidor propuso una descripcion
        Boolean hayPropuesta = jdbc.queryForObject(
                "SELECT descripcion_propuesta IS NOT NULL FROM producto WHERE id = ?", Boolean.class, id);
        jdbc.update("""
                UPDATE producto SET nombre = ?, descripcion = ?, descripcion_asistida = ?, linea_id = ?,
                       unidad_medida_id = ?, contenido = ?, modalidad = ?, precio = ?, precio_mayorista = ?,
                       cantidad_minima_mayorista = ?, es_materia_prima = ?, actualizado_por = ?, actualizado_en = now()
                WHERE id = ? AND productor_id = ?""",
                d.nombre().trim(), TiendaService.limpio(d.descripcion()),
                d.descripcionAsistida() && Boolean.TRUE.equals(hayPropuesta),
                lineas.isEmpty() ? null : lineas.get(0), unidad, TiendaService.limpio(d.contenido()),
                d.modalidad().name(), minorista ? d.precio() : null, mayorista ? d.precioMayorista() : null,
                mayorista ? d.cantidadMinimaMayorista() : null, d.esMateriaPrima(), usuarioId, id, productorId);
        jdbc.update("DELETE FROM producto_linea WHERE producto_id = ?", id);
        for (Short linea : lineas) {
            jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) VALUES (?, ?)", id, linea);
        }
    }

    /** Reglas que valen incluso para un borrador: nunca guardar datos contradictorios. */
    private static void validarCoherencia(DatosProducto d) {
        boolean mayorista = d.modalidad() != Modalidad.MINORISTA;
        boolean minorista = d.modalidad() != Modalidad.MAYORISTA;
        if (mayorista && minorista && d.precio() != null && d.precioMayorista() != null
                && d.precioMayorista().compareTo(d.precio()) >= 0) {
            throw ErrorNegocio.invalido("PRECIO_MAYORISTA_INVALIDO", "El precio mayorista debe ser menor que el minorista.");
        }
        if (mayorista && d.precioMayorista() != null && d.cantidadMinimaMayorista() == null) {
            throw ErrorNegocio.invalido("FALTA_MINIMO_MAYORISTA", "Indica desde qué cantidad se aplica el precio mayorista.");
        }
    }

    private List<String> faltantesDeDatos(ProductoEdicion p) {
        List<String> faltan = new ArrayList<>();
        if (p.lineas().isEmpty()) {
            faltan.add("Al menos una línea de producto");
        }
        if (p.unidad() == null) {
            faltan.add("La unidad de medida");
        }
        if (p.modalidad() != Modalidad.MAYORISTA && p.precio() == null) {
            faltan.add("El precio minorista");
        }
        if (p.modalidad() != Modalidad.MINORISTA && (p.precioMayorista() == null || p.cantidadMinimaMayorista() == null)) {
            faltan.add("El precio mayorista y su cantidad mínima");
        }
        return faltan;
    }

    private Publicable publicable(long productorId, ProductoEdicion p) {
        List<String> faltan = faltantesDeDatos(p);
        if (p.enVenta().signum() <= 0) {
            faltan.add("Un lote vigente con stock en un punto de acopio");
        }
        Boolean tiendaPublicada = jdbc.queryForObject("SELECT tienda_publicada FROM productor WHERE id = ?",
                Boolean.class, productorId);
        if (!Boolean.TRUE.equals(tiendaPublicada)) {
            faltan.add("Publicar tu tienda (Mi tienda)");
        }
        return new Publicable(faltan.isEmpty(), faltan);
    }

    private ProductoEdicion edicion(ResultSet rs, long productorId) throws SQLException {
        long id = rs.getLong("id");
        List<String> lineas = jdbc.queryForList("""
                SELECT l.codigo FROM producto_linea pl JOIN linea_producto l ON l.id = pl.linea_id
                WHERE pl.producto_id = ? ORDER BY (l.id = ?) DESC, l.orden""", String.class, id, rs.getObject("linea_id"));
        ProductoEdicion sinPublicable = new ProductoEdicion(id, Estado.valueOf(rs.getString("estado")),
                rs.getString("nombre"), rs.getString("descripcion"), rs.getBoolean("descripcion_asistida"),
                rs.getString("descripcion_propuesta"), lineas, rs.getString("unidad_codigo"), rs.getString("contenido"),
                Modalidad.valueOf(rs.getString("modalidad")), rs.getBigDecimal("precio"), rs.getBigDecimal("precio_mayorista"),
                (Integer) rs.getObject("cantidad_minima_mayorista"), rs.getBoolean("es_materia_prima"),
                imagenes.deProducto(id), historia(id), rs.getBigDecimal("en_venta"), null);
        return new ProductoEdicion(sinPublicable.id(), sinPublicable.estado(), sinPublicable.nombre(),
                sinPublicable.descripcion(), sinPublicable.descripcionAsistida(), sinPublicable.descripcionPropuesta(),
                sinPublicable.lineas(), sinPublicable.unidad(), sinPublicable.contenido(), sinPublicable.modalidad(),
                sinPublicable.precio(), sinPublicable.precioMayorista(), sinPublicable.cantidadMinimaMayorista(),
                sinPublicable.esMateriaPrima(), sinPublicable.imagenes(), sinPublicable.historia(),
                sinPublicable.enVenta(), publicable(productorId, sinPublicable));
    }

    Historia historia(long productoId) {
        return jdbc.query("""
                SELECT quienes, como, significado, cuidado, titulo, texto_propuesto, texto_final, asistida, aprobada_en
                FROM producto_historia WHERE producto_id = ?""",
                (rs, row) -> new Historia(rs.getString("quienes"), rs.getString("como"), rs.getString("significado"),
                        rs.getString("cuidado"), rs.getString("titulo"), rs.getString("texto_propuesto"),
                        rs.getString("texto_final"), rs.getBoolean("asistida"), rs.getObject("aprobada_en") != null),
                productoId).stream().findFirst()
                .orElse(new Historia(null, null, null, null, null, null, null, false, false));
    }

    private ProductoFila fila(ResultSet rs, List<Ubicado> stock) throws SQLException {
        BigDecimal enVenta = rs.getBigDecimal("en_venta");
        Estado estado = Estado.valueOf(rs.getString("estado"));
        String alerta = null;
        if (rs.getBoolean("por_vencer")) {
            alerta = "LOTE_POR_VENCER";
        } else if (enVenta.signum() == 0) {
            alerta = "SIN_STOCK";
        } else if (enVenta.compareTo(STOCK_BAJO) < 0) {
            alerta = "STOCK_BAJO";
        }
        Object imagen = rs.getObject("imagen");
        return new ProductoFila(rs.getLong("id"), rs.getString("nombre"),
                imagen == null ? null : ImagenService.urlMiniatura((java.util.UUID) imagen), rs.getString("linea"),
                rs.getString("linea_codigo"),
                Modalidad.valueOf(rs.getString("modalidad")), rs.getBigDecimal("precio"),
                rs.getBigDecimal("precio_mayorista"), enVenta, stock, estado, alerta);
    }

    private List<Ubicado> stockPorUbicacion(long productoId) {
        return jdbc.query("""
                SELECT u.nombre, u.tipo, sum(l.cantidad_actual) AS cantidad
                FROM lote l JOIN ubicacion u ON u.id = l.ubicacion_id
                WHERE l.producto_id = ?
                  AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= (now() AT TIME ZONE 'America/Lima')::date)
                GROUP BY u.id, u.nombre, u.tipo
                ORDER BY u.tipo DESC, u.nombre""",
                (rs, row) -> new Ubicado(rs.getString("nombre"), rs.getString("tipo"), rs.getBigDecimal("cantidad")),
                productoId);
    }

    private List<Short> idsLineas(List<String> codigos) {
        if (codigos == null) {
            return List.of();
        }
        List<Short> ids = new ArrayList<>();
        for (String codigo : codigos.stream().distinct().toList()) {
            ids.add(jdbc.query("SELECT id FROM linea_producto WHERE codigo = ? AND activo", (rs, row) -> rs.getShort(1), codigo)
                    .stream().findFirst()
                    .orElseThrow(() -> ErrorNegocio.invalido("LINEA_INVALIDA", "La línea " + codigo + " no existe.")));
        }
        return ids;
    }

    private Short idUnidad(String codigo) {
        // RNF-32: solo unidades del catalogo cerrado
        return jdbc.query("SELECT id FROM unidad_medida WHERE codigo = ?", (rs, row) -> rs.getShort(1), codigo)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.invalido("UNIDAD_INVALIDA", "Elige una unidad del catálogo."));
    }

    private static Map<String, Object> diferencias(ProductoEdicion a, ProductoEdicion b) {
        Map<String, Object> cambios = new LinkedHashMap<>();
        comparar(cambios, "nombre", a.nombre(), b.nombre());
        comparar(cambios, "descripcion", a.descripcion(), b.descripcion());
        comparar(cambios, "lineas", a.lineas(), b.lineas());
        comparar(cambios, "unidad", a.unidad(), b.unidad());
        comparar(cambios, "contenido", a.contenido(), b.contenido());
        comparar(cambios, "modalidad", a.modalidad(), b.modalidad());
        comparar(cambios, "precio", a.precio(), b.precio());
        comparar(cambios, "precioMayorista", a.precioMayorista(), b.precioMayorista());
        comparar(cambios, "cantidadMinimaMayorista", a.cantidadMinimaMayorista(), b.cantidadMinimaMayorista());
        comparar(cambios, "esMateriaPrima", a.esMateriaPrima(), b.esMateriaPrima());
        return cambios;
    }

    private static void comparar(Map<String, Object> cambios, String campo, Object antes, Object despues) {
        boolean iguales = antes instanceof BigDecimal x && despues instanceof BigDecimal y
                ? x.compareTo(y) == 0 : Objects.equals(antes, despues);
        if (!iguales) {
            Map<String, Object> cambio = new LinkedHashMap<>();
            cambio.put("antes", antes);
            cambio.put("despues", despues);
            cambios.put(campo, cambio);
        }
    }

    static ErrorNegocio noEncontrado() {
        return ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe.");
    }
}
