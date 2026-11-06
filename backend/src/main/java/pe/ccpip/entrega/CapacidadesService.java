package pe.ccpip.entrega;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-484: la Camara fija cuantos pedidos a domicilio puede despachar por dia cada punto de acopio
 * (decision del cliente del 05/11/2026). Sin capacidad registrada el punto no tiene limite. Cada cambio
 * queda, con su autor, en un registro de solo insercion que muestra la auditoria (CCPIP-97). Cambiar la
 * capacidad no toca los pedidos ya creados: solo decide si se acepta el siguiente.
 */
@Service
public class CapacidadesService {

    /**
     * Un punto de acopio activo con su capacidad. {@code maximoDiario} es nulo si no tiene limite;
     * {@code usadosHoy}, sus pedidos a domicilio de hoy (Lima) que no se cancelaron.
     */
    public record CapacidadPunto(long puntoId, String punto, String departamento, Integer maximoDiario, int usadosHoy,
                                 Instant actualizadoEn, String actualizadoPor) {
    }

    private static final String PUNTOS = """
            SELECT u.id, u.nombre, u.departamento, k.maximo_diario, k.actualizado_en, a.nombre AS autor,
                   %s AS usados
            FROM ubicacion u
            LEFT JOIN capacidad_entrega k ON k.ubicacion_id = u.id
            LEFT JOIN usuario a ON a.id = k.actualizado_por
            WHERE u.tipo = 'PUNTO_ACOPIO' AND u.activo
            """.formatted(EntregaService.USADOS_HOY.formatted("u.id"));

    private final NamedParameterJdbcTemplate jdbc;

    public CapacidadesService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Los puntos de acopio activos, por nombre, con su capacidad y lo que usaron hoy. */
    @Transactional(readOnly = true)
    public List<CapacidadPunto> listar() {
        return jdbc.query(PUNTOS + "ORDER BY u.nombre, u.id", new MapSqlParameterSource(), (rs, row) -> capacidad(rs));
    }

    /**
     * Fija la capacidad del punto o, con {@code maximo} nulo, la quita (sin limite). Si no cambia no registra
     * nada. Un punto que no existe o no es un punto de acopio activo no existe (404).
     */
    @Transactional
    public CapacidadPunto fijar(long puntoId, Integer maximo, long usuarioId) {
        MapSqlParameterSource p = new MapSqlParameterSource("punto", puntoId).addValue("maximo", maximo)
                .addValue("usuario", usuarioId);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM ubicacion WHERE id = :punto AND tipo = 'PUNTO_ACOPIO' AND activo)""",
                p, Boolean.class))) {
            throw ErrorNegocio.noEncontrado("PUNTO_NO_ENCONTRADO", "No encontramos ese punto de acopio.");
        }
        Integer anterior = jdbc.query("SELECT maximo_diario FROM capacidad_entrega WHERE ubicacion_id = :punto FOR UPDATE", p,
                (rs, row) -> rs.getInt("maximo_diario")).stream().findFirst().orElse(null);
        if (!Objects.equals(anterior, maximo)) {
            if (maximo == null) {
                jdbc.update("DELETE FROM capacidad_entrega WHERE ubicacion_id = :punto", p);
            } else {
                jdbc.update("""
                        INSERT INTO capacidad_entrega (ubicacion_id, maximo_diario, actualizado_en, actualizado_por)
                        VALUES (:punto, :maximo, now(), :usuario)
                        ON CONFLICT (ubicacion_id) DO UPDATE
                        SET maximo_diario = EXCLUDED.maximo_diario, actualizado_en = now(),
                            actualizado_por = EXCLUDED.actualizado_por""", p);
            }
            jdbc.update("""
                    INSERT INTO capacidad_entrega_cambio (ubicacion_id, maximo_anterior, maximo_nuevo, realizado_por)
                    VALUES (:punto, CAST(:anterior AS integer), CAST(:maximo AS integer), :usuario)""",
                    p.addValue("anterior", anterior));
        }
        return jdbc.query(PUNTOS + "AND u.id = :punto", p, (rs, row) -> capacidad(rs)).getFirst();
    }

    private static CapacidadPunto capacidad(ResultSet rs) throws SQLException {
        return new CapacidadPunto(rs.getLong("id"), rs.getString("nombre"), rs.getString("departamento"),
                (Integer) rs.getObject("maximo_diario"), rs.getInt("usados"),
                rs.getTimestamp("actualizado_en") == null ? null : rs.getTimestamp("actualizado_en").toInstant(),
                rs.getString("autor"));
    }
}
