package pe.ccpip.alerta;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.alerta.AlertaModelos.NuevasPreferencias;
import pe.ccpip.alerta.AlertaModelos.Preferencias;
import pe.ccpip.alerta.AlertaModelos.PreferenciasPunto;
import pe.ccpip.alerta.AlertaModelos.Tipo;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;

/**
 * "Configurar alertas" de PW-21 (productor) y A-08 (punto de acopio). CCPIP-145 y CCPIP-79 piden la
 * anticipacion configurable "sin dias arbitrarios": cada productor y cada punto la fija (7, 15, 30 o
 * la que elija). Mientras no la guarde rigen los provisionales de {@code ccpip.alertas.*}.
 *
 * <p>La anticipacion tambien decide que es "por vencer" en el inicio y el almacen del productor y en el
 * inventario del punto, para que la alerta y esas pantallas digan lo mismo.
 */
@Service
public class PreferenciasAlerta {

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;
    private final int diasVencimiento;
    private final int diasEstancado;

    public PreferenciasAlerta(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos,
                              @Value("${ccpip.alertas.dias-vencimiento-provisional:15}") int diasVencimiento,
                              @Value("${ccpip.alertas.dias-estancado-provisional:30}") int diasEstancado) {
        if (diasVencimiento < 1 || diasVencimiento > 365 || diasEstancado < 1 || diasEstancado > 365) {
            throw new IllegalStateException("ccpip.alertas.dias-*-provisional debe estar entre 1 y 365");
        }
        this.jdbc = jdbc;
        this.puntos = puntos;
        this.diasVencimiento = diasVencimiento;
        this.diasEstancado = diasEstancado;
    }

    public int diasVencimientoProvisional() {
        return diasVencimiento;
    }

    public int diasEstancadoProvisional() {
        return diasEstancado;
    }

    /** Dias de anticipacion del productor: el inicio (PW-01), su almacen (PW-16) y sus productos. */
    @Transactional(readOnly = true)
    public int diasVencimientoProductor(long productorId) {
        return deProductor(productorId).diasVencimiento();
    }

    /**
     * Expresion SQL con los dias de anticipacion del punto de la columna {@code ubicacion}. Usa el
     * parametro {@code :diasVencimientoProvisional} (ver {@link #provisionales}).
     */
    public static String diasVencimientoDelPunto(String ubicacion) {
        return "coalesce((SELECT pa.dias_vencimiento FROM preferencia_alerta pa WHERE pa.ubicacion_id = "
                + ubicacion + "), :diasVencimientoProvisional)";
    }

    /** Agrega a {@code p} los provisionales que usan las expresiones SQL de esta clase. */
    public MapSqlParameterSource provisionales(MapSqlParameterSource p) {
        return p.addValue("diasVencimientoProvisional", diasVencimiento)
                .addValue("diasEstancadoProvisional", diasEstancado);
    }

    @Transactional(readOnly = true)
    public Preferencias deProductor(long productorId) {
        return jdbc.query("SELECT * FROM preferencia_alerta WHERE productor_id = :id",
                        new MapSqlParameterSource("id", productorId), (rs, row) -> leer(rs, Tipo.delProductor()))
                .stream().findFirst().orElseGet(() -> provisional(Tipo.delProductor()));
    }

    @Transactional
    public Preferencias guardarProductor(long productorId, long usuarioId, NuevasPreferencias nuevas) {
        guardar("productor_id", productorId, usuarioId, nuevas, Tipo.delProductor());
        return deProductor(productorId);
    }

