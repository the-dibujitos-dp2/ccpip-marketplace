package pe.ccpip.valoracion;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CCPIP-82 (RF-89): cuantas calificaciones necesita una oferta para publicar su promedio. Es un parametro
 * de SA-02 que fija el superadministrador en {@code parametro_plataforma}; mientras no lo defina rige el
 * provisional {@code ccpip.calificaciones.minimo-para-promedio}.
 */
@Service
public class MinimoCalificacionesService {

    static final String CLAVE = "CALIFICACIONES_MINIMO_PROMEDIO";

    /** El minimo que rige y de donde sale. {@code registradoPor} y {@code registradoEn} son nulos si es el provisional. */
    public record Minimo(int calificaciones, boolean provisional, String registradoPor, Instant registradoEn) {
    }

    private final JdbcTemplate jdbc;
    private final int provisional;

    public MinimoCalificacionesService(JdbcTemplate jdbc,
                                       @Value("${ccpip.calificaciones.minimo-para-promedio:3}") int provisional) {
        if (provisional < 1) {
            throw new IllegalStateException("ccpip.calificaciones.minimo-para-promedio debe ser al menos 1");
        }
        this.jdbc = jdbc;
        this.provisional = provisional;
    }

    /** El ultimo configurado por el superadministrador o, si nunca lo configuro, el provisional. */
    @Transactional(readOnly = true)
    public Minimo vigente() {
        return jdbc.query("""
                SELECT p.valor, u.nombre, p.registrado_en FROM parametro_plataforma p
                JOIN usuario u ON u.id = p.registrado_por
                WHERE p.clave = ? ORDER BY p.registrado_en DESC, p.id DESC LIMIT 1""",
                (rs, row) -> new Minimo(rs.getInt("valor"), false, rs.getString("nombre"),
                        rs.getTimestamp("registrado_en").toInstant()), CLAVE)
                .stream().findFirst()
                .orElseGet(() -> new Minimo(provisional, true, null, null));
    }

    /** SA-02: rige de inmediato para todas las fichas. Queda en el historial con quien lo cambio. */
    @Transactional
    public Minimo configurar(long usuarioId, int calificaciones) {
        jdbc.update("INSERT INTO parametro_plataforma (clave, valor, registrado_por) VALUES (?, ?, ?)",
                CLAVE, calificaciones, usuarioId);
        return vigente();
    }
}
