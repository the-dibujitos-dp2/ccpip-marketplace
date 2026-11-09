package pe.ccpip.gobernanza;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Append-only event log. Hook detail allowlist: campo, estado, tipo, codigo, fecha,
 * estadoAnterior/estadoNuevo (enum codes); cantidad, monto, porcentaje, valorAnterior,
 * valorNuevo and pedidoId/loteId/productoId/transferenciaId/solicitudId/analisisId/avisoId
 * (numbers); causa (controlled code). Never include free text, PII, credentials, tokens or account numbers.
 */
@Service
@Transactional
public class AuditoriaEventos {
    private static final Set<String> TEXT = Set.of("campo", "estado", "tipo", "codigo", "fecha", "estadoAnterior", "estadoNuevo", "causa", "decision");
    private static final Set<String> NUM = Set.of("cantidad", "monto", "porcentaje", "valorAnterior", "valorNuevo", "pedidoId", "loteId", "productoId", "transferenciaId", "solicitudId", "analisisId", "avisoId");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_:-]{0,79}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AuditoriaEventos(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public void registrar(Long usuarioId, String accion, String entidad, String referencia, String resultado, Map<String, ?> datos) {
        registrar(usuarioId, accion, entidad, referencia, resultado, null, null, null, datos);
    }
    public void registrar(Long usuarioId, String accion, String entidad, String referencia, String resultado,
            Long ubicacionId, String correlacion, String claveEvento, Map<String, ?> datos) {
        String nombre=null, rol=null, actor="SISTEMA";
        if (usuarioId != null) {
            actor="USUARIO";
            try { var r=jdbc.queryForMap("SELECT u.nombre,r.codigo FROM usuario u JOIN rol r ON r.id=u.rol_id WHERE u.id=? FOR SHARE OF u", usuarioId); nombre=(String)r.get("nombre"); rol=(String)r.get("codigo"); }
            catch (EmptyResultDataAccessException e) { throw new IllegalArgumentException("Usuario inexistente: "+usuarioId,e); }
        }
        insertar(usuarioId,nombre,rol,actor,accion,entidad,referencia,resultado,ubicacionId,correlacion,claveEvento,datos);
    }
    public void anonimo(String accion,String entidad,String referencia,String resultado,Map<String, ?> datos) {
        insertar(null,null,null,"ANONIMO",accion,entidad,referencia,resultado,null,null,null,datos);
    }
    private void insertar(Long uid,String nombre,String rol,String actor,String accion,String entidad,String ref,String result,Long ubic,String corr,String key,Map<String,?> datos) {
        String json=detalle(datos);
        int inserted = jdbc.update("INSERT INTO auditoria_evento(usuario_id,usuario_nombre,rol,actor_tipo,accion,entidad,referencia,resultado,ubicacion_id,correlacion,clave_evento,detalle) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT (clave_evento) DO NOTHING",uid,nombre,rol,actor,accion,entidad,ref,result,ubic,corr,key,json);
        if (inserted == 0) {
            Boolean same = jdbc.queryForObject("""
                    SELECT usuario_id IS NOT DISTINCT FROM CAST(? AS bigint)
                        AND actor_tipo=? AND accion=? AND entidad=? AND referencia=? AND resultado=?
                        AND ubicacion_id IS NOT DISTINCT FROM CAST(? AS bigint)
                        AND correlacion IS NOT DISTINCT FROM CAST(? AS text) AND detalle=?::jsonb
                    FROM auditoria_evento WHERE clave_evento=?""", Boolean.class,
                    uid,actor,accion,entidad,ref,result,ubic,corr,json,key);
            if (!Boolean.TRUE.equals(same)) throw new IllegalStateException("La clave de auditoría tiene una colisión con otro hecho.");
        }
    }
    private String detalle(Map<String,?> datos) {
        if (datos==null || datos.isEmpty()) return "{}";
        for (var e:datos.entrySet()) {
            Object v=e.getValue();
            if (TEXT.contains(e.getKey())) { if (!(v instanceof String s) || !CODE.matcher(s).matches()) throw new IllegalArgumentException("Valor de código inválido: "+e.getKey()); }
            else if (NUM.contains(e.getKey())) { if (!(v instanceof Number) || (v instanceof Double d && !Double.isFinite(d)) || (v instanceof Float f && !Float.isFinite(f))) throw new IllegalArgumentException("Valor numérico inválido: "+e.getKey()); }
            else throw new IllegalArgumentException("Clave de auditoría no permitida: "+e.getKey());
        }
        try { return mapper.writeValueAsString(datos); } catch (JsonProcessingException e) { throw new IllegalArgumentException("Detalle no serializable",e); }
    }
}
