package pe.ccpip.merma;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.merma.MermaModelos.Causa;
import pe.ccpip.merma.MermaModelos.DatosMotivo;
import pe.ccpip.merma.MermaModelos.Motivo;

/**
 * Catalogo controlado de causas de merma: el superadministrador lo consulta (CCPIP-77), crea una causa
 * (CCPIP-298), la modifica (CCPIP-299) y la desactiva (CCPIP-300). La merma ya registrada guarda el
 * nombre con el que se marco, asi que ningun cambio la altera. Cada cambio queda en
 * motivo_merma_cambio con quien y cuando ("causas trazables").
 */
@Service
public class MotivosMermaService {

    private static final String COLUMNAS = """
            SELECT m.id, m.nombre, m.nombre_en, m.requiere_detalle, m.activo, m.sistema, m.actualizado_en,
                   (SELECT count(*) FROM merma x WHERE x.motivo_id = m.id) AS usos
            FROM motivo_merma m
            """;

    private final JdbcTemplate jdbc;

    public MotivosMermaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Todas, activas primero y por nombre. */
    @Transactional(readOnly = true)
    public List<Motivo> listar() {
        return jdbc.query(COLUMNAS + "ORDER BY m.activo DESC, m.sistema DESC, lower(m.nombre)", MotivosMermaService::motivo);
    }

    /** Las que ofrece el formulario de merma (A-04): solo activas. */
    @Transactional(readOnly = true)
    public List<Causa> activas() {
        return jdbc.query("""
                SELECT id, nombre, nombre_en, requiere_detalle FROM motivo_merma
                WHERE activo ORDER BY sistema DESC, requiere_detalle, lower(nombre)""",
                (rs, row) -> new Causa(rs.getLong("id"), rs.getString("nombre"), rs.getString("nombre_en"),
                        rs.getBoolean("requiere_detalle")));
    }

    @Transactional
    public Motivo crear(long usuarioId, DatosMotivo datos) {
        sinRepetir(datos, 0);
        Long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO motivo_merma (nombre, nombre_en, requiere_detalle, actualizado_por)
                    VALUES (?, ?, ?, ?) RETURNING id""", Long.class,
                    datos.nombre().trim(), datos.nombreEn().trim(), datos.requiereDetalle(), usuarioId);
        } catch (DuplicateKeyException e) {
            throw repetido();
        }
        return registrar(id, "CREADO", usuarioId);
    }

    /** Cambia nombre o detalle. La merma ya marcada conserva el nombre anterior. */
    @Transactional
    public Motivo modificar(long usuarioId, long id, DatosMotivo datos) {
        obtener(id);
        sinRepetir(datos, id);
        try {
            jdbc.update("""
                    UPDATE motivo_merma SET nombre = ?, nombre_en = ?, requiere_detalle = ?, actualizado_por = ?,
                                            actualizado_en = now()
                    WHERE id = ?""", datos.nombre().trim(), datos.nombreEn().trim(), datos.requiereDetalle(),
                    usuarioId, id);
        } catch (DuplicateKeyException e) {
            throw repetido();
        }
        return registrar(id, "MODIFICADO", usuarioId);
    }

    /** Deja de ofrecerse para marcar merma; su historial queda. La causa de la merma automatica no se desactiva. */
    @Transactional
    public Motivo desactivar(long usuarioId, long id) {
        Motivo motivo = obtener(id);
        if (motivo.sistema()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "MOTIVO_DEL_SISTEMA",
                    "Esta causa la usa la merma automática por vencimiento: no se puede desactivar.");
        }
        if (!motivo.activo()) {
            return motivo;
        }
        jdbc.update("UPDATE motivo_merma SET activo = false, actualizado_por = ?, actualizado_en = now() WHERE id = ?",
                usuarioId, id);
        return registrar(id, "DESACTIVADO", usuarioId);
    }

    /** Vuelve a ofrecerla, por si se desactivo por error. */
    @Transactional
    public Motivo activar(long usuarioId, long id) {
        Motivo motivo = obtener(id);
        if (motivo.activo()) {
            return motivo;
        }
        jdbc.update("UPDATE motivo_merma SET activo = true, actualizado_por = ?, actualizado_en = now() WHERE id = ?",
                usuarioId, id);
        return registrar(id, "ACTIVADO", usuarioId);
    }

    private Motivo obtener(long id) {
        return jdbc.query(COLUMNAS + "WHERE m.id = ? FOR UPDATE OF m", MotivosMermaService::motivo, id)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("MOTIVO_NO_ENCONTRADO", "La causa de merma no existe."));
    }

    /** Deja constancia del cambio con la causa como quedo. */
    private Motivo registrar(long id, String accion, long usuarioId) {
        jdbc.update("""
                INSERT INTO motivo_merma_cambio (motivo_id, accion, nombre, nombre_en, requiere_detalle, activo, registrado_por)
                SELECT id, ?, nombre, nombre_en, requiere_detalle, activo, ? FROM motivo_merma WHERE id = ?""",
                accion, usuarioId, id);
        return jdbc.query(COLUMNAS + "WHERE m.id = ?", MotivosMermaService::motivo, id).get(0);
    }

    /** Antes de escribir: el indice unico queda como respaldo ante dos altas simultaneas. */
    private void sinRepetir(DatosMotivo datos, long id) {
        Boolean existe = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM motivo_merma
                               WHERE id <> ? AND (lower(btrim(nombre)) = lower(btrim(?)) OR lower(btrim(nombre_en)) = lower(btrim(?))))""",
                Boolean.class, id, datos.nombre(), datos.nombreEn());
        if (Boolean.TRUE.equals(existe)) {
            throw repetido();
        }
    }

    private static ErrorNegocio repetido() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "MOTIVO_REPETIDO", "Ya existe una causa con ese nombre.");
    }

    private static Motivo motivo(ResultSet rs, int row) throws SQLException {
        return new Motivo(rs.getLong("id"), rs.getString("nombre"), rs.getString("nombre_en"),
                rs.getBoolean("requiere_detalle"), rs.getBoolean("activo"), rs.getBoolean("sistema"), rs.getLong("usos"),
                rs.getObject("actualizado_en", Timestamp.class).toInstant());
    }
}
