package pe.ccpip.valoracion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.valoracion.ValoracionModelos.Calificacion;
import pe.ccpip.valoracion.ValoracionModelos.CalificacionesPedido;
import pe.ccpip.valoracion.ValoracionModelos.Estrellas;
import pe.ccpip.valoracion.ValoracionModelos.LineaCalificable;
import pe.ccpip.valoracion.ValoracionModelos.NuevaCalificacion;
import pe.ccpip.valoracion.ValoracionModelos.Resena;
import pe.ccpip.valoracion.ValoracionModelos.ResumenCalificaciones;

/**
 * CCPIP-81 (RF-88): el comprador califica con 1 a 5 estrellas cada linea de un pedido que recibio
 * (ENTREGADO), una sola vez. CCPIP-82 (RF-89): el promedio publico de una oferta solo se muestra con al
 * menos {@code ccpip.calificaciones.minimo-para-promedio} calificaciones; sin ellas no hay promedio.
 */
@Service
public class CalificacionesService {

    static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final int RESENAS_PUBLICAS = 10;

    private final JdbcTemplate jdbc;
    private final int minimo;

    /**
     * {@code minimo}: valor provisional (3) mientras la Camara no fije el umbral como parametro de la
     * plataforma. Se cambia por propiedad, sin tocar codigo.
     */
    public CalificacionesService(JdbcTemplate jdbc,
                                 @Value("${ccpip.calificaciones.minimo-para-promedio:3}") int minimo) {
        this.jdbc = jdbc;
        this.minimo = Math.max(1, minimo);
    }

    /** Las lineas del pedido propio con su calificacion. Un pedido ajeno no existe (RNF-05). */
    @Transactional(readOnly = true)
    public CalificacionesPedido delPedido(long usuarioId, long pedidoId) {
        Cabecera c = cabecera(usuarioId, pedidoId, false);
        List<LineaCalificable> lineas = jdbc.query("""
                SELECT i.id, i.producto_id, p.nombre, coalesce(pr.nombre_tienda, pr.comunidad) AS tienda, i.cantidad,
                       c.estrellas, c.comentario, c.calificado_en
                FROM item_pedido i
                JOIN producto p ON p.id = i.producto_id
                JOIN productor pr ON pr.id = i.productor_id
                LEFT JOIN calificacion c ON c.item_pedido_id = i.id
                WHERE i.pedido_id = ? ORDER BY i.id""",
                (rs, row) -> new LineaCalificable(rs.getLong("id"), rs.getLong("producto_id"), rs.getString("nombre"),
                        rs.getString("tienda"), rs.getBigDecimal("cantidad"), calificacion(rs)), pedidoId);
        boolean calificable = "ENTREGADO".equals(c.estado());
        int pendientes = calificable ? (int) lineas.stream().filter(l -> l.calificacion() == null).count() : 0;
        return new CalificacionesPedido(pedidoId, c.numero(), c.estado(), calificable, pendientes, lineas);
    }

