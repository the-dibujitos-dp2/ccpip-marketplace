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
import pe.ccpip.gobernanza.AuditoriaModelos.Actor;

/**
 * CCPIP-97 (RF-84): auditoria de operaciones sensibles para el superadministrador. Junta, con UNION ALL,
 * lo que cada modulo ya registra con su autor desde el primer sprint: las acciones de gobernanza
 * ({@link HistorialGobernanzaService#ACCIONES}), los cambios de productos (CCPIP-47), los del catalogo
 * maestro, cada intento de pago (CCPIP-163), aprobado o no, cada cambio de permisos (CCPIP-296) y cada
 * tarifa de envio fijada o cambiada (CCPIP-482), cada capacidad diaria de entrega de un punto (CCPIP-484) y
 * cada zona de cobertura activada o desactivada (CCPIP-496, 498).
 *
 * <p>CCPIP-504: tambien la operacion de los administradores de punto de acopio y de los distribuidores: los
 * movimientos de inventario que registra el punto (entradas, salidas, transferencias, ajustes y mermas), cada
 * cambio de estado de un pedido que hizo uno de esos roles (preparacion, listo, recojo, en camino, entrega),
 * cada entrega que un distribuidor asumio o retiro del punto y cada producto malogrado que el punto resolvio
 * (CCPIP-485, 486). Las ventas automaticas no entran: no las hace nadie del punto.
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
                   CASE c.entidad WHEN 'LINEA' THEN 'LINEA_MAESTRA' WHEN 'UNIDAD' THEN 'UNIDAD' ELSE 'PRODUCTO_MAESTRO' END, c.entidad_id,
                   CASE c.entidad WHEN 'LINEA' THEN (SELECT l.nombre FROM linea_producto l WHERE l.id = c.entidad_id)
                                  WHEN 'UNIDAD' THEN (SELECT um.nombre FROM unidad_medida um WHERE um.id = c.entidad_id)
                                  ELSE (SELECT m.nombre FROM producto_maestro m WHERE m.id = c.entidad_id) END,
                   'EXITOSA'
            FROM catalogo_maestro_auditoria c
            UNION ALL
            SELECT 'PG-' || pg.id, pg.creado_en, pe.usuario_id, 'PAGO', 'PEDIDO', pe.id, pe.numero, pg.estado
            FROM pago pg
            JOIN pedido pe ON pe.id = pg.pedido_id
            UNION ALL
            SELECT 'PC-' || c.id, c.realizado_en, c.realizado_por,
                   CASE WHEN c.concedido THEN 'PERMISO_CONCEDIDO' ELSE 'PERMISO_REVOCADO' END, 'PERMISO', c.id,
                   r.codigo || ' · ' || c.codigo, 'EXITOSA'
            FROM permiso_configurable_cambio c
            JOIN rol r ON r.id = c.rol_id
            UNION ALL
            SELECT 'TE-' || t.id, t.realizado_en, t.realizado_por, 'TARIFA_ENVIO', 'TARIFA_ENVIO', t.id,
                   g.distrito || ' (' || g.provincia || ', ' || g.departamento || ') · '
                       || coalesce('S/ ' || t.monto_anterior::text || ' → ', '') || 'S/ ' || t.monto_nuevo::text,
                   'EXITOSA'
            FROM tarifa_envio_cambio t
            JOIN ubigeo g ON g.codigo = t.ubigeo
            UNION ALL
            SELECT 'CE-' || k.id, k.realizado_en, k.realizado_por, 'CAPACIDAD_ENTREGA', 'CAPACIDAD_ENTREGA', k.id,
                   u.nombre || ' · ' || coalesce(k.maximo_anterior::text, 'sin límite') || ' → '
                       || coalesce(k.maximo_nuevo::text, 'sin límite') || ' por día',
                   'EXITOSA'
            FROM capacidad_entrega_cambio k
            JOIN ubicacion u ON u.id = k.ubicacion_id
            UNION ALL
            SELECT 'CZ-' || z.id, z.realizado_en, z.realizado_por,
                   CASE WHEN z.activa THEN 'COBERTURA_ACTIVADA' ELSE 'COBERTURA_DESACTIVADA' END,
                   'COBERTURA_ENTREGA', z.cobertura_id,
                   u.nombre || ' · ' || g.distrito || ' (' || g.provincia || ', ' || g.departamento || ')', 'EXITOSA'
            FROM cobertura_entrega_cambio z
            JOIN cobertura_entrega c ON c.id = z.cobertura_id
            JOIN ubicacion u ON u.id = c.ubicacion_id
            JOIN ubigeo g ON g.codigo = c.ubigeo
            UNION ALL
            SELECT 'CC-' || c.id, c.registrado_en, c.registrado_por, 'COMISION_PROGRAMADA', 'COMISION', c.id,
                   c.porcentaje::text || '%% · ' || c.motivo, 'EXITOSA'
            FROM comision_configuracion c
            UNION ALL
            SELECT 'CCX-' || c.id, c.cancelado_en, c.cancelado_por, 'COMISION_CANCELADA', 'COMISION', c.id,
                   c.porcentaje::text || '%% · ' || c.motivo, 'EXITOSA'
            FROM comision_configuracion c
            WHERE c.cancelado_en IS NOT NULL
            UNION ALL
            SELECT 'PP-' || p.id, p.registrado_en, p.registrado_por, 'PARAMETRO_CAMBIADO', 'PARAMETRO', p.id,
                   p.clave || ' · ' || p.valor::text, 'EXITOSA'
            FROM parametro_plataforma p
            UNION ALL
            SELECT 'MM-' || m.id, m.registrado_en, m.registrado_por, 'MOTIVO_MERMA_' || m.accion,
                   'MOTIVO_MERMA', m.motivo_id, m.nombre, 'EXITOSA'
            FROM motivo_merma_cambio m
            UNION ALL
            SELECT 'MI-' || m.id, m.registrado_en,
                   CASE WHEN m.tipo='SALIDA_VENTA' THEN NULL::bigint ELSE m.usuario_id END,
                   'MOVIMIENTO_' || m.tipo, 'MOVIMIENTO', m.id,
                   pr.nombre || coalesce(' · lote ' || l.codigo, '') || ' · ' || trim(to_char(m.cantidad, 'FM999999990.###'))
                       || ' und · ' || ub.nombre,
                   'EXITOSA'
            FROM movimiento_inventario m
            LEFT JOIN usuario mu ON mu.id = m.usuario_id
            LEFT JOIN rol mr ON mr.id = mu.rol_id
            JOIN producto pr ON pr.id = m.producto_id
            JOIN ubicacion ub ON ub.id = m.ubicacion_id
            LEFT JOIN lote l ON l.id = m.lote_id
            WHERE m.tipo <> 'SALIDA_VENTA'
              AND (mr.codigo = 'ADMIN_PUNTO' OR (m.usuario_id IS NULL AND EXISTS (
                  SELECT 1 FROM merma ma WHERE ma.movimiento_id = m.id AND ma.automatica
              )))
            UNION ALL
            SELECT 'PH-' || h.id, h.registrado_en, h.usuario_id, 'PEDIDO_' || h.estado, 'PEDIDO', h.pedido_id,
                   pe.numero, 'EXITOSA'
            FROM pedido_estado_historial h
            JOIN pedido pe ON pe.id = h.pedido_id
            JOIN usuario hu ON hu.id = h.usuario_id
            JOIN rol hr ON hr.id = hu.rol_id AND hr.codigo IN ('ADMIN_PUNTO', 'DISTRIBUIDOR')
            UNION ALL
            SELECT 'EA-' || e.id, e.asignada_en, e.distribuidor_id, 'ENTREGA_ASUMIDA', 'PEDIDO', e.pedido_id, pe.numero,
                   'EXITOSA'
            FROM entrega e
            JOIN pedido pe ON pe.id = e.pedido_id
            WHERE e.asignada_en IS NOT NULL AND e.distribuidor_id IS NOT NULL
            UNION ALL
            SELECT 'ER-' || e.id, e.retirada_en, e.distribuidor_id, 'ENTREGA_RETIRADA', 'PEDIDO', e.pedido_id, pe.numero,
                   'EXITOSA'
            FROM entrega e
            JOIN pedido pe ON pe.id = e.pedido_id
            WHERE e.retirada_en IS NOT NULL AND e.distribuidor_id IS NOT NULL
            UNION ALL
            SELECT 'IP-' || ip.id, ip.registrado_en, ip.registrado_por, 'PRODUCTO_MALOGRADO_' || ip.tipo, 'PEDIDO',
                   ip.pedido_id,
                   pe.numero || ' · ' || pr.nombre || ' · ' || trim(to_char(ip.cantidad, 'FM999999990.###')) || ' und',
                   'EXITOSA'
            FROM incidencia_pedido ip
            JOIN pedido pe ON pe.id = ip.pedido_id
            JOIN item_pedido it ON it.id = ip.item_pedido_id
            JOIN producto pr ON pr.id = it.producto_id
            UNION ALL
            SELECT 'SR-' || sr.id, sr.registrada_en, sr.solicitante_id, 'REVISION_MERMA_SOLICITADA', 'MOVIMIENTO',
                   sr.merma_movimiento_id,
                   pr.nombre || ' · lote ' || l.codigo || ' · ' || trim(to_char(sr.cantidad, 'FM999999990.###')) || ' und',
                   'EXITOSA'
            FROM solicitud_revision_merma sr
            JOIN merma m ON m.movimiento_id = sr.merma_movimiento_id
            JOIN lote l ON l.id = m.lote_id
            JOIN producto pr ON pr.id = l.producto_id
            UNION ALL
            SELECT 'RR-' || sr.id, sr.resuelta_en, sr.resuelta_por, 'REVISION_MERMA_' || sr.estado, 'MOVIMIENTO',
                   sr.merma_movimiento_id,
                   pr.nombre || ' · lote ' || l.codigo
                       || coalesce(' · ' || trim(to_char(sr.cantidad_aprobada, 'FM999999990.###')) || ' und', ''),
                   'EXITOSA'
            FROM solicitud_revision_merma sr
            JOIN merma m ON m.movimiento_id = sr.merma_movimiento_id
            JOIN lote l ON l.id = m.lote_id
            JOIN producto pr ON pr.id = l.producto_id
            WHERE sr.resuelta_en IS NOT NULL
            """.formatted(HistorialGobernanzaService.ACCIONES);

    private static final String REGISTROS_INTEGRADOS = """
            SELECT l.*, CASE WHEN l.usuario_id IS NULL THEN 'SISTEMA' ELSE 'USUARIO' END AS actor_tipo,
                   NULL::bigint AS ubicacion_id, l.entidad_id::text AS referencia, NULL::text AS correlacion,
                   NULL::text AS decision, false AS rol_historico,
                   u.nombre AS actor_nombre, ro.codigo AS actor_rol, u.correo AS actor_correo, NULL::text AS metadatos
            FROM (%s) AS l (clave, fecha, usuario_id, accion, entidad, entidad_id, detalle, resultado)
            LEFT JOIN usuario u ON u.id = l.usuario_id
            LEFT JOIN rol ro ON ro.id = u.rol_id
            WHERE NOT EXISTS (SELECT 1 FROM auditoria_evento e WHERE e.clave_evento = l.clave
                OR (l.clave LIKE 'PG-%%' AND e.clave_evento LIKE l.clave || '-%%'))
            UNION ALL
            SELECT coalesce(e.clave_evento, 'AE-' || e.id), e.registrado_en, e.usuario_id, e.accion,
                   e.entidad, CASE WHEN e.referencia ~ '^[0-9]{1,18}$' THEN e.referencia::bigint ELSE e.id END,
                   coalesce(contexto.detalle, e.detalle::text), e.resultado, e.actor_tipo, e.ubicacion_id, e.referencia,
                   e.correlacion, e.detalle ->> 'decision', true, e.usuario_nombre, e.rol, eu.correo, e.detalle::text
            FROM auditoria_evento e
            LEFT JOIN usuario eu ON eu.id = e.usuario_id
            LEFT JOIN (%s) AS contexto (clave, fecha, usuario_id, accion, entidad, entidad_id, detalle, resultado)
                ON contexto.clave = e.clave_evento
                    OR (contexto.clave LIKE 'PG-%%' AND e.clave_evento LIKE contexto.clave || '-%%')
            """.formatted(REGISTROS, REGISTROS);

    private static final String DESDE_REGISTROS = """
            FROM (%s) AS r
            LEFT JOIN (%s) AS razones (clave, fecha, autor, tipo, accion, sobre, detalle, objeto, objeto_id, motivo)
                ON razones.clave=r.clave
            WHERE %s BETWEEN :desde AND :hasta
            """.formatted(REGISTROS_INTEGRADOS, HistorialGobernanzaService.ACCIONES, FECHA_LIMA.formatted("r.fecha"));

    private static final String FILTROS = """
              AND (CAST(:usuario AS text) IS NULL
                   OR lower(inmutable_unaccent(r.actor_nombre)) LIKE lower(inmutable_unaccent(:usuario)) ESCAPE '\\'
                   OR lower(r.actor_correo) LIKE lower(:usuario) ESCAPE '\\')
              AND (CAST(:rol AS text) IS NULL OR r.actor_rol = :rol)
              AND (CAST(:accion AS text) IS NULL OR r.accion = :accion)
              AND (CAST(:entidad AS text) IS NULL OR r.entidad = :entidad)
              AND (CAST(:resultado AS text) IS NULL OR r.resultado = :resultado)
              AND (CAST(:actor AS text) IS NULL OR r.actor_tipo = :actor)
              AND (CAST(:ubicacion AS bigint) IS NULL OR r.ubicacion_id = :ubicacion)
              AND (CAST(:referencia AS text) IS NULL OR r.referencia = :referencia)
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
                .addValue("actor", f.actor() == null ? null : f.actor().name())
                .addValue("ubicacion", f.ubicacion())
                .addValue("referencia", f.referencia() == null || f.referencia().isBlank() ? null : f.referencia().trim())
                .addValue("tamano", TAMANO).addValue("desplazamiento", (long) pagina * TAMANO);

        Long total = jdbc.queryForObject("SELECT count(*) " + DESDE_REGISTROS + FILTROS, p, Long.class);
        List<Registro> registros = jdbc.query("""
                SELECT r.*, r.actor_nombre AS usuario, r.actor_correo AS correo, r.actor_rol AS rol, razones.motivo
                %s%s
                ORDER BY r.fecha DESC, r.clave DESC
                LIMIT :tamano OFFSET :desplazamiento""".formatted(DESDE_REGISTROS, FILTROS), p,
                (rs, row) -> new Registro(rs.getString("clave"), rs.getTimestamp("fecha").toInstant(),
                        rs.getObject("usuario_id", Long.class), rs.getString("usuario"), rs.getString("correo"),
                        rs.getString("rol") == null ? null : Rol.valueOf(rs.getString("rol")), rs.getString("accion"),
                        Entidad.valueOf(rs.getString("entidad")), rs.getLong("entidad_id"), rs.getString("detalle"),
                        Resultado.valueOf(rs.getString("resultado")), Actor.valueOf(rs.getString("actor_tipo")),
                        rs.getObject("ubicacion_id", Long.class), rs.getString("referencia"), rs.getString("correlacion"),
                        rs.getString("decision"), rs.getBoolean("rol_historico"), rs.getString("metadatos"), motivoSeguro(rs.getString("motivo"))));
        List<String> acciones = jdbc.queryForList(
                "SELECT DISTINCT r.accion " + DESDE_REGISTROS + "ORDER BY r.accion", p, String.class);
        long cuantos = total == null ? 0 : total;
        int totalPaginas = (int) Math.max(1, (cuantos + TAMANO - 1) / TAMANO);
        return new PaginaAuditoria(rango.desde(), rango.hasta(), registros, pagina, TAMANO, cuantos, totalPaginas,
                acciones);
    }

    private static String motivoSeguro(String texto) {
        if (texto == null) return null;
        String correos = enmascaraCorreos(texto);
        return correos.replaceAll("(?:\\+?+[0-9][ .-]?+){7,}+", "[oculto]").strip();
    }

    /** Scans each local/domain candidate at most twice, without recursive regex backtracking. */
    private static String enmascaraCorreos(String texto) {
        StringBuilder resultado = new StringBuilder(texto.length());
        int i = 0;
        int copiado = 0;
        while (i < texto.length()) {
            int inicio = i;
            while (i < texto.length() && esCaracterCorreo(texto.charAt(i))) i++;
            if (i == inicio) {
                i++;
                continue;
            }
            if (i == texto.length() || texto.charAt(i) != '@') continue;
            int dominio = i + 1;
            int fin = finCorreo(texto, dominio);
            if (fin >= 0) {
                resultado.append(texto, copiado, inicio).append("[oculto]");
                copiado = fin;
                i = fin;
            } else {
                i = dominio;
            }
        }
        return copiado == 0 ? texto : resultado.append(texto, copiado, texto.length()).toString();
    }

    /** Last valid ASCII suffix, preserving punctuation and partial matches of the legacy pattern. */
    private static int finCorreo(String texto, int dominio) {
        int fin = -1;
        int letrasSufijo = -1;
        for (int i = dominio; i < texto.length() && esCaracterDominio(texto.charAt(i)); i++) {
            char c = texto.charAt(i);
            if (c == '.') {
                letrasSufijo = i > dominio ? 0 : -1;
            } else if (esLetraAscii(c) && letrasSufijo >= 0) {
                letrasSufijo++;
                if (letrasSufijo >= 2) fin = i + 1;
            } else {
                letrasSufijo = -1;
            }
        }
        return fin;
    }

    private static boolean esCaracterCorreo(char c) {
        return esLetraAscii(c) || (c >= '0' && c <= '9') || "._%+-".indexOf(c) >= 0;
    }

    private static boolean esCaracterDominio(char c) {
        return esLetraAscii(c) || (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    private static boolean esLetraAscii(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
