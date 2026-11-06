package pe.ccpip.cobertura;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.cobertura.CoberturaModelos.CoberturaPunto;
import pe.ccpip.cobertura.CoberturaModelos.Zona;
import pe.ccpip.entrega.EntregaModelos.CapacidadDiaria;
import pe.ccpip.entrega.EntregaService;

/**
 * CCPIP-401: lectura por asignacion, sin alterar reglas comerciales ni la consulta del comprador.
 * CCPIP-149: el punto declara los distritos que atiende, solo del catalogo territorial vigente.
 * CCPIP-403 y CCPIP-496, 498: desactiva y reactiva una zona; cada cambio queda registrado con su autor.
 */
@Service
public class CoberturasService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;
    private final EntregaService entregas;
    public CoberturasService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos, EntregaService entregas) {
        this.jdbc = jdbc;
        this.puntos = puntos;
        this.entregas = entregas;
    }
    @Transactional(readOnly = true)
    public List<CoberturaPunto> listar(long usuarioId, Long puntoId) {
        puntos.nombres(usuarioId);
        String filtro = puntoId == null ? "" : " AND u.id=:punto";
        var parametros = new MapSqlParameterSource("usuario", usuarioId);
        if (puntoId != null) parametros.addValue("punto", puntoId);
        Map<Long, Grupo> grupos = new LinkedHashMap<>();
        jdbc.query("""
                SELECT u.id AS punto_id, u.nombre AS punto, c.id AS zona_id, g.codigo,
                       g.departamento, g.provincia, g.distrito, c.activa, c.actualizado_en
                FROM ubicacion u JOIN usuario_ubicacion uu ON uu.ubicacion_id=u.id
                LEFT JOIN cobertura_entrega c ON c.ubicacion_id=u.id
                LEFT JOIN ubigeo g ON g.codigo=c.ubigeo
                WHERE uu.usuario_id=:usuario AND u.tipo='PUNTO_ACOPIO'
                """ + filtro + " ORDER BY u.nombre, u.id, g.departamento, g.provincia, g.distrito, g.codigo",
                parametros, (RowCallbackHandler) rs -> {
                    long id = rs.getLong("punto_id");
                    String nombre = rs.getString("punto");
                    Grupo grupo = grupos.computeIfAbsent(id, k -> new Grupo(id, nombre));
                    if (rs.getObject("zona_id") != null) {
                        grupo.zonas.add(new Zona(rs.getLong("zona_id"), rs.getString("codigo"),
                                rs.getString("departamento"), rs.getString("provincia"), rs.getString("distrito"),
                                rs.getBoolean("activa"), rs.getTimestamp("actualizado_en").toInstant()));
                    }
                });
        if (puntoId != null && grupos.isEmpty()) {
            throw ErrorNegocio.noEncontrado("PUNTO_NO_ENCONTRADO", "El punto no existe o no esta asignado a tu cuenta.");
        }
        return grupos.values().stream().map(g -> g.respuesta(entregas.capacidad(g.id))).toList();
    }
    /** CCPIP-403: deja de ofrecer la zona a domicilio. Desactivar una zona ya inactiva no cambia nada. */
    @Transactional
    public void desactivar(long usuarioId, long zonaId) {
        cambiarEstado(usuarioId, zonaId, false);
    }
    /**
     * CCPIP-496, 498: vuelve a ofrecer a domicilio un distrito que el punto desactivo, sin declararlo otra vez.
     * Se ofrece en la compra si el distrito tiene tarifa de envio (CCPIP-482). Activar una zona ya activa no
     * cambia nada.
     */
    @Transactional
    public void activar(long usuarioId, long zonaId) {
        cambiarEstado(usuarioId, zonaId, true);
    }
    /**
     * Cambia el estado de una zona de un punto propio y registra quien lo hizo (cobertura_entrega_cambio). Una
     * zona ajena responde igual que una inexistente, para no revelarla.
     */
    private void cambiarEstado(long usuarioId, long zonaId, boolean activa) {
        if (zonaId <= 0) throw ErrorNegocio.invalido("ZONA_INVALIDA", "La zona debe tener identificador positivo.");
        var p = new MapSqlParameterSource("zona", zonaId).addValue("usuario", usuarioId).addValue("activa", activa);
        Boolean anterior = jdbc.query("""
                SELECT c.activa FROM cobertura_entrega c
                WHERE c.id=:zona AND EXISTS (
                  SELECT 1 FROM usuario_ubicacion uu WHERE uu.ubicacion_id=c.ubicacion_id AND uu.usuario_id=:usuario)
                FOR UPDATE""", p, (rs, row) -> rs.getBoolean("activa")).stream().findFirst().orElse(null);
        if (anterior == null) {
            throw ErrorNegocio.noEncontrado("ZONA_NO_ENCONTRADA", "La zona no existe o no está asignada a tu cuenta.");
        }
        if (anterior == activa) return;
        jdbc.update("UPDATE cobertura_entrega SET activa=:activa, actualizado_en=now() WHERE id=:zona", p);
        registrar(zonaId, activa, usuarioId);
    }
    private void registrar(long zonaId, boolean activa, long usuarioId) {
        jdbc.update("""
                INSERT INTO cobertura_entrega_cambio (cobertura_id, activa, realizado_por)
                VALUES (:zona, :activa, :usuario)""",
                new MapSqlParameterSource("zona", zonaId).addValue("activa", activa).addValue("usuario", usuarioId));
    }
    /**
     * CCPIP-149 (RF-118): declara que el punto propio entrega en esos distritos del catalogo territorial. Un
     * distrito ya declarado e inactivo se reactiva; uno activo queda igual. No toca pedidos ni entregas: cada
     * entrega guarda su propio distrito, asi que declarar no altera lo ya vendido. Solo Peru: el destino
     * internacional sigue pendiente (C9). La capacidad aprobada aun no esta definida por la Camara.
     */
    @Transactional
    public CoberturaPunto declarar(long usuarioId, long puntoId, Collection<String> ubigeos) {
        puntos.nombres(usuarioId);
        var p = new MapSqlParameterSource("usuario", usuarioId).addValue("punto", puntoId);
        Integer propio = jdbc.queryForObject("""
                SELECT count(*) FROM ubicacion u JOIN usuario_ubicacion uu ON uu.ubicacion_id=u.id
                WHERE u.id=:punto AND uu.usuario_id=:usuario AND u.tipo='PUNTO_ACOPIO'""", p, Integer.class);
        if (propio == null || propio == 0) {
            throw ErrorNegocio.noEncontrado("PUNTO_NO_ENCONTRADO", "El punto no existe o no esta asignado a tu cuenta.");
        }
        List<String> pedidos = ubigeos.stream().map(String::strip).distinct().toList();
        List<String> conocidos = jdbc.queryForList("SELECT codigo FROM ubigeo WHERE codigo IN (:codigos)",
                new MapSqlParameterSource("codigos", pedidos), String.class);
        List<String> faltan = pedidos.stream().filter(c -> !conocidos.contains(c)).toList();
        if (!faltan.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "UBIGEO_NO_CATALOGADO",
                    "Algunos distritos no estan en el catalogo territorial.", faltan);
        }
        for (String ubigeo : pedidos) {
            // Solo devuelve la zona si se creo o se reactivo: esas quedan registradas (CCPIP-496)
            jdbc.queryForList("""
                    INSERT INTO cobertura_entrega (ubicacion_id, ubigeo) VALUES (:punto, :ubigeo)
                    ON CONFLICT (ubicacion_id, ubigeo) DO UPDATE SET activa = true, actualizado_en = now()
                    WHERE NOT cobertura_entrega.activa
                    RETURNING id""", new MapSqlParameterSource(p.getValues()).addValue("ubigeo", ubigeo), Long.class)
                    .forEach(zona -> registrar(zona, true, usuarioId));
        }
        return listar(usuarioId, puntoId).get(0);
    }
    private static final class Grupo {
        private final long id;
        private final String nombre;
        private final List<Zona> zonas = new ArrayList<>();
        private Grupo(long id, String nombre) { this.id = id; this.nombre = nombre; }
        private CoberturaPunto respuesta(CapacidadDiaria capacidad) {
            return new CoberturaPunto(id, nombre, List.copyOf(zonas), capacidad);
        }
    }
}
