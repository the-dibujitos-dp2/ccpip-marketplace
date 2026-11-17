package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.fidelizacion.Beneficio.Tipo;
import pe.ccpip.fidelizacion.BeneficiosModelos.Cupon;
import pe.ccpip.fidelizacion.BeneficiosModelos.CuponCanjeable;
import pe.ccpip.fidelizacion.BeneficiosModelos.EstadoCatalogo;
import pe.ccpip.fidelizacion.BeneficiosModelos.EstadoCupon;
import pe.ccpip.fidelizacion.BeneficiosModelos.MiCupon;
import pe.ccpip.fidelizacion.BeneficiosModelos.MisPuntos;
import pe.ccpip.fidelizacion.BeneficiosModelos.Movimiento;
import pe.ccpip.fidelizacion.BeneficiosModelos.Nivel;
import pe.ccpip.fidelizacion.BeneficiosModelos.NuevoBeneficio;
import pe.ccpip.fidelizacion.BeneficiosModelos.NuevoCupon;
import pe.ccpip.fidelizacion.BeneficiosModelos.TipoMovimiento;

/**
 * CCPIP-89 (RF-95): el sub administrador arma un catalogo de cupones de descuento que los compradores canjean con
 * sus puntos. Canjear resta los puntos y entrega un cupon con su propio codigo, que vale {@code vigencia_dias} dias y
 * se usa en una sola compra. CCPIP-173 y CCPIP-374: el comprador ve su saldo y sus movimientos.
 *
 * <p>El saldo es lo acumulado menos lo canjeado. El canje bloquea la fila del comprador: dos canjes a la vez no
 * gastan los mismos puntos.
 */
@Service
public class CuponesService {

    /** El estado de un cupon canjeado {@code cc}, segun el pedido que lo usa y su vencimiento. */
    static final String ESTADO_CUPON = """
            CASE WHEN pe.estado = 'PENDIENTE_PAGO' THEN 'EN_USO'
                 WHEN pe.id IS NOT NULL THEN 'USADO'
                 WHEN cc.vence_en <= now() THEN 'VENCIDO'
                 ELSE 'DISPONIBLE' END""";
    /** El pedido vigente (no cancelado) que usa el cupon {@code cc}, si hay. */
    static final String PEDIDO_DEL_CUPON = "LEFT JOIN pedido pe ON pe.cupon_cliente_id = cc.id AND pe.estado <> 'CANCELADO'";

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";
    /** El cupon {@code c} se puede canjear hoy: activo y dentro de su periodo. */
    static final String CANJEABLE = "c.activo AND c.disponible_desde <= " + HOY
            + " AND (c.disponible_hasta IS NULL OR c.disponible_hasta >= " + HOY + ")";
    private static final String ESTADO_CATALOGO = """
            CASE WHEN NOT c.activo OR c.disponible_hasta < %1$s THEN 'FINALIZADO'
                 WHEN c.disponible_desde > %1$s THEN 'PROGRAMADO' ELSE 'ACTIVO' END""".formatted(HOY);

    private static final String CARACTERES = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom AZAR = new SecureRandom();

    private final NamedParameterJdbcTemplate jdbc;
    private final NivelesService niveles;

    public CuponesService(NamedParameterJdbcTemplate jdbc, NivelesService niveles) {
        this.jdbc = jdbc;
        this.niveles = niveles;
    }

    // --- Catalogo (sub administrador) ---

