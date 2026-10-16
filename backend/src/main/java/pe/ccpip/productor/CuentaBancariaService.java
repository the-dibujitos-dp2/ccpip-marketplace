package pe.ccpip.productor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.entrega.PersonaDeRecojo;
import pe.ccpip.productor.CuentaBancariaModelos.Cuenta;
import pe.ccpip.productor.CuentaBancariaModelos.Datos;
import pe.ccpip.productor.CuentaBancariaModelos.Estado;
import pe.ccpip.productor.CuentaBancariaModelos.Moneda;
import pe.ccpip.productor.CuentaBancariaModelos.Motivo;
import pe.ccpip.productor.CuentaBancariaModelos.TipoCuenta;

/**
 * La cuenta donde la Camara liquida al productor. Vender exige una cuenta ACTIVA (RF-142): al
 * desactivarla, sus ofertas siguen visibles en "Venta pausada" y no se pueden comprar; los pedidos
 * ya pagados siguen su curso (supuesto anotado en PW-20, por validar con el stakeholder).
 * Un productor tiene una sola cuenta: se registra una vez y luego se actualiza, desactiva o reactiva.
 */
@Service
public class CuentaBancariaService {

    private static final Pattern NO_DIGITOS = Pattern.compile("\\D");

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public CuentaBancariaService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    /** CCPIP-399: la cuenta del productor, o 404 si aun no registra una. */
    @Transactional(readOnly = true)
    public Cuenta consultar(long productorId) {
        return buscar(productorId).orElseThrow(() -> ErrorNegocio.noEncontrado("CUENTA_NO_REGISTRADA",
                "Aún no registras tu cuenta bancaria. Regístrala para poder vender."));
    }

