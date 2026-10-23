package pe.ccpip.gobernanza;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.RangoFechas;
import pe.ccpip.gobernanza.GobernanzaModelos.Accion;
import pe.ccpip.gobernanza.GobernanzaModelos.Historial;
import pe.ccpip.gobernanza.GobernanzaModelos.Objeto;
import pe.ccpip.gobernanza.GobernanzaModelos.Tipo;

/**
 * Historial de acciones de gobernanza (CCPIP-126, RF-85; Figma S-05). Junta, con UNION ALL, lo que cada
 * modulo ya registra con su autor: decisiones sobre autorizaciones (CCPIP-174, 284, 39), solicitudes de
 * renovacion (CCPIP-321), decisiones sobre el productor (CCPIP-291, 330, 85, 322), cambios de estado de
 * denuncias (CCPIP-84) y suspension o reactivacion de cuentas (CCPIP-124).
 *
 * <p>Solo lee: no hay tabla propia ni copia, asi que nada de esto se puede alterar desde aqui, y una
 * accion nueva de esos modulos aparece sola. {@code autor} nulo trae las de todos, para la auditoria
 * (CCPIP-97).
 */
@Service
public class HistorialGobernanzaService {

    private static final String FECHA_LIMA = "(%s AT TIME ZONE 'America/Lima')::date";

    /** Cada fila: clave, fecha, autor, tipo, accion, sobre, detalle, objeto, objeto_id y motivo. */
    static final String ACCIONES = """
            SELECT 'AD-' || d.id, d.decidido_en, d.decidido_por, 'AUTORIZACIONES', 'AUTORIZACION_' || d.decision,
                   coalesce(pr.nombre_tienda, pr.comunidad), p.nombre, 'AUTORIZACION', a.id, d.motivo
            FROM autorizacion_decision d
            JOIN autorizacion_producto a ON a.id = d.autorizacion_id
            JOIN producto p ON p.id = a.producto_id
            JOIN productor pr ON pr.id = a.productor_id
            UNION ALL
            SELECT 'RC-' || r.id, r.solicitada_en, r.solicitada_por, 'AUTORIZACIONES', 'RENOVACION_SOLICITADA',
                   coalesce(pr.nombre_tienda, pr.comunidad), p.nombre, 'AUTORIZACION', r.autorizacion_id, r.motivo
            FROM renovacion_certificado r
            JOIN producto p ON p.id = r.producto_id
            JOIN productor pr ON pr.id = r.productor_id
            UNION ALL
            SELECT 'PD-' || d.id, d.decidido_en, d.decidido_por, 'PRODUCTORES', d.decision,
                   coalesce(pr.nombre_tienda, pr.comunidad), NULL, 'PRODUCTOR', pr.id, d.motivo
            FROM productor_decision d
            JOIN productor pr ON pr.id = d.productor_id
            UNION ALL
            SELECT 'DE-' || e.id, e.registrado_en, e.usuario_id, 'DENUNCIAS', 'DENUNCIA_' || e.estado,
                   coalesce(pr.nombre_tienda, pr.comunidad), CAST(de.id AS text), 'DENUNCIAS', pr.id, e.nota
            FROM denuncia_evento e
            JOIN denuncia de ON de.id = e.denuncia_id
            JOIN productor pr ON pr.id = de.productor_id
            WHERE e.tipo = 'CAMBIO_ESTADO' AND e.rol = 'SUB_ADMIN'
            UNION ALL
            SELECT 'CH-' || h.id, h.realizado_en, h.realizado_por, 'CUENTAS', 'CUENTA_' || h.accion,
                   u.nombre, NULL, 'CUENTA', u.id, h.motivo
            FROM cuenta_historial h
            JOIN usuario u ON u.id = h.usuario_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public HistorialGobernanzaService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Las acciones de {@code autor} (o de todos, si es nulo) en el periodo, de la mas reciente a la mas
     * antigua. Sin fechas, los ultimos 30 dias de Lima; como maximo un año. {@code tipo} nulo: todas.
     */
    @Transactional(readOnly = true)
    public Historial acciones(Long autor, LocalDate desde, LocalDate hasta, Tipo tipo) {
        LocalDate hoy = jdbc.getJdbcTemplate()
                .queryForObject("SELECT " + FECHA_LIMA.formatted("now()"), LocalDate.class);
        RangoFechas rango = RangoFechas.resolver(desde, hasta, hoy, fin -> fin.minusDays(29));
        MapSqlParameterSource p = new MapSqlParameterSource("desde", rango.desde()).addValue("hasta", rango.hasta())
                .addValue("autor", autor).addValue("tipo", tipo == null ? null : tipo.name());
        List<Accion> acciones = jdbc.query("""
                SELECT * FROM (%s) AS x (clave, fecha, autor, tipo, accion, sobre, detalle, objeto, objeto_id, motivo)
                WHERE %s BETWEEN :desde AND :hasta
                  AND (CAST(:autor AS bigint) IS NULL OR x.autor = :autor)
                  AND (CAST(:tipo AS text) IS NULL OR x.tipo = :tipo)
                ORDER BY x.fecha DESC, x.clave DESC""".formatted(ACCIONES, FECHA_LIMA.formatted("x.fecha")), p,
                (rs, row) -> new Accion(rs.getString("clave"), rs.getTimestamp("fecha").toInstant(),
                        Tipo.valueOf(rs.getString("tipo")), rs.getString("accion"), rs.getString("sobre"),
                        rs.getString("detalle"), Objeto.valueOf(rs.getString("objeto")), rs.getLong("objeto_id"),
                        rs.getString("motivo")));
        return new Historial(rango.desde(), rango.hasta(), tipo, acciones);
    }
}