    /** Todos los cupones del catalogo, los activos primero, con sus canjes y usos. */
    @Transactional(readOnly = true)
    public List<Cupon> catalogo() {
        return jdbc.query("""
                SELECT c.*, u.nombre AS registrado_por_nombre, %s AS estado,
                       (SELECT count(*) FROM cupon_cliente cc WHERE cc.cupon_id = c.id) AS canjes,
                       (SELECT count(*) FROM cupon_cliente cc JOIN pedido pe ON pe.cupon_cliente_id = cc.id
                        WHERE cc.cupon_id = c.id AND pe.estado NOT IN ('CANCELADO', 'PENDIENTE_PAGO')) AS usados,
                       (SELECT coalesce(sum(pe.descuento_monto), 0) FROM cupon_cliente cc JOIN pedido pe ON pe.cupon_cliente_id = cc.id
                        WHERE cc.cupon_id = c.id AND pe.estado NOT IN ('CANCELADO', 'PENDIENTE_PAGO')) AS descuento
                FROM cupon c JOIN usuario u ON u.id = c.registrado_por
                ORDER BY c.disponible_desde DESC, c.id DESC""".formatted(ESTADO_CATALOGO), new MapSqlParameterSource(),
                (rs, row) -> new Cupon(
                rs.getLong("id"), rs.getString("nombre"), rs.getString("descripcion"), rs.getInt("costo_puntos"),
                Tipo.valueOf(rs.getString("tipo")), rs.getBigDecimal("valor"), rs.getBigDecimal("monto_minimo"),
                rs.getBigDecimal("tope_descuento"), rs.getInt("vigencia_dias"), rs.getBoolean("activo"), rs.getInt("canjes"),
                rs.getInt("usados"), rs.getBigDecimal("descuento"), rs.getString("registrado_por_nombre"),
                CampanasService.instante(rs, "registrado_en"), rs.getObject("disponible_desde", LocalDate.class),
                rs.getObject("disponible_hasta", LocalDate.class), EstadoCatalogo.valueOf(rs.getString("estado"))));
    }

    @Transactional
    public List<Cupon> crear(long usuarioId, NuevoCupon nuevo) {
        validar(nuevo.beneficio());
        LocalDate hoy = hoy();
        LocalDate desde = nuevo.disponibleDesde() == null ? hoy : nuevo.disponibleDesde();
        if (desde.isBefore(hoy)) {
            throw inicioPasado();
        }
        validarPeriodo(desde, nuevo.disponibleHasta());
        jdbc.update("""
                INSERT INTO cupon (nombre, descripcion, costo_puntos, tipo, valor, monto_minimo, tope_descuento, vigencia_dias,
                                   disponible_desde, disponible_hasta, registrado_por)
                VALUES (:nombre, :descripcion, :costo, :tipo, :valor, :minimo, :tope, :vigencia, :desde, :hasta, :usuario)""",
                parametros(nuevo).addValue("desde", desde).addValue("usuario", usuarioId));
        return catalogo();
    }

