package pe.ccpip.productor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.productor.Auditoria.Accion;
import pe.ccpip.productor.ProductoModelos.DatosLote;
import pe.ccpip.productor.ProductoModelos.EstadoLote;
import pe.ccpip.productor.ProductoModelos.Lote;
import pe.ccpip.productor.ProductoModelos.Lotes;
import pe.ccpip.productor.ProductoModelos.Ubicacion;

/**
 * CCPIP-41 y PW-06: registro de lotes. Lote, movimiento de entrada y stock se escriben en la
 * misma transaccion: el stock siempre queda explicado por un movimiento (RNF-15, ADR-10).
 */
@Service
public class LotesService {

    static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final JdbcTemplate jdbc;
    private final ProductosService productos;
    private final Auditoria auditoria;

    public LotesService(JdbcTemplate jdbc, ProductosService productos, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.productos = productos;
        this.auditoria = auditoria;
    }

    /** Su almacen y los puntos de acopio de la camara. */
    public List<Ubicacion> ubicaciones(long productorId) {
        return jdbc.query("""
                SELECT id, nombre, tipo, departamento FROM ubicacion
                WHERE activo AND (tipo = 'PUNTO_ACOPIO' OR productor_id = ?)
                ORDER BY tipo DESC, nombre""",
                (rs, row) -> new Ubicacion(rs.getLong("id"), rs.getString("nombre"), rs.getString("tipo"),
                        rs.getString("departamento")), productorId);
    }

    public Lotes listar(long productorId, long productoId) {
        var producto = productos.obtener(productorId, productoId);
        LocalDate hoy = LocalDate.now(LIMA);
        List<Lote> sinEstado = jdbc.query("""
                SELECT l.id, l.codigo, l.cantidad_inicial, l.cantidad_actual, l.fecha_produccion, l.fecha_vencimiento,
                       u.id AS ubicacion_id, u.nombre AS ubicacion, u.tipo
                FROM lote l JOIN ubicacion u ON u.id = l.ubicacion_id
                WHERE l.producto_id = ?
                ORDER BY l.fecha_vencimiento NULLS LAST, l.registrado_en""",
                (rs, row) -> new Lote(rs.getLong("id"), rs.getString("codigo"), rs.getBigDecimal("cantidad_inicial"),
                        rs.getBigDecimal("cantidad_actual"), rs.getObject("fecha_produccion", LocalDate.class),
                        rs.getObject("fecha_vencimiento", LocalDate.class), rs.getLong("ubicacion_id"),
                        rs.getString("ubicacion"), rs.getString("tipo"), null),
                productoId);
        // "Se vende primero el lote que vence antes": el primer lote vendible de la lista
        Long vendePrimero = sinEstado.stream()
                .filter(l -> vendible(l, hoy) && l.fechaVencimiento() != null)
                .map(Lote::id).findFirst().orElse(null);
        List<Lote> items = sinEstado.stream().map(l -> new Lote(l.id(), l.codigo(), l.cantidadInicial(),
                l.cantidadActual(), l.fechaProduccion(), l.fechaVencimiento(), l.ubicacionId(), l.ubicacion(),
                l.tipoUbicacion(), estado(l, hoy, vendePrimero))).toList();
        BigDecimal total = items.stream()
                .filter(l -> l.estado() != EstadoLote.VENCIDO)
                .map(Lote::cantidadActual).reduce(BigDecimal.ZERO, BigDecimal::add);
        String miniatura = producto.imagenes().stream().filter(i -> i.principal()).map(i -> i.urlMiniatura())
                .findFirst().orElse(null);
        return new Lotes(productoId, producto.nombre(), miniatura, requiereVencimiento(productoId), total,
                producto.enVenta(), items);
    }

