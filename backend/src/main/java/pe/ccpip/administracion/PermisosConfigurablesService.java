package pe.ccpip.administracion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-296 (RF-172): el superadministrador concede o revoca los permisos configurables de cada rol. Las
 * restricciones de gobernanza no se conceden nunca (lo impide tambien la base) y cada cambio queda, con su
 * autor y motivo, en un registro de solo insercion que muestra la auditoria (CCPIP-97).
 */
@Service
public class PermisosConfigurablesService {

    /**
     * Un permiso de un rol. {@code restringido}: restriccion de gobernanza, nunca concedida.
     * {@code actualizadoPor} es el nombre de quien hizo el ultimo cambio; nulo si nunca cambio.
     */
    public record PermisoRol(String codigo, boolean restringido, boolean concedido, Instant actualizadoEn,
                             String actualizadoPor) {
    }

    /** Los permisos configurables de un rol, en orden: primero los que se pueden cambiar. */
    public record PermisosDeRol(Rol rol, List<PermisoRol> permisos) {
    }

    private final JdbcTemplate jdbc;

    public PermisosConfigurablesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Los roles que tienen permisos configurables, en el orden del catalogo de roles. */
    @Transactional(readOnly = true)
    public List<PermisosDeRol> listar() {
        Map<Rol, List<PermisoRol>> porRol = new EnumMap<>(Rol.class);
        jdbc.query("""
                SELECT r.codigo AS rol, pc.codigo, pc.restringido, pc.concedido, pc.actualizado_en, u.nombre
                FROM permiso_configurable pc
                JOIN rol r ON r.id = pc.rol_id
                LEFT JOIN usuario u ON u.id = pc.actualizado_por
                ORDER BY r.id, pc.restringido, pc.codigo""", rs -> {
            porRol.computeIfAbsent(Rol.valueOf(rs.getString("rol")), r -> new ArrayList<>())
                    .add(new PermisoRol(rs.getString("codigo"), rs.getBoolean("restringido"), rs.getBoolean("concedido"),
                            rs.getTimestamp("actualizado_en") == null ? null : rs.getTimestamp("actualizado_en").toInstant(),
                            rs.getString("nombre")));
        });
        return porRol.entrySet().stream().map(e -> new PermisosDeRol(e.getKey(), e.getValue())).toList();
    }

    /**
     * Concede o revoca {@code codigo} al rol. Si ya estaba asi, no registra nada. Una restriccion de
     * gobernanza no se concede (RESTRICCION_GOBERNANZA); un permiso que el rol no tiene configurable, 404.
     */
    @Transactional
    public PermisosDeRol cambiar(Rol rol, String codigo, boolean concedido, String motivo, long usuarioId) {
        List<Map<String, Object>> filas = jdbc.queryForList("""
                SELECT pc.rol_id, pc.restringido, pc.concedido
                FROM permiso_configurable pc JOIN rol r ON r.id = pc.rol_id
                WHERE r.codigo = ? AND pc.codigo = ?
                FOR UPDATE OF pc""", rol.name(), codigo);
        if (filas.isEmpty()) {
            throw ErrorNegocio.noEncontrado("PERMISO_NO_CONFIGURABLE", "Ese permiso no es configurable para el rol.");
        }
        Map<String, Object> actual = filas.get(0);
        if (concedido && Boolean.TRUE.equals(actual.get("restringido"))) {
            throw ErrorNegocio.invalido("RESTRICCION_GOBERNANZA",
                    "La administración no puede editar productos, tiendas, pedidos, datos de compradores ni existencias.");
        }
        if (concedido != Boolean.TRUE.equals(actual.get("concedido"))) {
            Number rolId = (Number) actual.get("rol_id");
            jdbc.update("""
                    UPDATE permiso_configurable SET concedido = ?, actualizado_en = now(), actualizado_por = ?
                    WHERE rol_id = ? AND codigo = ?""", concedido, usuarioId, rolId.shortValue(), codigo);
            jdbc.update("""
                    INSERT INTO permiso_configurable_cambio (rol_id, codigo, concedido, motivo, realizado_por)
                    VALUES (?, ?, ?, ?, ?)""", rolId.shortValue(), codigo, concedido, motivo.trim(), usuarioId);
        }
        return listar().stream().filter(p -> p.rol() == rol).findFirst().orElseThrow();
    }
}