    /** Cambia un cupon del catalogo. Los ya canjeados conservan el beneficio con que se canjearon. */
    @Transactional
    public List<Cupon> editar(long usuarioId, long id, NuevoCupon cambios) {
        validar(cambios.beneficio());
        LocalDate actual = jdbc.query("SELECT disponible_desde FROM cupon WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, row) -> rs.getObject(1, LocalDate.class)).stream().findFirst().orElse(null);
        exigirUno(actual == null ? 0 : 1);
        // Un cupon que ya empezo conserva su inicio; uno que aun no empieza no se mueve al pasado
        LocalDate desde = cambios.disponibleDesde() == null ? actual : cambios.disponibleDesde();
        if (!desde.equals(actual) && desde.isBefore(hoy())) {
            throw inicioPasado();
        }
        validarPeriodo(desde, cambios.disponibleHasta());
        jdbc.update("""
                UPDATE cupon SET nombre = :nombre, descripcion = :descripcion, costo_puntos = :costo, tipo = :tipo,
                       valor = :valor, monto_minimo = :minimo, tope_descuento = :tope, vigencia_dias = :vigencia,
                       disponible_desde = :desde, disponible_hasta = :hasta,
                       actualizado_por = :usuario, actualizado_en = clock_timestamp()
                WHERE id = :id""", parametros(cambios).addValue("desde", desde).addValue("usuario", usuarioId).addValue("id", id));
        return catalogo();
    }

    /** Muestra u oculta un cupon del catalogo. Los ya canjeados se siguen pudiendo usar. */
    @Transactional
    public List<Cupon> activar(long usuarioId, long id, boolean activo) {
        MapSqlParameterSource p = new MapSqlParameterSource("activo", activo).addValue("usuario", usuarioId).addValue("id", id);
        Boolean actual = jdbc.query("SELECT activo FROM cupon WHERE id = :id FOR UPDATE", p, (rs, row) -> rs.getBoolean(1))
                .stream().findFirst().orElse(null);
        exigirUno(actual == null ? 0 : 1);
        if (actual != activo) {
            jdbc.update("""
                    UPDATE cupon SET activo = :activo, actualizado_por = :usuario, actualizado_en = clock_timestamp()
                    WHERE id = :id""", p);
        }
        return catalogo();
    }

    private static ErrorNegocio inicioPasado() {
        return ErrorNegocio.invalido("CUPON_INICIO_PASADO", "El cupón está disponible desde hoy o una fecha futura.");
    }

    private static void validarPeriodo(LocalDate desde, LocalDate hasta) {
        if (hasta != null && hasta.isBefore(desde)) {
            throw ErrorNegocio.invalido("CUPON_FIN_ANTES_DE_INICIO", "El cupón termina el mismo día que empieza o después.");
        }
    }

    private LocalDate hoy() {
        return jdbc.queryForObject("SELECT " + HOY, new MapSqlParameterSource(), LocalDate.class);
    }

    /** Reglas del descuento que la validacion de campos no cubre: un porcentaje hasta 90 y con tope solo los porcentajes. */
    static void validar(NuevoBeneficio b) {
        if (b.tipo() == Tipo.PORCENTAJE && b.valor().compareTo(BigDecimal.valueOf(90)) > 0) {
            throw ErrorNegocio.invalido("PORCENTAJE_MUY_ALTO", "El porcentaje es como máximo 90 %.");
        }
        if (b.tipo() == Tipo.MONTO && b.topeDescuento() != null) {
            throw ErrorNegocio.invalido("TOPE_SOLO_PORCENTAJE", "El tope solo aplica a los descuentos en porcentaje.");
        }
    }

    // --- El comprador ---

    /** CCPIP-173, 374, 88 y 89: saldo, nivel, movimientos, cupones para canjear y los ya canjeados. */
    @Transactional(readOnly = true)
    public MisPuntos misPuntos(long usuarioId) {
        long saldo = saldo(usuarioId);
        List<Nivel> vigentes = niveles.niveles();
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId);
        List<Movimiento> movimientos = jdbc.query("""
                SELECT m.tipo, m.puntos, m.registrado_en, coalesce(pe.numero, cc.nombre) AS referencia, c.nombre AS campana,
                       m.nivel
                FROM movimiento_puntos m
                LEFT JOIN pedido pe ON pe.id = m.pedido_id
                LEFT JOIN cupon_cliente cc ON cc.id = m.cupon_cliente_id
                LEFT JOIN campana_puntos c ON c.id = m.campana_id
                WHERE m.usuario_id = :usuario
                ORDER BY m.registrado_en DESC, m.id DESC LIMIT 100""", p, (rs, row) -> new Movimiento(
                TipoMovimiento.valueOf(rs.getString("tipo")), rs.getInt("puntos"), CampanasService.instante(rs, "registrado_en"),
                rs.getString("referencia"), rs.getString("campana"), rs.getString("nivel")));
        List<CuponCanjeable> catalogo = jdbc.query("""
                SELECT * FROM cupon c WHERE %s ORDER BY costo_puntos, id""".formatted(CANJEABLE), p, (rs, row) -> new CuponCanjeable(
                rs.getLong("id"), rs.getString("nombre"), rs.getString("descripcion"), rs.getInt("costo_puntos"),
                Tipo.valueOf(rs.getString("tipo")), rs.getBigDecimal("valor"), rs.getBigDecimal("monto_minimo"),
                rs.getBigDecimal("tope_descuento"), rs.getInt("vigencia_dias"), rs.getInt("costo_puntos") <= saldo));
        return new MisPuntos(saldo, niveles.delCliente(usuarioId, vigentes), vigentes, movimientos, catalogo,
                misCupones(usuarioId));
    }

