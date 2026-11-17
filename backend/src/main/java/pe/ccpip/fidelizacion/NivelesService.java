package pe.ccpip.fidelizacion;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.fidelizacion.BeneficiosModelos.Nivel;
import pe.ccpip.fidelizacion.BeneficiosModelos.NivelCliente;
import pe.ccpip.fidelizacion.BeneficiosModelos.NuevoNivel;

/**
 * CCPIP-88 (RF-94): beneficios por recurrencia. El sub administrador define niveles por pedidos entregados (p. ej.
 * Plata desde 5, Oro desde 15) y cada nivel multiplica los puntos que gana el comprador. Un cambio rige para las
 * entregas que vienen; los puntos ya acreditados no cambian. Un nivel retirado deja de regir, pero queda su historia.
 */
@Service
public class NivelesService {

    private static final String VIGENTES = """
            SELECT id, nombre, pedidos_minimos, multiplicador FROM nivel_fidelizacion
            WHERE retirado_en IS NULL ORDER BY pedidos_minimos""";

    private final NamedParameterJdbcTemplate jdbc;

    public NivelesService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Los niveles vigentes, del primero al mas alto. */
    @Transactional(readOnly = true)
    public List<Nivel> niveles() {
        return jdbc.query(VIGENTES, new MapSqlParameterSource(), (rs, row) -> new Nivel(rs.getLong("id"),
                rs.getString("nombre"), rs.getInt("pedidos_minimos"), rs.getBigDecimal("multiplicador")));
    }

    @Transactional
    public List<Nivel> crear(long usuarioId, NuevoNivel nuevo) {
        exigirUnico(nuevo, null);
        guardar(() -> jdbc.update("""
                INSERT INTO nivel_fidelizacion (nombre, pedidos_minimos, multiplicador, registrado_por)
                VALUES (:nombre, :pedidos, :multiplicador, :usuario)""", parametros(nuevo).addValue("usuario", usuarioId)));
        return niveles();
    }

    @Transactional
    public List<Nivel> editar(long usuarioId, long id, NuevoNivel cambios) {
        exigirUnico(cambios, id);
        guardar(() -> exigirUno(jdbc.update("""
                UPDATE nivel_fidelizacion SET nombre = :nombre, pedidos_minimos = :pedidos, multiplicador = :multiplicador,
                       actualizado_por = :usuario, actualizado_en = clock_timestamp()
                WHERE id = :id AND retirado_en IS NULL""", parametros(cambios).addValue("usuario", usuarioId).addValue("id", id))));
        return niveles();
    }

    @Transactional
    public List<Nivel> retirar(long usuarioId, long id) {
        exigirUno(jdbc.update("""
                UPDATE nivel_fidelizacion SET retirado_por = :usuario, retirado_en = clock_timestamp()
                WHERE id = :id AND retirado_en IS NULL""", new MapSqlParameterSource("usuario", usuarioId).addValue("id", id)));
        return niveles();
    }

    /** El nivel que alcanza quien tiene {@code entregados} pedidos entregados, si alguno. */
    Optional<Nivel> nivelPara(int entregados) {
        return niveles().stream().filter(n -> n.pedidosMinimos() <= entregados).reduce((a, b) -> b);
    }

    /** Donde esta el comprador y cuanto le falta para el siguiente nivel. */
    NivelCliente delCliente(long usuarioId, List<Nivel> niveles) {
        Integer entregados = jdbc.queryForObject("""
                SELECT count(*) FROM pedido WHERE usuario_id = :usuario AND estado = 'ENTREGADO'""",
                new MapSqlParameterSource("usuario", usuarioId), Integer.class);
        int n = entregados == null ? 0 : entregados;
        Nivel actual = niveles.stream().filter(v -> v.pedidosMinimos() <= n).reduce((a, b) -> b).orElse(null);
        Nivel siguiente = niveles.stream().filter(v -> v.pedidosMinimos() > n).findFirst().orElse(null);
        return new NivelCliente(n, actual, siguiente, siguiente == null ? null : siguiente.pedidosMinimos() - n);
    }

    private static MapSqlParameterSource parametros(NuevoNivel n) {
        return new MapSqlParameterSource("nombre", n.nombre().strip()).addValue("pedidos", n.pedidosMinimos())
                .addValue("multiplicador", n.multiplicador());
    }

    /** Dos niveles vigentes no comparten nombre ni numero de pedidos ({@code id}: el que se edita). */
    private void exigirUnico(NuevoNivel n, Long id) {
        Boolean repetido = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM nivel_fidelizacion
                               WHERE retirado_en IS NULL AND (CAST(:id AS bigint) IS NULL OR id <> :id)
                                 AND (lower(nombre) = lower(:nombre) OR pedidos_minimos = :pedidos))""",
                parametros(n).addValue("id", id), Boolean.class);
        if (Boolean.TRUE.equals(repetido)) {
            throw repetido();
        }
    }

    private static ErrorNegocio repetido() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "NIVEL_REPETIDO",
                "Ya hay un nivel con ese nombre o desde esa cantidad de pedidos.");
    }

    /** Respaldo si dos sub administradores guardan a la vez el mismo nivel. */
    private static void guardar(Runnable escritura) {
        try {
            escritura.run();
        } catch (DuplicateKeyException e) {
            throw repetido();
        }
    }

    private static int exigirUno(int filas) {
        if (filas == 0) {
            throw ErrorNegocio.noEncontrado("NIVEL_NO_ENCONTRADO", "No encontramos ese nivel.");
        }
        return filas;
    }
}