    @Transactional
    public Lotes registrar(long productorId, long usuarioId, long productoId, DatosLote d) {
        productos.obtener(productorId, productoId);
        LocalDate hoy = LocalDate.now(LIMA);
        if (requiereVencimiento(productoId) && d.fechaVencimiento() == null) {
            throw ErrorNegocio.invalido("FALTA_VENCIMIENTO", "Este producto necesita fecha de vencimiento.");
        }
        if (d.fechaVencimiento() != null && d.fechaVencimiento().isBefore(hoy)) {
            throw ErrorNegocio.invalido("VENCIMIENTO_PASADO", "La fecha de vencimiento no puede ser anterior a hoy.");
        }
        if (d.fechaProduccion() != null && d.fechaProduccion().isAfter(hoy)) {
            throw ErrorNegocio.invalido("PRODUCCION_FUTURA", "La fecha de producción no puede ser posterior a hoy.");
        }
        if (d.fechaProduccion() != null && d.fechaVencimiento() != null && !d.fechaVencimiento().isAfter(d.fechaProduccion())) {
            throw ErrorNegocio.invalido("FECHAS_INVALIDAS", "El vencimiento debe ser posterior a la producción.");
        }
        boolean ubicacionValida = ubicaciones(productorId).stream().anyMatch(u -> u.id() == d.ubicacionId());
        if (!ubicacionValida) {
            throw ErrorNegocio.invalido("UBICACION_INVALIDA", "Elige tu almacén o un punto de acopio.");
        }
        String codigo = d.codigo().trim().toUpperCase(java.util.Locale.ROOT);
        Integer repetido = jdbc.queryForObject("SELECT count(*) FROM lote WHERE producto_id = ? AND codigo = ?",
                Integer.class, productoId, codigo);
        if (repetido != null && repetido > 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "LOTE_REPETIDO", "Ya registraste un lote con el código " + codigo + ".");
        }

        Long loteId = jdbc.queryForObject("""
                INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                  fecha_produccion, fecha_vencimiento, registrado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                productoId, d.ubicacionId(), codigo, d.cantidad(), d.cantidad(), d.fechaProduccion(), d.fechaVencimiento(),
                usuarioId);
        jdbc.update("""
                INSERT INTO movimiento_inventario (producto_id, ubicacion_id, tipo, cantidad, usuario_id, lote_id, observacion)
                VALUES (?, ?, 'ENTRADA', ?, ?, ?, ?)""",
                productoId, d.ubicacionId(), d.cantidad(), usuarioId, loteId, "Registro del lote " + codigo);
        jdbc.update("""
                INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, ?)
                ON CONFLICT (producto_id, ubicacion_id)
                DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad, actualizado_en = now()""",
                productoId, d.ubicacionId(), d.cantidad());
        auditoria.registrar(productoId, usuarioId, Accion.LOTE, Map.of("codigo", codigo, "cantidad", d.cantidad()));
        return listar(productorId, productoId);
    }

    private boolean requiereVencimiento(long productoId) {
        // Basta que una de sus lineas lo pida (CCPIP-45); sin linea aun, se pide por seguridad
        Boolean requiere = jdbc.queryForObject("""
                SELECT coalesce(bool_or(l.requiere_vencimiento), true)
                FROM producto_linea pl JOIN linea_producto l ON l.id = pl.linea_id WHERE pl.producto_id = ?""",
                Boolean.class, productoId);
        return !Boolean.FALSE.equals(requiere);
    }

    private static boolean vendible(Lote l, LocalDate hoy) {
        return "PUNTO_ACOPIO".equals(l.tipoUbicacion()) && l.cantidadActual().signum() > 0
                && (l.fechaVencimiento() == null || !l.fechaVencimiento().isBefore(hoy));
    }

    private static EstadoLote estado(Lote l, LocalDate hoy, Long vendePrimero) {
        if (l.fechaVencimiento() != null && l.fechaVencimiento().isBefore(hoy)) {
            return EstadoLote.VENCIDO;
        }
        if (l.cantidadActual().signum() == 0) {
            return EstadoLote.AGOTADO;
        }
        if (!"PUNTO_ACOPIO".equals(l.tipoUbicacion())) {
            return EstadoLote.EN_ALMACEN;
        }
        return Long.valueOf(l.id()).equals(vendePrimero) ? EstadoLote.VENDE_PRIMERO : EstadoLote.DISPONIBLE;
    }
}
