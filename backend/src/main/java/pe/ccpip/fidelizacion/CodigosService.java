package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.fidelizacion.Beneficio.Tipo;
import pe.ccpip.fidelizacion.BeneficiosModelos.Codigo;
import pe.ccpip.fidelizacion.BeneficiosModelos.NuevoBeneficio;
import pe.ccpip.fidelizacion.BeneficiosModelos.NuevoCodigo;
import pe.ccpip.fidelizacion.FidelizacionModelos.Campana;
import pe.ccpip.fidelizacion.FidelizacionModelos.EstadoCampana;

/**
 * Codigos de descuento de una campana de puntos: el sub administrador los crea para difundirlos (p. ej. CACAO10) y
 * valen mientras rige la campana, con un limite de usos total y por cliente. Un codigo no se edita: si cambia el
 * beneficio, se desactiva y se crea otro, para que cada pedido conserve el que uso.
 */
@Service
public class CodigosService {

    /** Los pedidos vigentes (no cancelados) que usaron el codigo {@code d}. */
    static final String USOS = "pedido pe WHERE pe.codigo_descuento_id = d.id AND pe.estado <> 'CANCELADO'";

    private final NamedParameterJdbcTemplate jdbc;
    private final CampanasService campanas;

    public CodigosService(NamedParameterJdbcTemplate jdbc, CampanasService campanas) {
        this.jdbc = jdbc;
        this.campanas = campanas;
    }

    /** Los codigos de una campana, los activos primero, con sus usos y lo que descontaron. */
    @Transactional(readOnly = true)
    public List<Codigo> codigos(long campanaId) {
        campanas.campana(campanaId);
        return jdbc.query("""
                SELECT d.*, (SELECT count(*) FROM %1$s) AS usos,
                       (SELECT coalesce(sum(pe.descuento_monto), 0) FROM %1$s AND pe.estado <> 'PENDIENTE_PAGO') AS descuento
                FROM codigo_descuento d WHERE d.campana_id = :campana
                ORDER BY d.desactivado_en IS NULL DESC, d.registrado_en DESC""".formatted(USOS),
                new MapSqlParameterSource("campana", campanaId), (rs, row) -> new Codigo(rs.getLong("id"),
                        rs.getLong("campana_id"), rs.getString("codigo"), Tipo.valueOf(rs.getString("tipo")),
                        rs.getBigDecimal("valor"), rs.getBigDecimal("monto_minimo"), rs.getBigDecimal("tope_descuento"),
                        rs.getObject("usos_maximos", Integer.class), rs.getInt("usos_por_cliente"),
                        rs.getObject("desactivado_en") == null, rs.getInt("usos"), rs.getBigDecimal("descuento"),
                        CampanasService.instante(rs, "registrado_en")));
    }

    /** Crea un codigo en una campana que aun no termina. Los codigos son unicos en toda la plataforma. */
    @Transactional
    public List<Codigo> crear(long usuarioId, long campanaId, NuevoCodigo nuevo) {
        Campana campana = campanas.campana(campanaId);
        if (campana.estado() != EstadoCampana.ACTIVA && campana.estado() != EstadoCampana.PROGRAMADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CAMPANA_YA_TERMINO", "Esta campaña ya terminó.");
        }
        NuevoBeneficio b = nuevo.beneficio();
        CuponesService.validar(b);
        String codigo = normal(nuevo.codigo());
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM codigo_descuento WHERE codigo = :codigo)",
                new MapSqlParameterSource("codigo", codigo), Boolean.class))) {
            throw repetido();
        }
        // El indice unico respalda el caso de dos sub administradores creando el mismo codigo a la vez
        try {
            jdbc.update("""
                    INSERT INTO codigo_descuento (campana_id, codigo, tipo, valor, monto_minimo, tope_descuento, usos_maximos,
                                                  usos_por_cliente, registrado_por)
                    VALUES (:campana, :codigo, :tipo, :valor, :minimo, :tope, :maximos, :porCliente, :usuario)""",
                    new MapSqlParameterSource("campana", campanaId).addValue("codigo", codigo)
                            .addValue("tipo", b.tipo().name()).addValue("valor", b.valor())
                            .addValue("minimo", b.montoMinimo() == null ? BigDecimal.ZERO : b.montoMinimo())
                            .addValue("tope", b.topeDescuento()).addValue("maximos", nuevo.usosMaximos())
                            .addValue("porCliente", nuevo.usosPorCliente() == null ? 1 : nuevo.usosPorCliente())
                            .addValue("usuario", usuarioId));
        } catch (DuplicateKeyException e) {
            throw repetido();
        }
        return codigos(campanaId);
    }

    private static ErrorNegocio repetido() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "CODIGO_REPETIDO", "Ese código ya existe. Elige otro.");
    }

    /** Desactiva un codigo: deja de valer desde ahora; los pedidos que ya lo usaron conservan su descuento. */
    @Transactional
    public List<Codigo> desactivar(long usuarioId, long campanaId, long id) {
        int filas = jdbc.update("""
                UPDATE codigo_descuento SET desactivado_por = :usuario, desactivado_en = clock_timestamp()
                WHERE id = :id AND campana_id = :campana AND desactivado_en IS NULL""",
                new MapSqlParameterSource("usuario", usuarioId).addValue("id", id).addValue("campana", campanaId));
        if (filas == 0) {
            throw ErrorNegocio.noEncontrado("CODIGO_NO_ENCONTRADO", "No encontramos ese código activo.");
        }
        return codigos(campanaId);
    }

    /** Los codigos se guardan y se buscan en mayusculas: CACAO10 y cacao10 son el mismo. */
    static String normal(String codigo) {
        return codigo.strip().toUpperCase(Locale.ROOT);
    }
}
