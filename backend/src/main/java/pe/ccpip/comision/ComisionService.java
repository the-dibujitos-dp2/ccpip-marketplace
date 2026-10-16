package pe.ccpip.comision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comision.ComisionModelos.Cambio;
import pe.ccpip.comision.ComisionModelos.EstadoCambio;
import pe.ccpip.comision.ComisionModelos.Estado;
import pe.ccpip.comision.ComisionModelos.NuevaComision;
import pe.ccpip.comision.ComisionModelos.Vigente;
import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-90 (RF-68): el superadministrador configura la comision con fecha de vigencia y motivo. Cada
 * cambio queda en {@code comision_configuracion}, que no se edita ni se borra (auditoria).
 *
 * <p>CCPIP-91 y CCPIP-167: al confirmarse un pedido se copia la configuracion vigente y se calculan la
 * comision y el neto de cada linea. El redondeo es unico: HALF_UP a 2 decimales por linea, y el total
 * del pedido es la suma de lineas ya redondeadas, sin volver a redondear.
 */
@Service
public class ComisionService {

    static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final NamedParameterJdbcTemplate jdbc;

    public ComisionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** La configuracion que rige hoy (Lima), o vacio si la Camara aun no fijo ninguna. */
    @Transactional(readOnly = true)
    public Optional<Vigente> vigente() {
        return jdbc.query("""
                SELECT id, porcentaje, base_calculo FROM comision_configuracion
                WHERE cancelado_en IS NULL AND vigente_desde <= %s
                ORDER BY vigente_desde DESC LIMIT 1""".formatted(HOY), new MapSqlParameterSource(),
                (rs, row) -> new Vigente(rs.getLong("id"), rs.getBigDecimal("porcentaje"), rs.getString("base_calculo")))
                .stream().findFirst();
    }

    /** El porcentaje vigente, o nulo si no hay ninguno configurado. */
    public BigDecimal porcentajeVigente() {
        return vigente().map(Vigente::porcentaje).orElse(null);
    }

    /** SA-02: vigente, cambio programado e historial completo, el mas reciente primero. */
    @Transactional(readOnly = true)
    public Estado estado() {
        LocalDate hoy = hoy();
        List<Fila> filas = jdbc.query("""
                SELECT c.id, c.porcentaje, c.base_calculo, c.vigente_desde, c.motivo, c.registrado_en, c.cancelado_en,
                       r.nombre AS registrado_por, x.nombre AS cancelado_por
                FROM comision_configuracion c
                JOIN usuario r ON r.id = c.registrado_por
                LEFT JOIN usuario x ON x.id = c.cancelado_por
                ORDER BY c.vigente_desde DESC, c.id DESC""", new MapSqlParameterSource(), (rs, row) -> fila(rs));

        Long vigenteId = filas.stream()
                .filter(f -> f.canceladoEn() == null && !f.vigenteDesde().isAfter(hoy))
                .map(Fila::id).findFirst().orElse(null);
        List<Cambio> historial = filas.stream().map(f -> f.cambio(estadoDe(f, hoy, vigenteId))).toList();
        Cambio vigente = historial.stream().filter(c -> c.estado() == EstadoCambio.VIGENTE).findFirst().orElse(null);
        Cambio programado = historial.stream().filter(c -> c.estado() == EstadoCambio.PROGRAMADA).findFirst().orElse(null);
        return new Estado(hoy, ComisionModelos.BASE_PRECIO_SIN_ENVIO, vigente, programado, historial);
    }

