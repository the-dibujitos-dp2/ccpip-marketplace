package pe.ccpip.autorizacion;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-365 (RF-255): lo que un visitante puede ver de la certificacion de un producto del catalogo.
 *
 * <ul>
 *   <li>Solo las evidencias que sustentaron la aprobacion de la Camara (las que cita su decision), sin
 *       retirar, y solo certificados y evidencias: un DOCUMENTO del productor puede ser privado (una
 *       constancia, un acta) y no se publica.</li>
 *   <li>Solo los datos: tipo, titulo, emisor y vigencia. Nunca el archivo, su nombre ni su clave, que
 *       siguen sirviendose solo con sesion (RNF-08).</li>
 *   <li>No afirma una aprobacion sin evidencia: VERIFICADA exige la autorizacion aprobada y vigente y al
 *       menos un certificado publico vigente. Lo aprobado sin evidencias publicas (por ejemplo, las
 *       semillas) se informa como SIN_EVIDENCIA.</li>
 * </ul>
 */
@Service
public class CertificacionPublicaService {

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";

    /**
     * VERIFICADA: aprobada, vigente y con un certificado publico vigente. VENCIDA: aprobada, pero sus
     * certificados publicos ya vencieron (la venta queda pausada hasta renovarlos, C5). SIN_EVIDENCIA:
     * cualquier otro caso; la pantalla no dice que el producto este aprobado.
     */
    public enum Estado { VERIFICADA, VENCIDA, SIN_EVIDENCIA }

    /** Una evidencia publica. {@code vigente}: hoy esta dentro de su vigencia (las que no tienen fin lo estan). */
    public record EvidenciaPublica(String tipo, String titulo, String emisor, LocalDate vigenteDesde,
                                   LocalDate vigenteHasta, boolean vigente) {
    }

    /** {@code verificadaEn}: cuando la Camara aprobo; nulo en SIN_EVIDENCIA. */
    public record Certificacion(long productoId, Estado estado, Instant verificadaEn, LocalDate vigenteHasta,
                                List<EvidenciaPublica> evidencias) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public CertificacionPublicaService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Certificacion consultar(long productoId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", productoId);
        Integer visible = jdbc.queryForObject("SELECT count(*) " + CatalogoRepository.VISIBLE + " AND p.id = :id",
                p, Integer.class);
        if (visible == null || visible == 0) {
            throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o ya no está disponible");
        }

        List<EvidenciaPublica> evidencias = jdbc.query("""
                SELECT e.tipo, e.titulo, e.emisor, e.vigente_desde, e.vigente_hasta,
                       (e.vigente_desde IS NULL OR e.vigente_desde <= %1$s)
                       AND (e.vigente_hasta IS NULL OR e.vigente_hasta >= %1$s) AS vigente
                FROM producto pr
                JOIN autorizacion_producto a ON a.producto_id = pr.id AND a.productor_id = pr.productor_id
                JOIN autorizacion_decision d ON d.id = a.decision_id
                JOIN evidencia_producto e ON e.autorizacion_id = a.id AND e.id = ANY (d.evidencias)
                WHERE pr.id = :id AND a.estado = 'APROBADA' AND e.retirada_en IS NULL
                  AND e.tipo IN ('CERTIFICADO', 'EVIDENCIA')
                ORDER BY e.tipo, e.vigente_hasta DESC NULLS LAST, e.titulo""".formatted(HOY), p,
                (rs, row) -> new EvidenciaPublica(rs.getString("tipo"), rs.getString("titulo"), rs.getString("emisor"),
                        rs.getObject("vigente_desde", LocalDate.class), rs.getObject("vigente_hasta", LocalDate.class),
                        rs.getBoolean("vigente")));

        boolean certificadoVigente = evidencias.stream().anyMatch(e -> "CERTIFICADO".equals(e.tipo()) && e.vigente());
        boolean certificado = evidencias.stream().anyMatch(e -> "CERTIFICADO".equals(e.tipo()));
        if (!certificado) {
            return new Certificacion(productoId, Estado.SIN_EVIDENCIA, null, null, List.of());
        }

        return jdbc.queryForObject("""
                SELECT d.decidido_en, a.vigente_hasta, (a.vigente_hasta IS NULL OR a.vigente_hasta >= %s) AS vigente
                FROM producto pr
                JOIN autorizacion_producto a ON a.producto_id = pr.id AND a.productor_id = pr.productor_id
                JOIN autorizacion_decision d ON d.id = a.decision_id
                WHERE pr.id = :id""".formatted(HOY), p, (rs, row) -> {
            Estado estado = certificadoVigente && rs.getBoolean("vigente") ? Estado.VERIFICADA : Estado.VENCIDA;
            Timestamp decidido = rs.getTimestamp("decidido_en");
            return new Certificacion(productoId, estado, decidido == null ? null : decidido.toInstant(),
                    rs.getObject("vigente_hasta", LocalDate.class), evidencias);
        });
    }
}
