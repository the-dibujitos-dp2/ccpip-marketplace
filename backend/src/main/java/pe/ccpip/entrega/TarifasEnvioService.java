package pe.ccpip.entrega;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-482: la Camara fija la tarifa de envio a domicilio de cada distrito (decision del cliente del
 * 05/11/2026). Un distrito sin tarifa no se ofrece a domicilio aunque algun punto lo cubra; el recojo en el
 * punto cuesta S/ 0. Cada tarifa fijada o cambiada queda, con su autor, en un registro de solo insercion que
 * muestra la auditoria (CCPIP-97). Un pedido ya creado conserva la tarifa que copio (CCPIP-483).
 */
@Service
public class TarifasEnvioService {

    /**
     * Un distrito que algun punto cubre hoy, o que ya tiene tarifa. {@code monto} es nulo si aun no tiene
     * tarifa; {@code puntos}, cuantos puntos de acopio activos lo cubren con una zona activa.
     * {@code actualizadoPor} es el nombre de quien fijo la tarifa vigente.
     */
    public record TarifaDistrito(String ubigeo, String departamento, String provincia, String distrito,
                                 BigDecimal monto, int puntos, Instant actualizadoEn, String actualizadoPor) {
    }

    private static final String DISTRITOS = """
            SELECT g.codigo, g.departamento, g.provincia, g.distrito, t.monto, t.actualizado_en, u.nombre,
                   (SELECT count(DISTINCT c.ubicacion_id) FROM cobertura_entrega c
                    JOIN ubicacion p ON p.id = c.ubicacion_id
                    WHERE c.ubigeo = g.codigo AND c.activa AND p.activo) AS puntos
            FROM ubigeo g
            LEFT JOIN tarifa_envio t ON t.ubigeo = g.codigo
            LEFT JOIN usuario u ON u.id = t.actualizado_por
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TarifasEnvioService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Los distritos con cobertura activa o con tarifa, por departamento, provincia y distrito. */
    @Transactional(readOnly = true)
    public List<TarifaDistrito> listar() {
        return jdbc.query(DISTRITOS + """
                WHERE t.ubigeo IS NOT NULL
                   OR EXISTS (SELECT 1 FROM cobertura_entrega c WHERE c.ubigeo = g.codigo AND c.activa)
                ORDER BY g.departamento, g.provincia, g.distrito""", new MapSqlParameterSource(),
                (rs, row) -> tarifa(rs));
    }

    /**
     * Fija o cambia la tarifa del distrito. Si el monto no cambia no registra nada. Un ubigeo que no esta en
     * el catalogo no existe (404).
     */
    @Transactional
    public TarifaDistrito fijar(String ubigeo, BigDecimal monto, long usuarioId) {
        MapSqlParameterSource p = new MapSqlParameterSource("ubigeo", ubigeo).addValue("monto", monto)
                .addValue("usuario", usuarioId);
        if (ubigeo == null || !ubigeo.matches("\\d{6}") || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ubigeo WHERE codigo = :ubigeo)", p, Boolean.class))) {
            throw ErrorNegocio.noEncontrado("UBIGEO_NO_ENCONTRADO", "No encontramos ese distrito.");
        }
        BigDecimal anterior = jdbc.query("SELECT monto FROM tarifa_envio WHERE ubigeo = :ubigeo FOR UPDATE", p,
                (rs, row) -> rs.getBigDecimal("monto")).stream().findFirst().orElse(null);
        if (anterior == null || anterior.compareTo(monto) != 0) {
            jdbc.update("""
                    INSERT INTO tarifa_envio (ubigeo, monto, actualizado_en, actualizado_por)
                    VALUES (:ubigeo, :monto, now(), :usuario)
                    ON CONFLICT (ubigeo) DO UPDATE
                    SET monto = EXCLUDED.monto, actualizado_en = now(), actualizado_por = EXCLUDED.actualizado_por""", p);
            jdbc.update("""
                    INSERT INTO tarifa_envio_cambio (ubigeo, monto_anterior, monto_nuevo, realizado_por)
                    VALUES (:ubigeo, :anterior, :monto, :usuario)""", p.addValue("anterior", anterior));
        }
        return jdbc.query(DISTRITOS + "WHERE g.codigo = :ubigeo", p, (rs, row) -> tarifa(rs)).getFirst();
    }

    private static TarifaDistrito tarifa(ResultSet rs) throws SQLException {
        return new TarifaDistrito(rs.getString("codigo"), rs.getString("departamento"), rs.getString("provincia"),
                rs.getString("distrito"), rs.getBigDecimal("monto"), rs.getInt("puntos"),
                rs.getTimestamp("actualizado_en") == null ? null : rs.getTimestamp("actualizado_en").toInstant(),
                rs.getString("nombre"));
    }
}
