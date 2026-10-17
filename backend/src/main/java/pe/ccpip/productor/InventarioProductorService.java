package pe.ccpip.productor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.imagenes.ImagenService;
import pe.ccpip.productor.InventarioProductorModelos.EstadoInventario;
import pe.ccpip.productor.InventarioProductorModelos.Existencia;
import pe.ccpip.productor.InventarioProductorModelos.Inventario;
import pe.ccpip.productor.InventarioProductorModelos.LoteInventario;
import pe.ccpip.productor.InventarioProductorModelos.ProductoInventario;
import pe.ccpip.productor.InventarioProductorModelos.Resumen;
import pe.ccpip.productor.ProductoModelos.Ubicacion;

/**
 * CCPIP-136 (RF-110) y CCPIP-162 (RF-131), Figma PW-16: el inventario del almacen virtual unico del
 * productor, por producto, ubicacion, lote y estado.
 *
 * <ul>
 *   <li>Todo sale de los lotes con saldo: lote.cantidad_actual solo cambia junto con su movimiento de
 *       inventario (RNF-15, ADR-10), asi que lo que se muestra siempre queda explicado por movimientos.
 *       No se lee la tabla stock ni se guarda un total aparte.</li>
 *   <li>Se filtra por producto.productor_id del productor de la sesion, en el servidor (RNF-05).</li>
 *   <li>Es solo consulta; las transferencias (PW-17, PW-18) llegan con sus historias.</li>
 * </ul>
 */
@Service
public class InventarioProductorService {

    static final String ALMACEN = "ALMACEN_PRODUCTOR";

    /** Su almacen primero y luego los puntos de acopio por nombre. */
    private static final Comparator<Existencia> ALMACEN_PRIMERO = Comparator
            .comparing((Existencia e) -> !ALMACEN.equals(e.tipo())).thenComparing(Existencia::ubicacion);

    private record Fila(long productoId, String producto, UUID imagen, LoteInventario lote, String departamento) {
    }

    private final JdbcTemplate jdbc;

    public InventarioProductorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Inventario obtener(long productorId) {
        LocalDate hoy = LocalDate.now(LotesService.LIMA);
        List<Fila> filas = jdbc.query("""
                SELECT p.id AS producto_id, p.nombre AS producto,
                       (SELECT i.id FROM imagen i WHERE i.producto_id = p.id AND i.principal) AS imagen,
                       l.id, l.codigo, l.cantidad_actual, l.fecha_produccion, l.fecha_vencimiento,
                       u.id AS ubicacion_id, u.nombre AS ubicacion, u.tipo, u.departamento
                FROM lote l
                JOIN producto p ON p.id = l.producto_id
                JOIN ubicacion u ON u.id = l.ubicacion_id
                WHERE p.productor_id = ? AND l.cantidad_actual > 0
                ORDER BY p.nombre, p.id, u.tipo = 'ALMACEN_PRODUCTOR' DESC, u.nombre,
                         l.fecha_vencimiento NULLS LAST, l.registrado_en, l.id""",
                (rs, row) -> {
                    LocalDate vence = rs.getObject("fecha_vencimiento", LocalDate.class);
                    return new Fila(rs.getLong("producto_id"), rs.getString("producto"), rs.getObject("imagen", UUID.class),
                            new LoteInventario(rs.getLong("id"), rs.getString("codigo"), rs.getLong("ubicacion_id"),
                                    rs.getString("ubicacion"), rs.getString("tipo"), rs.getBigDecimal("cantidad_actual"),
                                    rs.getObject("fecha_produccion", LocalDate.class), vence, estado(vence, hoy)),
                            rs.getString("departamento"));
                },
                productorId);

        return new Inventario(resumen(filas), ProductosService.DIAS_ALERTA_VENCIMIENTO, ubicaciones(productorId, filas),
                productos(filas));
    }

    /** Vencido desde el dia siguiente a su fecha; por vencer dentro del aviso, como en el inicio (PW-01). */
    static EstadoInventario estado(LocalDate vence, LocalDate hoy) {
        if (vence == null) {
            return EstadoInventario.DISPONIBLE;
        }
        if (vence.isBefore(hoy)) {
            return EstadoInventario.VENCIDO;
        }
        return vence.isAfter(hoy.plusDays(ProductosService.DIAS_ALERTA_VENCIMIENTO))
                ? EstadoInventario.DISPONIBLE : EstadoInventario.POR_VENCER;
    }