    /** CCPIP-32: registra la cuenta, activa desde ya. Solo una por productor. */
    @Transactional
    public Cuenta registrar(long productorId, Datos datos) {
        if (buscar(productorId).isPresent()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CUENTA_YA_REGISTRADA",
                    "Ya registraste tu cuenta bancaria. Puedes actualizar sus datos.");
        }
        MapSqlParameterSource p = parametros(productorId, datos);
        jdbc.update("""
                INSERT INTO cuenta_bancaria (productor_id, titular, documento_titular, banco, tipo_cuenta, numero_cuenta,
                                             cci, moneda, correo_avisos, yape_celular)
                VALUES (:productor, :titular, :documento, :banco, :tipo, :numero, :cci, :moneda, :correo, :yape)""", p);
        return consultar(productorId);
    }

    /** CCPIP-274: actualiza los datos de la cuenta activa. Una desactivada se corrige al reactivarla. */
    @Transactional
    public Cuenta actualizar(long productorId, Datos datos) {
        Cuenta actual = consultar(productorId);
        if (actual.estado() != Estado.ACTIVA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CUENTA_DESACTIVADA",
                    "Tu cuenta está desactivada. Revisa sus datos y reactívala.");
        }
        guardar(actual.id(), productorId, datos, "estado");
        return consultar(productorId);
    }

    /**
     * CCPIP-275: desactiva temporalmente la cuenta con un motivo y la confirmacion expresa (validada
     * en la peticion). La tienda y los productos siguen visibles; las ventas nuevas se pausan.
     */
    @Transactional
    public Cuenta desactivar(long productorId, Motivo motivo) {
        Cuenta actual = consultar(productorId);
        if (actual.estado() != Estado.ACTIVA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CUENTA_DESACTIVADA", "Tu cuenta ya está desactivada.");
        }
        jdbc.update("""
                UPDATE cuenta_bancaria SET estado = 'DESACTIVADA', motivo_desactivacion = :motivo,
                       desactivada_en = now(), actualizado_en = now()
                WHERE id = :id AND productor_id = :productor""",
                new MapSqlParameterSource("id", actual.id()).addValue("productor", productorId).addValue("motivo", motivo.name()));
        return consultar(productorId);
    }

    /**
     * CCPIP-276: reactiva la cuenta despues de validar otra vez sus datos y la contrasena del
     * productor. Al quedar ACTIVA, sus ofertas vuelven a poder comprarse.
     */
    @Transactional
    public Cuenta reactivar(long productorId, long usuarioId, Datos datos, String contrasena) {
        Cuenta actual = consultar(productorId);
        if (actual.estado() != Estado.DESACTIVADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CUENTA_ACTIVA", "Tu cuenta ya está activa.");
        }
        String hash = jdbc.queryForObject("SELECT contrasena_hash FROM usuario WHERE id = :id",
                new MapSqlParameterSource("id", usuarioId), String.class);
        if (hash == null || !passwordEncoder.matches(contrasena, hash)) {
            throw ErrorNegocio.invalido("CONTRASENA_INCORRECTA", "La contraseña no es correcta.");
        }
        guardar(actual.id(), productorId, datos, "'ACTIVA'");
        return consultar(productorId);
    }

    /** Escribe los datos validados. {@code estado}: la expresion SQL del estado que queda. */
    private void guardar(long cuentaId, long productorId, Datos datos, String estado) {
        MapSqlParameterSource p = parametros(productorId, datos).addValue("id", cuentaId);
        jdbc.update("""
                UPDATE cuenta_bancaria SET titular = :titular, documento_titular = :documento, banco = :banco,
                       tipo_cuenta = :tipo, numero_cuenta = :numero, cci = :cci, moneda = :moneda,
                       correo_avisos = :correo, yape_celular = :yape, estado = %1$s,
                       motivo_desactivacion = CASE WHEN %1$s = 'ACTIVA' THEN NULL ELSE motivo_desactivacion END,
                       desactivada_en = CASE WHEN %1$s = 'ACTIVA' THEN NULL ELSE desactivada_en END,
                       actualizado_en = now()
                WHERE id = :id AND productor_id = :productor""".formatted(estado), p);
    }

    /** Valida lo que las anotaciones no pueden (banco de la lista, digitos del CCI, celular) y normaliza. */
    private static MapSqlParameterSource parametros(long productorId, Datos d) {
        if (!CuentaBancariaModelos.BANCOS.contains(d.banco())) {
            throw ErrorNegocio.invalido("BANCO_INVALIDO", "Elige un banco de la lista.");
        }
        String cci = NO_DIGITOS.matcher(d.cci()).replaceAll("");
        if (cci.length() != 20) {
            throw ErrorNegocio.invalido("CCI_INVALIDO", "El CCI tiene 20 dígitos.");
        }
        String numero = d.numeroCuenta().trim();
        int digitos = NO_DIGITOS.matcher(numero).replaceAll("").length();
        if (digitos < 8 || digitos > 20) {
            throw ErrorNegocio.invalido("NUMERO_CUENTA_INVALIDO", "El número de cuenta tiene entre 8 y 20 dígitos.");
        }
        String yape = null;
        if (d.yapeCelular() != null && !d.yapeCelular().isBlank()) {
            yape = PersonaDeRecojo.celular(d.yapeCelular()).orElseThrow(() ->
                    ErrorNegocio.invalido("YAPE_INVALIDO", "El celular de Yape tiene 9 dígitos y empieza con 9."));
        }
        String correo = d.correoAvisos() == null || d.correoAvisos().isBlank() ? null : d.correoAvisos().trim();
        return new MapSqlParameterSource("productor", productorId)
                .addValue("titular", d.titular().trim())
                .addValue("documento", d.documento().trim())
                .addValue("banco", d.banco())
                .addValue("tipo", d.tipoCuenta().name())
                .addValue("numero", numero)
                .addValue("cci", cci)
                .addValue("moneda", d.moneda().name())
                .addValue("correo", correo)
                .addValue("yape", yape);
    }

    /** La cuenta vigente del productor: la activa o, si no hay, la ultima desactivada. */
    private Optional<Cuenta> buscar(long productorId) {
        return jdbc.query("""
                SELECT id, estado, titular, documento_titular, banco, tipo_cuenta, numero_cuenta, cci, moneda,
                       correo_avisos, yape_celular, motivo_desactivacion, desactivada_en, actualizado_en
                FROM cuenta_bancaria WHERE productor_id = :productor
                ORDER BY (estado = 'ACTIVA') DESC, actualizado_en DESC, id DESC LIMIT 1""",
                new MapSqlParameterSource("productor", productorId), (rs, row) -> cuenta(rs)).stream().findFirst();
    }

    private static Cuenta cuenta(ResultSet rs) throws SQLException {
        String motivo = rs.getString("motivo_desactivacion");
        return new Cuenta(rs.getLong("id"), Estado.valueOf(rs.getString("estado")), rs.getString("titular"),
                rs.getString("documento_titular"), rs.getString("banco"), TipoCuenta.valueOf(rs.getString("tipo_cuenta")),
                rs.getString("numero_cuenta"), rs.getString("cci"), Moneda.valueOf(rs.getString("moneda").trim()),
                rs.getString("correo_avisos"), rs.getString("yape_celular"),
                motivo == null ? null : Motivo.valueOf(motivo), instante(rs, "desactivada_en"), instante(rs, "actualizado_en"));
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