    /** CCPIP-89: canjea los puntos por un cupon del catalogo. Devuelve el modulo de puntos actualizado. */
    @Transactional
    public MisPuntos canjear(long usuarioId, long cuponId) {
        // Serializa los canjes del comprador: el saldo que se revisa es el que se gasta
        jdbc.query("SELECT id FROM usuario WHERE id = :usuario FOR UPDATE", new MapSqlParameterSource("usuario", usuarioId),
                rs -> null);
        record Catalogo(String nombre, int costo, int vigencia) {
        }
        Catalogo cupon = jdbc.query("SELECT nombre, costo_puntos, vigencia_dias FROM cupon c WHERE id = :id AND " + CANJEABLE + " FOR UPDATE",
                        new MapSqlParameterSource("id", cuponId),
                        (rs, row) -> new Catalogo(rs.getString("nombre"), rs.getInt("costo_puntos"), rs.getInt("vigencia_dias")))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("CUPON_NO_DISPONIBLE", "Ese cupón ya no está disponible."));
        long saldo = saldo(usuarioId);
        if (saldo < cupon.costo()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "PUNTOS_INSUFICIENTES",
                    "Te faltan " + (cupon.costo() - saldo) + " puntos para canjear este cupón.");
        }
        Long canjeado = jdbc.queryForObject("""
                INSERT INTO cupon_cliente (cupon_id, usuario_id, codigo, nombre, tipo, valor, monto_minimo, tope_descuento,
                                           costo_puntos, vence_en)
                SELECT id, :usuario, :codigo, nombre, tipo, valor, monto_minimo, tope_descuento, costo_puntos,
                       now() + make_interval(days => vigencia_dias)
                FROM cupon WHERE id = :id
                RETURNING id""", new MapSqlParameterSource("usuario", usuarioId).addValue("id", cuponId)
                .addValue("codigo", codigoNuevo()), Long.class);
        jdbc.update("""
                INSERT INTO movimiento_puntos (usuario_id, tipo, puntos, cupon_cliente_id)
                VALUES (:usuario, 'CANJE', :puntos, :cupon)""", new MapSqlParameterSource("usuario", usuarioId)
                .addValue("puntos", cupon.costo()).addValue("cupon", canjeado));
        return misPuntos(usuarioId);
    }

    /** Lo acumulado menos lo canjeado. */
    long saldo(long usuarioId) {
        Long saldo = jdbc.queryForObject("""
                SELECT coalesce(sum(CASE WHEN tipo = 'ACUMULACION' THEN puntos ELSE -puntos END), 0)
                FROM movimiento_puntos WHERE usuario_id = :usuario""", new MapSqlParameterSource("usuario", usuarioId), Long.class);
        return saldo == null ? 0 : saldo;
    }

    /** Los cupones que canjeo el comprador, los disponibles primero. */
    private List<MiCupon> misCupones(long usuarioId) {
        return jdbc.query("""
                SELECT cc.*, pe.numero AS pedido, %s AS estado
                FROM cupon_cliente cc %s
                WHERE cc.usuario_id = :usuario
                ORDER BY CASE %s WHEN 'DISPONIBLE' THEN 0 WHEN 'EN_USO' THEN 1 ELSE 2 END, cc.vence_en, cc.id"""
                        .formatted(ESTADO_CUPON, PEDIDO_DEL_CUPON, ESTADO_CUPON),
                new MapSqlParameterSource("usuario", usuarioId), (rs, row) -> new MiCupon(rs.getLong("id"),
                        rs.getString("codigo"), rs.getString("nombre"), Tipo.valueOf(rs.getString("tipo")),
                        rs.getBigDecimal("valor"), rs.getBigDecimal("monto_minimo"), rs.getBigDecimal("tope_descuento"),
                        CampanasService.instante(rs, "canjeado_en"), CampanasService.instante(rs, "vence_en"), EstadoCupon.valueOf(rs.getString("estado")),
                        rs.getString("pedido")));
    }

    private static MapSqlParameterSource parametros(NuevoCupon c) {
        NuevoBeneficio b = c.beneficio();
        String descripcion = c.descripcion() == null || c.descripcion().isBlank() ? null : c.descripcion().strip();
        return new MapSqlParameterSource("nombre", c.nombre().strip()).addValue("descripcion", descripcion)
                .addValue("costo", c.costoPuntos()).addValue("tipo", b.tipo().name()).addValue("valor", b.valor())
                .addValue("minimo", b.montoMinimo() == null ? BigDecimal.ZERO : b.montoMinimo())
                .addValue("tope", b.topeDescuento()).addValue("vigencia", c.vigenciaDias()).addValue("hasta", c.disponibleHasta());
    }

    /** Un codigo de 10 caracteres sin letras que se confunden (O/0, I/1). */
    private static String codigoNuevo() {
        StringBuilder codigo = new StringBuilder("PTS");
        for (int i = 0; i < 7; i++) {
            codigo.append(CARACTERES.charAt(AZAR.nextInt(CARACTERES.length())));
        }
        return codigo.toString();
    }

    private static void exigirUno(int filas) {
        if (filas == 0) {
            throw ErrorNegocio.noEncontrado("CUPON_NO_ENCONTRADO", "No encontramos ese cupón.");
        }
    }
}
