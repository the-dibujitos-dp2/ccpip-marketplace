package pe.ccpip.productor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * CCPIP-493: una oferta se rechaza a lo mas {@link #MAXIMO} veces. Se cuentan los rechazos de las solicitudes
 * posteriores a la del ultimo intento que habilito el subadministrador; al llegar al maximo el productor ya
 * no puede volver a enviarla hasta que se habilite uno nuevo.
 */
@Component
public class LimiteRechazos {

    public static final int MAXIMO = 3;

    private final JdbcTemplate jdbc;

    public LimiteRechazos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Los rechazos que cuentan hoy para el producto. */
    public int rechazos(long productoId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM moderacion_publicacion m
                WHERE m.producto_id = ? AND m.decision = 'RECHAZADA'
                  AND m.id > coalesce((SELECT max(r.solicitud_id) FROM reintento_publicacion r
                                       WHERE r.producto_id = m.producto_id), 0)""",
                Integer.class, productoId);
        return n == null ? 0 : n;
    }

    public boolean bloqueada(long productoId) {
        return rechazos(productoId) >= MAXIMO;
    }
}