    /**
     * Registra un cambio que rige desde {@code vigenteDesde}. Uno solo pendiente a la vez: para cambiar
     * un cambio programado, primero se cancela. Si la fecha es hoy, rige de inmediato para los pedidos
     * que se confirmen desde ahora; los ya confirmados conservan su comision.
     */
    @Transactional
    public Estado programar(long usuarioId, NuevaComision nueva) {
        // Dos superadministradores a la vez no deben dejar dos cambios pendientes
        jdbc.getJdbcTemplate().execute("LOCK TABLE comision_configuracion IN SHARE ROW EXCLUSIVE MODE");
        LocalDate hoy = hoy();
        if (nueva.vigenteDesde().isBefore(hoy)) {
            throw ErrorNegocio.invalido("VIGENCIA_PASADA", "La nueva comisión rige desde hoy o desde una fecha futura.");
        }
        Estado actual = estado();
        if (actual.programado() != null) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CAMBIO_YA_PROGRAMADO",
                    "Ya hay un cambio programado. Cancélalo antes de programar otro.");
        }
        if (actual.vigente() != null && actual.vigente().vigenteDesde().equals(nueva.vigenteDesde())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "VIGENCIA_OCUPADA",
                    "Ya hay un cambio que rige desde esa fecha. Elige otra fecha.");
        }
        if (actual.vigente() != null && actual.vigente().porcentaje().compareTo(nueva.porcentaje()) == 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "MISMO_PORCENTAJE", "Ese ya es el porcentaje vigente.");
        }
        jdbc.update("""
                INSERT INTO comision_configuracion (porcentaje, base_calculo, vigente_desde, motivo, registrado_por)
                VALUES (:porcentaje, :base, :desde, :motivo, :usuario)""", new MapSqlParameterSource()
                .addValue("porcentaje", nueva.porcentaje()).addValue("base", ComisionModelos.BASE_PRECIO_SIN_ENVIO)
                .addValue("desde", nueva.vigenteDesde()).addValue("motivo", nueva.motivo().strip())
                .addValue("usuario", usuarioId));
        return estado();
    }

    /** Cancela un cambio que aun no entra en vigor. El vigente y los anteriores no se tocan. */
    @Transactional
    public Estado cancelar(long usuarioId, long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id).addValue("usuario", usuarioId);
        int cancelados = jdbc.update("""
                UPDATE comision_configuracion SET cancelado_por = :usuario, cancelado_en = now()
                WHERE id = :id AND cancelado_en IS NULL AND vigente_desde > %s""".formatted(HOY), p);
        if (cancelados == 0) {
            Integer existe = jdbc.queryForObject("SELECT count(*) FROM comision_configuracion WHERE id = :id", p, Integer.class);
            if (existe == null || existe == 0) {
                throw ErrorNegocio.noEncontrado("CAMBIO_NO_ENCONTRADO", "No encontramos ese cambio de comisión.");
            }
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CAMBIO_NO_CANCELABLE",
                    "Solo se puede cancelar un cambio que aún no entra en vigor.");
        }
        return estado();
    }

    /**
     * Copia en el pedido la comision vigente y calcula la de cada linea. Se llama dentro de la
     * transaccion que lo confirma, despues de fijar {@code confirmado_en}. Sin comision configurada el
     * pedido queda sin ella: no se inventa un porcentaje.
     */
    public void copiarAlPedido(long pedidoId) {
        Optional<Vigente> vigente = vigente();
        if (vigente.isEmpty()) {
            return;
        }
        BigDecimal porcentaje = vigente.get().porcentaje();
        record Linea(long id, BigDecimal subtotal) {
        }
        List<Linea> lineas = jdbc.query("SELECT id, subtotal FROM item_pedido WHERE pedido_id = :id ORDER BY id",
                new MapSqlParameterSource("id", pedidoId), (rs, row) -> new Linea(rs.getLong("id"), rs.getBigDecimal("subtotal")));
        BigDecimal total = BigDecimal.ZERO;
        for (Linea linea : lineas) {
            BigDecimal comision = comision(linea.subtotal(), porcentaje);
            total = total.add(comision);
            jdbc.update("UPDATE item_pedido SET comision_monto = :comision, neto = :neto WHERE id = :id",
                    new MapSqlParameterSource("id", linea.id()).addValue("comision", comision)
                            .addValue("neto", linea.subtotal().subtract(comision)));
        }
        jdbc.update("""
                UPDATE pedido SET comision_configuracion_id = :config, comision_porcentaje = :porcentaje,
                                  comision_base = :base, comision_monto = :monto
                WHERE id = :id AND comision_porcentaje IS NULL""", new MapSqlParameterSource("id", pedidoId)
                .addValue("config", vigente.get().id()).addValue("porcentaje", porcentaje)
                .addValue("base", vigente.get().base()).addValue("monto", total));
    }

    /** La comision de un monto: el unico redondeo del calculo (HALF_UP, 2 decimales). */
    static BigDecimal comision(BigDecimal monto, BigDecimal porcentaje) {
        return monto.multiply(porcentaje).divide(CIEN, 2, RoundingMode.HALF_UP);
    }

    LocalDate hoy() {
        return jdbc.queryForObject("SELECT " + HOY, new MapSqlParameterSource(), LocalDate.class);
    }

    private static EstadoCambio estadoDe(Fila f, LocalDate hoy, Long vigenteId) {
        if (f.canceladoEn() != null) {
            return EstadoCambio.CANCELADA;
        }
        if (f.vigenteDesde().isAfter(hoy)) {
            return EstadoCambio.PROGRAMADA;
        }
        return Long.valueOf(f.id()).equals(vigenteId) ? EstadoCambio.VIGENTE : EstadoCambio.ANTERIOR;
    }

    private record Fila(long id, BigDecimal porcentaje, String base, LocalDate vigenteDesde, String motivo,
                        String registradoPor, Instant registradoEn, String canceladoPor, Instant canceladoEn) {
        Cambio cambio(EstadoCambio estado) {
            return new Cambio(id, porcentaje, base, vigenteDesde, motivo, registradoPor, registradoEn, estado,
                    canceladoPor, canceladoEn);
        }
    }

    private static Fila fila(ResultSet rs) throws SQLException {
        return new Fila(rs.getLong("id"), rs.getBigDecimal("porcentaje"), rs.getString("base_calculo"),
                rs.getObject("vigente_desde", LocalDate.class), rs.getString("motivo"), rs.getString("registrado_por"),
                instante(rs, "registrado_en"), rs.getString("cancelado_por"), instante(rs, "cancelado_en"));
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