    /** Las de cada punto del administrador, por nombre. 409 ACOPIO_SIN_ASIGNAR si no tiene ninguno. */
    @Transactional(readOnly = true)
    public List<PreferenciasPunto> dePuntos(long usuarioId) {
        puntos.nombres(usuarioId);
        return jdbc.query("""
                SELECT ub.id AS punto_id, ub.nombre AS punto, pa.*
                FROM usuario_ubicacion uu
                JOIN ubicacion ub ON ub.id = uu.ubicacion_id
                LEFT JOIN preferencia_alerta pa ON pa.ubicacion_id = ub.id
                WHERE uu.usuario_id = :usuario AND ub.tipo = 'PUNTO_ACOPIO'
                ORDER BY ub.nombre, ub.id""", new MapSqlParameterSource("usuario", usuarioId),
                (rs, row) -> new PreferenciasPunto(rs.getLong("punto_id"), rs.getString("punto"),
                        rs.getObject("id") == null ? provisional(Tipo.delPunto()) : leer(rs, Tipo.delPunto())));
    }

    /** Solo un punto que administra (RNF-05): otro no existe para el (404). */
    @Transactional
    public PreferenciasPunto guardarPunto(long usuarioId, long ubicacionId, NuevasPreferencias nuevas) {
        if (dePuntos(usuarioId).stream().noneMatch(p -> p.ubicacionId() == ubicacionId)) {
            throw ErrorNegocio.noEncontrado("PUNTO_NO_ENCONTRADO", "No administras ese punto de acopio.");
        }
        guardar("ubicacion_id", ubicacionId, usuarioId, nuevas, Tipo.delPunto());
        return dePuntos(usuarioId).stream().filter(p -> p.ubicacionId() == ubicacionId).findFirst().orElseThrow();
    }

    private void guardar(String columna, long destino, long usuarioId, NuevasPreferencias nuevas, Set<Tipo> disponibles) {
        if (!disponibles.containsAll(nuevas.tipos())) {
            throw new ErrorNegocio(HttpStatus.BAD_REQUEST, "TIPO_ALERTA_NO_DISPONIBLE",
                    "Elige solo alertas de tu rol.");
        }
        EnumSet<Tipo> silenciados = EnumSet.copyOf(disponibles);
        silenciados.removeAll(nuevas.tipos());
        // Literal de arreglo de PostgreSQL ({A,B}); los nombres del enum no llevan comas ni comillas
        String valores = silenciados.stream().map(Enum::name).collect(Collectors.joining(",", "{", "}"));
        jdbc.update("""
                INSERT INTO preferencia_alerta (%1$s, dias_vencimiento, dias_estancado, tipos_silenciados, correo,
                                                actualizado_por)
                VALUES (:destino, :vencimiento, :estancado, CAST(:silenciados AS varchar[]), :correo, :usuario)
                ON CONFLICT (%1$s) DO UPDATE SET dias_vencimiento = EXCLUDED.dias_vencimiento,
                    dias_estancado = EXCLUDED.dias_estancado, tipos_silenciados = EXCLUDED.tipos_silenciados,
                    correo = EXCLUDED.correo, actualizado_por = EXCLUDED.actualizado_por, actualizado_en = now()"""
                        .formatted(columna),
                new MapSqlParameterSource("destino", destino).addValue("vencimiento", nuevas.diasVencimiento())
                        .addValue("estancado", nuevas.diasEstancado()).addValue("silenciados", valores)
                        .addValue("correo", nuevas.correo()).addValue("usuario", usuarioId));
    }

    private Preferencias provisional(Set<Tipo> disponibles) {
        return new Preferencias(diasVencimiento, diasEstancado, disponibles, true, true, disponibles);
    }

    private static Preferencias leer(ResultSet rs, Set<Tipo> disponibles) throws SQLException {
        EnumSet<Tipo> tipos = EnumSet.copyOf(disponibles);
        Array silenciados = rs.getArray("tipos_silenciados");
        for (Object s : (Object[]) silenciados.getArray()) {
            tipos.remove(Tipo.valueOf((String) s));
        }
        return new Preferencias(rs.getInt("dias_vencimiento"), rs.getInt("dias_estancado"), tipos,
                rs.getBoolean("correo"), false, disponibles);
    }
}