    /** CCPIP-81: compra verificada, una vez por linea y sin edicion posterior. */
    @Transactional
    public CalificacionesPedido calificar(long usuarioId, long pedidoId, NuevaCalificacion nueva) {
        Cabecera c = cabecera(usuarioId, pedidoId, true);
        if (!"ENTREGADO".equals(c.estado())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PEDIDO_NO_RECIBIDO",
                    "Podrás calificar los productos cuando recibas tu pedido.");
        }
        Long existe = jdbc.query("SELECT id FROM item_pedido WHERE id = ? AND pedido_id = ?",
                (rs, row) -> rs.getLong(1), nueva.itemId(), pedidoId).stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("LINEA_NO_ENCONTRADA", "Ese producto no está en tu pedido."));
        String comentario = nueva.comentario() == null || nueva.comentario().isBlank() ? null : nueva.comentario().strip();
        try {
            int filas = jdbc.update("""
                    INSERT INTO calificacion (item_pedido_id, pedido_id, producto_id, productor_id, usuario_id, estrellas, comentario)
                    SELECT i.id, i.pedido_id, i.producto_id, i.productor_id, ?, ?, ?
                    FROM item_pedido i
                    WHERE i.id = ? AND NOT EXISTS (SELECT 1 FROM calificacion c WHERE c.item_pedido_id = i.id)""",
                    usuarioId, nueva.estrellas(), comentario, existe);
            if (filas == 0) {
                throw yaCalificada();
            }
        } catch (DuplicateKeyException e) {
            throw yaCalificada();
        }
        return delPedido(usuarioId, pedidoId);
    }

    /** CCPIP-82: resumen publico de la oferta. Sin suficiente evidencia, solo el conteo y el umbral. */
    @Transactional(readOnly = true)
    public ResumenCalificaciones resumen(long productoId) {
        Long visible = jdbc.queryForObject("SELECT count(*) " + CatalogoRepository.VISIBLE + " AND p.id = ?",
                Long.class, productoId);
        if (visible == null || visible == 0) {
            throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o ya no está disponible");
        }
        return jdbc.queryForObject("""
                SELECT count(*) AS total, avg(estrellas) AS promedio,
                       count(*) FILTER (WHERE estrellas = 5) AS e5, count(*) FILTER (WHERE estrellas = 4) AS e4,
                       count(*) FILTER (WHERE estrellas = 3) AS e3, count(*) FILTER (WHERE estrellas = 2) AS e2,
                       count(*) FILTER (WHERE estrellas = 1) AS e1
                FROM calificacion WHERE producto_id = ?""", (rs, row) -> {
                    long total = rs.getLong("total");
                    if (total < minimo) {
                        return new ResumenCalificaciones(productoId, total, minimo, false, null, null, null);
                    }
                    BigDecimal promedio = rs.getBigDecimal("promedio").setScale(1, RoundingMode.HALF_UP);
                    Estrellas distribucion = new Estrellas(rs.getLong("e5"), rs.getLong("e4"), rs.getLong("e3"),
                            rs.getLong("e2"), rs.getLong("e1"));
                    return new ResumenCalificaciones(productoId, total, minimo, true, promedio, distribucion,
                            resenas(productoId));
                }, productoId);
    }

    private List<Resena> resenas(long productoId) {
        return jdbc.query("""
                SELECT estrellas, comentario, calificado_en FROM calificacion
                WHERE producto_id = ? AND comentario IS NOT NULL
                ORDER BY calificado_en DESC, id DESC LIMIT ?""",
                (rs, row) -> new Resena(rs.getInt("estrellas"), rs.getString("comentario"),
                        rs.getTimestamp("calificado_en").toInstant().atZone(LIMA).toLocalDate()),
                productoId, RESENAS_PUBLICAS);
    }

    private static Calificacion calificacion(ResultSet rs) throws SQLException {
        Timestamp en = rs.getTimestamp("calificado_en");
        return en == null ? null : new Calificacion(rs.getInt("estrellas"), rs.getString("comentario"), en.toInstant());
    }

    private static ErrorNegocio yaCalificada() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "LINEA_YA_CALIFICADA", "Ya calificaste este producto de tu pedido.");
    }

    private record Cabecera(String numero, String estado) {
    }

    /** Con {@code bloquear}, dos envios simultaneos de la misma calificacion se ordenan. */
    private Cabecera cabecera(long usuarioId, long pedidoId, boolean bloquear) {
        return jdbc.query("SELECT numero, estado FROM pedido WHERE id = ? AND usuario_id = ?" + (bloquear ? " FOR UPDATE" : ""),
                        (rs, row) -> new Cabecera(rs.getString(1), rs.getString(2)), pedidoId, usuarioId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PEDIDO_NO_ENCONTRADO", "No encontramos ese pedido."));
    }
}
