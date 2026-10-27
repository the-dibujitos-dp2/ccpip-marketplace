package pe.ccpip.autorizacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-184 (RF-149): el subadministrador consulta la vigencia de los certificados de cada producto
 * autorizado, para revisar a tiempo los que vencen y pedir su renovacion (CCPIP-321).
 *
 * <p>Solo consulta y alerta: no cambia la autorizacion ni la venta. Lo que pasa con un certificado
 * vencido lo decide C5 (la venta queda pausada hasta renovarlo, sin bloqueo permanente).
 *
 * <p>Entran los certificados sin retirar de las autorizaciones APROBADAS. "Por vencer" es lo que vence
 * entre hoy y el horizonte que elige el subadministrador (30, 60 o 90 dias), sin un plazo escondido.
 */
@Service
public class VigenciaCertificadosService {

    public static final Set<Integer> HORIZONTES = Set.of(30, 60, 90);
    private static final String HOY = EvidenciasService.HOY;

    public enum Estado { VENCIDO, POR_VENCER, VIGENTE }

    /**
     * Un certificado y lo que necesita el subadministrador para actuar. {@code diasRestantes}: dias desde
     * hoy (Lima) hasta su vencimiento, negativo si ya vencio. {@code renovacionPlazo}: el plazo de la
     * solicitud de renovacion pendiente, si la hay.
     */
    public record Certificado(UUID evidenciaId, long autorizacionId, long productoId, String producto, String linea,
                              long productorId, String organizacion, String titulo, String emisor,
                              LocalDate vigenteHasta, long diasRestantes, Estado estado, LocalDate renovacionPlazo) {
    }

    public record Contadores(int vencidos, int porVencer, int vigentes) {
    }

    public record Vigencia(int dias, Estado estado, Contadores contadores, List<Certificado> certificados) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public VigenciaCertificadosService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Vigencia consultar(Estado estado, int dias) {
        if (!HORIZONTES.contains(dias)) {
            throw ErrorNegocio.invalido("HORIZONTE_INVALIDO", "Elige 30, 60 o 90 días.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("dias", dias);
        List<Certificado> todos = jdbc.query("""
                SELECT * FROM (
                    SELECT e.id, a.id AS autorizacion_id, p.id AS producto_id, p.nombre AS producto, l.nombre AS linea,
                           pr.id AS productor_id, coalesce(pr.nombre_tienda, pr.comunidad) AS organizacion,
                           e.titulo, e.emisor, e.vigente_hasta, e.vigente_hasta - %1$s AS dias_restantes,
                           CASE WHEN e.vigente_hasta < %1$s THEN 'VENCIDO'
                                WHEN e.vigente_hasta <= %1$s + :dias THEN 'POR_VENCER'
                                ELSE 'VIGENTE' END AS estado,
                           (SELECT r.plazo FROM renovacion_certificado r
                            WHERE r.evidencia_id = e.id AND r.estado = 'PENDIENTE') AS renovacion_plazo
                    FROM evidencia_producto e
                    JOIN autorizacion_producto a ON a.id = e.autorizacion_id
                    JOIN producto p ON p.id = e.producto_id
                    JOIN productor pr ON pr.id = e.productor_id
                    LEFT JOIN linea_producto l ON l.id = p.linea_id
                    WHERE a.estado = 'APROBADA' AND e.tipo = 'CERTIFICADO' AND e.retirada_en IS NULL
                ) c
                ORDER BY c.vigente_hasta, c.producto, c.id""".formatted(HOY), p,
                (rs, row) -> new Certificado(rs.getObject("id", UUID.class), rs.getLong("autorizacion_id"),
                        rs.getLong("producto_id"), rs.getString("producto"), rs.getString("linea"),
                        rs.getLong("productor_id"), rs.getString("organizacion"), rs.getString("titulo"),
                        rs.getString("emisor"), rs.getObject("vigente_hasta", LocalDate.class), rs.getLong("dias_restantes"),
                        Estado.valueOf(rs.getString("estado")), rs.getObject("renovacion_plazo", LocalDate.class)));
        Contadores contadores = new Contadores(contar(todos, Estado.VENCIDO), contar(todos, Estado.POR_VENCER),
                contar(todos, Estado.VIGENTE));
        return new Vigencia(dias, estado, contadores, todos.stream().filter(c -> c.estado() == estado).toList());
    }

    private static int contar(List<Certificado> certificados, Estado estado) {
        return (int) certificados.stream().filter(c -> c.estado() == estado).count();
    }
}
