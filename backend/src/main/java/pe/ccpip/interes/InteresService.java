package pe.ccpip.interes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.catalogo.CatalogoRepository.Vendible;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.interes.InteresModelos.EstadoOferta;
import pe.ccpip.interes.InteresModelos.Interes;
import pe.ccpip.interes.InteresModelos.InteresOferta;

/**
 * CCPIP-105 (RF-38): el consumidor registra cuanto le interesaria comprar de una oferta agotada o en
 * "Venta pausada". Es por esa oferta y su productor; no reserva stock, no cobra y no mueve inventario.
 * CCPIP-106 (RF-24): el productor ve, por oferta suya, cuantos interesados hay y que cantidad piden.
 */
@Service
public class InteresService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogoRepository catalogo;

    public InteresService(NamedParameterJdbcTemplate jdbc, CatalogoRepository catalogo) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
    }

    /** El interes pendiente del comprador en la oferta, si tiene uno. */
    @Transactional(readOnly = true)
    public Optional<Interes> consultar(long usuarioId, long productoId) {
        return jdbc.query("""
                SELECT producto_id, cantidad, motivo, registrado_en, actualizado_en FROM interes_compra
                WHERE usuario_id = :usuario AND producto_id = :producto AND estado = 'PENDIENTE'""",
                new MapSqlParameterSource("usuario", usuarioId).addValue("producto", productoId),
                (rs, row) -> new Interes(rs.getLong("producto_id"), rs.getInt("cantidad"), rs.getString("motivo"),
                        instante(rs, "registrado_en"), instante(rs, "actualizado_en"))).stream().findFirst();
    }

    /**
     * Registra el interes o, si ya tenia uno pendiente en esa oferta, cambia la cantidad. Una oferta que
     * no esta publicada no admite interes; una que se puede comprar, tampoco: se compra.
     */
    @Transactional
    public Interes registrar(long usuarioId, long productoId, int cantidad) {
        Vendible oferta = catalogo.vendible(productoId)
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "No encontramos ese producto."));
        String motivo = motivo(oferta);
        if (motivo == null) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "OFERTA_DISPONIBLE",
                    "Este producto está disponible: puedes agregarlo a tu carrito.");
        }
        jdbc.update("""
                INSERT INTO interes_compra (usuario_id, producto_id, productor_id, cantidad, motivo)
                SELECT :usuario, p.id, p.productor_id, :cantidad, :motivo FROM producto p WHERE p.id = :producto
                ON CONFLICT (usuario_id, producto_id) WHERE estado = 'PENDIENTE'
                DO UPDATE SET cantidad = EXCLUDED.cantidad, motivo = EXCLUDED.motivo, actualizado_en = now()""",
                new MapSqlParameterSource("usuario", usuarioId).addValue("producto", productoId)
                        .addValue("cantidad", cantidad).addValue("motivo", motivo));
        return consultar(usuarioId, productoId).orElseThrow();
    }

    /** El comprador retira su interes: deja de contar para el productor y no recibe aviso. */
    @Transactional
    public void retirar(long usuarioId, long productoId) {
        int retirados = jdbc.update("""
                UPDATE interes_compra SET estado = 'RETIRADO', actualizado_en = now()
                WHERE usuario_id = :usuario AND producto_id = :producto AND estado = 'PENDIENTE'""",
                new MapSqlParameterSource("usuario", usuarioId).addValue("producto", productoId));
        if (retirados == 0) {
            throw ErrorNegocio.noEncontrado("SIN_INTERES", "No tienes un interés registrado en este producto.");
        }
    }

    /**
     * CCPIP-106: las ofertas del productor con interes pendiente, la de mas interesados primero. Solo las
     * suyas (RNF-05) y sin datos de quienes se interesaron.
     */
    @Transactional(readOnly = true)
    public List<InteresOferta> deProductor(long productorId) {
        record Fila(long productoId, String nombre, String unidad, int interesados, int cantidad, Instant ultimo) {
        }
        List<Fila> filas = jdbc.query("""
                SELECT p.id, p.nombre, um.codigo AS unidad, count(*) AS interesados, sum(i.cantidad) AS cantidad,
                       max(i.actualizado_en) AS ultimo
                FROM interes_compra i
                JOIN producto p ON p.id = i.producto_id
                LEFT JOIN unidad_medida um ON um.id = p.unidad_medida_id
                WHERE i.productor_id = :productor AND i.estado = 'PENDIENTE'
                GROUP BY p.id, p.nombre, um.codigo
                ORDER BY interesados DESC, cantidad DESC, p.nombre""", new MapSqlParameterSource("productor", productorId),
                (rs, row) -> new Fila(rs.getLong("id"), rs.getString("nombre"), rs.getString("unidad"),
                        rs.getInt("interesados"), rs.getInt("cantidad"), instante(rs, "ultimo")));
        Map<Long, Vendible> vendibles = catalogo.vendibles(filas.stream().map(Fila::productoId).toList());
        return filas.stream().map(f -> new InteresOferta(f.productoId(), f.nombre(), f.unidad(), f.interesados(),
                f.cantidad(), estado(vendibles.get(f.productoId())), f.ultimo())).toList();
    }

    /** Por que no se puede comprar la oferta, o nulo si se puede. */
    static String motivo(Vendible oferta) {
        if (!oferta.habilitada()) {
            return "VENTA_PAUSADA";
        }
        return oferta.enVenta().signum() > 0 ? null : "AGOTADO";
    }

    static boolean vendible(Vendible oferta) {
        return oferta != null && motivo(oferta) == null;
    }

    private static EstadoOferta estado(Vendible oferta) {
        if (oferta == null) {
            return EstadoOferta.NO_PUBLICADO;
        }
        String motivo = motivo(oferta);
        return motivo == null ? EstadoOferta.DISPONIBLE : EstadoOferta.valueOf(motivo);
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
