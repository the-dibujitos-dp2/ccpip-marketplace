package pe.ccpip.carrito;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CCPIP-142 (RF-109, RNF-36): cuanto tiempo se conserva el carrito del comprador desde su ultimo
 * cambio. Es un parametro de SA-02 que fija el superadministrador en {@code parametro_plataforma};
 * mientras la Camara no lo defina (Figma, "Pendiente de definicion del stakeholder") rige el
 * provisional de {@code ccpip.carrito.vigencia-provisional}. No depende de la sesion: el carrito vive
 * en la base y vence por {@code carrito.actualizado_en}, este o no el comprador conectado.
 *
 * <p>Vencer vacia el carrito y anota {@code vencido_en}. No hay movimientos de inventario porque el
 * carrito nunca reservo nada (RNF-35).
 */
@Service
public class VigenciaCarritoService {

    static final String CLAVE = "CARRITO_VIGENCIA_HORAS";
    private static final Logger log = LoggerFactory.getLogger(VigenciaCarritoService.class);

    /** La vigencia que rige y de donde sale. {@code registradoPor} y {@code registradoEn} son nulos si es la provisional. */
    public record Vigencia(long horas, boolean provisional, String registradoPor, Instant registradoEn) {
        Duration duracion() {
            return Duration.ofHours(horas);
        }
    }

    private final JdbcTemplate jdbc;
    private final long horasProvisionales;

    public VigenciaCarritoService(JdbcTemplate jdbc,
                                  @Value("${ccpip.carrito.vigencia-provisional:2h}") Duration provisional) {
        // SA-02 la configura en horas: el provisional tambien, para mostrarlo igual
        if (provisional.isNegative() || provisional.isZero() || provisional.toSecondsPart() != 0
                || provisional.toMinutesPart() != 0) {
            throw new IllegalStateException("ccpip.carrito.vigencia-provisional debe ser un numero entero de horas, p. ej. 2h");
        }
        this.jdbc = jdbc;
        this.horasProvisionales = provisional.toHours();
    }

    /** La ultima configurada por el superadministrador o, si nunca la configuro, la provisional. */
    @Transactional(readOnly = true)
    public Vigencia vigente() {
        return jdbc.query("""
                SELECT p.valor, u.nombre, p.registrado_en FROM parametro_plataforma p
                JOIN usuario u ON u.id = p.registrado_por
                WHERE p.clave = ? ORDER BY p.registrado_en DESC, p.id DESC LIMIT 1""",
                (rs, row) -> new Vigencia(rs.getInt("valor"), false, rs.getString("nombre"),
                        rs.getTimestamp("registrado_en").toInstant()), CLAVE)
                .stream().findFirst()
                .orElseGet(() -> new Vigencia(horasProvisionales, true, null, null));
    }

    /** SA-02: rige de inmediato, tambien para los carritos ya guardados (vencen segun la nueva vigencia). */
    @Transactional
    public Vigencia configurar(long usuarioId, int horas) {
        jdbc.update("INSERT INTO parametro_plataforma (clave, valor, registrado_por) VALUES (?, ?, ?)",
                CLAVE, horas, usuarioId);
        return vigente();
    }

    /**
     * Vacia el carrito del comprador si vencio. Se llama antes de leerlo o cambiarlo, asi un carrito
     * vencido nunca se retoma ni se compra, aunque la tarea programada aun no haya pasado.
     */
    void vencerSiCorresponde(long usuarioId, Vigencia vigencia) {
        int vaciados = vaciarVencidos("AND c.usuario_id = ?", vigencia.duracion().toSeconds(), usuarioId);
        if (vaciados > 0) {
            log.debug("Carrito del usuario {} vencido y vaciado (CCPIP-142)", usuarioId);
        }
    }

    /** Hasta cuando se conserva el carrito, contado desde su ultimo cambio. */
    static Instant vigenteHasta(Timestamp actualizadoEn, Vigencia vigencia) {
        return actualizadoEn.toInstant().plus(vigencia.duracion());
    }

    /** Barre los carritos abandonados para que no queden guardados indefinidamente. */
    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "${ccpip.carrito.barrido-vencidos:PT15M}")
    @Transactional
    public void vaciarVencidos() {
        int vaciados = vaciarVencidos("", vigente().duracion().toSeconds());
        if (vaciados > 0) {
            log.info("{} carritos vencidos vaciados (CCPIP-142)", vaciados);
        }
    }

    /** Devuelve cuantos carritos se vaciaron. Solo borra lineas del carrito: el inventario no se toca. */
    private int vaciarVencidos(String filtro, long segundos, Object... args) {
        Object[] parametros = new Object[args.length + 1];
        parametros[0] = segundos;
        System.arraycopy(args, 0, parametros, 1, args.length);
        Integer vaciados = jdbc.queryForObject("""
                WITH vencidos AS (
                    UPDATE carrito c SET vencido_en = now()
                    WHERE c.actualizado_en + ? * interval '1 second' <= now() %s
                      AND EXISTS (SELECT 1 FROM item_carrito i WHERE i.carrito_id = c.id)
                    RETURNING c.id),
                borrados AS (
                    DELETE FROM item_carrito i USING vencidos v WHERE i.carrito_id = v.id RETURNING i.carrito_id)
                SELECT count(*) FROM vencidos""".formatted(filtro), Integer.class, parametros);
        return vaciados == null ? 0 : vaciados;
    }
}