    private static List<ProductoInventario> productos(List<Fila> filas) {
        Map<Long, List<Fila>> porProducto = new LinkedHashMap<>();
        for (Fila f : filas) {
            porProducto.computeIfAbsent(f.productoId(), k -> new ArrayList<>()).add(f);
        }
        return porProducto.values().stream().map(propias -> {
            Fila primera = propias.get(0);
            List<LoteInventario> lotes = propias.stream().map(Fila::lote).toList();
            List<Existencia> porUbicacion = existencias(lotes);
            BigDecimal total = porUbicacion.stream().map(Existencia::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ProductoInventario(primera.productoId(), primera.producto(),
                    primera.imagen() == null ? null : ImagenService.urlMiniatura(primera.imagen()), total, porUbicacion, lotes);
        }).toList();
    }

    private static Resumen resumen(List<Fila> filas) {
        List<LoteInventario> lotes = filas.stream().map(Fila::lote).toList();
        List<LoteInventario> almacen = lotes.stream().filter(l -> esAlmacen(l) && vigente(l)).toList();
        List<Existencia> porPunto = existencias(lotes.stream().filter(l -> !esAlmacen(l)).toList());
        int productosEnAlmacen = (int) filas.stream()
                .filter(f -> esAlmacen(f.lote()) && vigente(f.lote()))
                .map(Fila::productoId).distinct().count();
        return new Resumen(sumar(almacen), productosEnAlmacen,
                porPunto.stream().map(Existencia::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add), porPunto,
                (int) lotes.stream().filter(l -> l.estado() == EstadoInventario.POR_VENCER).count(),
                (int) lotes.stream().filter(l -> l.estado() == EstadoInventario.VENCIDO).count());
    }

    /** Lo disponible por ubicacion, en el orden de los lotes (almacen primero); sin vencidos. */
    private static List<Existencia> existencias(List<LoteInventario> lotes) {
        Map<Long, Existencia> porUbicacion = new LinkedHashMap<>();
        for (LoteInventario l : lotes) {
            if (vigente(l)) {
                porUbicacion.merge(l.ubicacionId(),
                        new Existencia(l.ubicacionId(), l.ubicacion(), l.tipoUbicacion(), l.cantidad()),
                        (a, b) -> new Existencia(a.ubicacionId(), a.ubicacion(), a.tipo(), a.cantidad().add(b.cantidad())));
            }
        }
        return porUbicacion.values().stream().sorted(ALMACEN_PRIMERO).toList();
    }

    /** Su almacen (aunque este vacio) y los puntos de acopio donde tiene algun lote con saldo. */
    private List<Ubicacion> ubicaciones(long productorId, List<Fila> filas) {
        List<Ubicacion> resultado = new ArrayList<>(jdbc.query("""
                SELECT id, nombre, tipo, departamento FROM ubicacion
                WHERE productor_id = ? AND tipo = 'ALMACEN_PRODUCTOR' AND activo""",
                (rs, row) -> new Ubicacion(rs.getLong("id"), rs.getString("nombre"), rs.getString("tipo"),
                        rs.getString("departamento")), productorId));
        // Los puntos salen de los mismos lotes: un punto sin saldo del productor no le concierne
        filas.stream().filter(f -> !esAlmacen(f.lote()))
                .map(f -> new Ubicacion(f.lote().ubicacionId(), f.lote().ubicacion(), f.lote().tipoUbicacion(), f.departamento()))
                .distinct().sorted(Comparator.comparing(Ubicacion::nombre)).forEach(resultado::add);
        return resultado;
    }

    private static boolean esAlmacen(LoteInventario l) {
        return ALMACEN.equals(l.tipoUbicacion());
    }

    private static boolean vigente(LoteInventario l) {
        return l.estado() != EstadoInventario.VENCIDO;
    }

    private static BigDecimal sumar(List<LoteInventario> lotes) {
        return lotes.stream().map(LoteInventario::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
