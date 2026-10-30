package pe.ccpip.gobernanza;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.Busqueda;
import pe.ccpip.comun.RangoFechas;
import pe.ccpip.gobernanza.AuditoriaModelos.Entidad;
import pe.ccpip.gobernanza.AuditoriaModelos.Filtro;
import pe.ccpip.gobernanza.AuditoriaModelos.PaginaAuditoria;
import pe.ccpip.gobernanza.AuditoriaModelos.Registro;
import pe.ccpip.gobernanza.AuditoriaModelos.Resultado;

/**
 * CCPIP-97 (RF-84): auditoria de operaciones sensibles para el superadministrador. Junta, con UNION ALL,
 * lo que cada modulo ya registra con su autor desde el primer sprint: las acciones de gobernanza
 * ({@link HistorialGobernanzaService#ACCIONES}), los cambios de productos (CCPIP-47), los del catalogo
 * maestro y cada intento de pago (CCPIP-163), aprobado o no.
 *
 * <p>Solo lee: todas esas tablas son de solo insercion o de un unico cambio de estado, asi que ningun
 * registro se puede alterar desde aqui, y una operacion nueva de esos modulos aparece sola.
 */
@Service
public class AuditoriaService {

    public static final int TAMANO = 25;

    private static final String FECHA_LIMA = "(%s AT TIME ZONE 'America/Lima')::date";

    /** Cada fila: clave, fecha, usuario_id, accion, entidad, entidad_id, detalle y resultado. */
    static final String REGISTROS = """
            SELECT g.clave, g.fecha, g.autor, g.accion, g.objeto, g.objeto_id,
                   CASE WHEN g.detalle IS NULL THEN g.sobre ELSE g.sobre || ' · ' || g.detalle END, 'EXITOSA'
            FROM (%s) AS g (clave, fecha, autor, tipo, accion, sobre, detalle, objeto, objeto_id, motivo)
            UNION ALL
            SELECT 'PA-' || a.id, a.registrado_en, a.usuario_id, 'PRODUCTO_' || a.accion, 'PRODUCTO', a.producto_id,
                   p.nombre, 'EXITOSA'
            FROM producto_auditoria a
            JOIN producto p ON p.id = a.producto_id
            UNION ALL
            SELECT 'CM-' || c.id, c.registrado_en, c.usuario_id, 'CATALOGO_' || c.accion,
                   CASE c.entidad WHEN 'LINEA' THEN 'LINEA_MAESTRA' ELSE 'PRODUCTO_MAESTRO' END, c.entidad_id,
                   CASE c.entidad WHEN 'LINEA' THEN (SELECT l.nombre FROM linea_producto l WHERE l.id = c.entidad_id)
                                  ELSE (SELECT m.nombre FROM producto_maestro m WHERE m.id = c.entidad_id) END,
                   'EXITOSA'
            FROM catalogo_maestro_auditoria c
            UNION ALL
            SELECT 'PG-' || pg.id, pg.creado_en, pe.usuario_id, 'PAGO', 'PEDIDO', pe.id, pe.numero, pg.estado
            FROM pago pg
            JOIN pedido pe ON pe.id = pg.pedido_id
            """.formatted(HistorialGobernanzaService.ACCIONES);

    private static final String DESDE_REGISTROS = """
            FROM (%s) AS r (clave, fecha, usuario_id, accion, entidad, entidad_id, detalle, resultado)
            LEFT JOIN usuario u ON u.id = r.usuario_id
            LEFT JOIN rol ro ON ro.id = u.rol_id
            WHERE %s BETWEEN :desde AND :hasta
            """.formatted(REGISTROS, FECHA_LIMA.formatted("r.fecha"));

    private static final String FILTROS = """
              AND (CAST(:usuario AS text) IS NULL
                   OR lower(inmutable_unaccent(u.nombre)) LIKE lower(inmutable_unaccent(:usuario)) ESCAPE '\\'
                   OR lower(u.correo) LIKE lower(:usuario) ESCAPE '\\')
              AND (CAST(:rol AS text) IS NULL OR ro.codigo = :rol)
              AND (CAST(:accion AS text) IS NULL OR r.accion = :accion)
              AND (CAST(:entidad AS text) IS NULL OR r.entidad = :entidad)
              AND (CAST(:resultado AS text) IS NULL OR r.resultado = :resultado)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AuditoriaService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Una pagina de los registros del periodo que cumplen los filtros, del mas reciente al mas antiguo.
     * Sin fechas, los ultimos 30 dias de Lima; como maximo un año. {@code acciones}: los codigos de accion
     * del periodo, para el filtro.
     */
    @Transactional(readOnly = true)
    public PaginaAuditoria consultar(Filtro f, int pagina) {
        LocalDate hoy = jdbc.getJdbcTemplate().queryForObject("SELECT " + FECHA_LIMA.formatted("now()"), LocalDate.class);
        RangoFechas rango = RangoFechas.resolver(f.desde(), f.hasta(), hoy, fin -> fin.minusDays(29));
        MapSqlParameterSource p = new MapSqlParameterSource("desde", rango.desde()).addValue("hasta", rango.hasta())
                .addValue("usuario", Busqueda.patron(f.usuario()))
                .addValue("rol", f.rol() == null ? null : f.rol().name())
                .addValue("accion", f.accion() == null || f.accion().isBlank() ? null : f.accion().trim())
                .addValue("entidad", f.entidad() == null ? null : f.entidad().name())
                .addValue("resultado", f.resultado() == null ? null : f.resultado().name())
                .addValue("tamano", TAMANO).addValue("desplazamiento", (long) pagina * TAMANO);

        Long total = jdbc.queryForObject("SELECT count(*) " + DESDE_REGISTROS + FILTROS, p, Long.class);
        List<Registro> registros = jdbc.query("""
                SELECT r.*, u.nombre AS usuario, u.correo, ro.codigo AS rol
                %s%s
                ORDER BY r.fecha DESC, r.clave DESC
                LIMIT :tamano OFFSET :desplazamiento""".formatted(DESDE_REGISTROS, FILTROS), p,
                (rs, row) -> new Registro(rs.getString("clave"), rs.getTimestamp("fecha").toInstant(),
                        rs.getObject("usuario_id", Long.class), rs.getString("usuario"), rs.getString("correo"),
                        rs.getString("rol") == null ? null : Rol.valueOf(rs.getString("rol")), rs.getString("accion"),
                        Entidad.valueOf(rs.getString("entidad")), rs.getLong("entidad_id"), rs.getString("detalle"),
                        Resultado.valueOf(rs.getString("resultado"))));
        List<String> acciones = jdbc.queryForList(
                "SELECT DISTINCT r.accion " + DESDE_REGISTROS + "ORDER BY r.accion", p, String.class);
        long cuantos = total == null ? 0 : total;
        int totalPaginas = (int) Math.max(1, (cuantos + TAMANO - 1) / TAMANO);
        return new PaginaAuditoria(rango.desde(), rango.hasta(), registros, pagina, TAMANO, cuantos, totalPaginas,
                acciones);
    }
}
