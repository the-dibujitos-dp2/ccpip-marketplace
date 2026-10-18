package pe.ccpip.auth.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cuentas nuevas y enlaces de un solo uso (CCPIP-26, CCPIP-328). Va aparte de {@link UserStore},
 * que solo lee cuentas para el inicio de sesion.
 */
@Repository
public class RegistroStore {

    /** Valores del dominio "proposito de enlace" del diccionario de datos. */
    public enum Proposito { VERIFICAR_CORREO, RECUPERAR_CLAVE }

    /** Un enlace con lo necesario para decidir si todavia sirve. */
    public record Enlace(long id, long usuarioId, String correo, Instant expiraEn, Instant usadoEn) {
    }

    /** Cuenta buscada por correo para reenviar el enlace. */
    public record Cuenta(long id, String correo, String nombre, boolean activa, boolean correoConfirmado) {
    }

    private final JdbcTemplate jdbc;

    public RegistroStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Crea la cuenta de consumidor sin confirmar. Si el correo ya existe, la base lo rechaza con
     * {@link org.springframework.dao.DuplicateKeyException} (indice usuario_correo_lower_idx).
     */
    public long crearConsumidor(String correo, String contrasenaHash, String nombres, String apellidos, String telefono) {
        // CCPIP-113: nombres y apellidos por separado para Mis datos; "nombre" es el completo
        return jdbc.queryForObject("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, nombres, apellidos, telefono, rol_id, correo_confirmado)
                SELECT ?, ?, ?, ?, ?, ?, id, false FROM rol WHERE codigo = 'CONSUMIDOR'
                RETURNING id""", Long.class, correo, contrasenaHash, (nombres + " " + apellidos).trim(), nombres, apellidos,
                telefono);
    }

    public Optional<Cuenta> cuentaPorCorreo(String correo) {
        return jdbc.query("""
                SELECT id, correo, nombre, activo, correo_confirmado FROM usuario
                WHERE lower(correo) = lower(?)""",
                (rs, row) -> new Cuenta(rs.getLong("id"), rs.getString("correo"), rs.getString("nombre"),
                        rs.getBoolean("activo"), rs.getBoolean("correo_confirmado")),
                correo).stream().findFirst();
    }

    public void guardarEnlace(long usuarioId, Proposito proposito, String tokenHash, Instant creado, Instant expira) {
        jdbc.update("""
                INSERT INTO token_un_uso (usuario_id, proposito, token_hash, creado_en, expira_en)
                VALUES (?, ?, ?, ?, ?)""",
                usuarioId, proposito.name(), tokenHash, utc(creado), utc(expira));
    }

    public Optional<Enlace> buscarEnlace(String tokenHash, Proposito proposito) {
        return jdbc.query("""
                SELECT t.id, t.usuario_id, u.correo, t.expira_en, t.usado_en
                FROM token_un_uso t
                JOIN usuario u ON u.id = t.usuario_id
                WHERE t.token_hash = ? AND t.proposito = ?""",
                RegistroStore::enlace, tokenHash, proposito.name()).stream().findFirst();
    }

    /** Marca el enlace como usado solo si nadie lo uso antes; devuelve false si perdio la carrera. */
    public boolean marcarUsado(long enlaceId, Instant ahora) {
        return jdbc.update("UPDATE token_un_uso SET usado_en = ? WHERE id = ? AND usado_en IS NULL",
                utc(ahora), enlaceId) == 1;
    }

    public void confirmarCorreo(long usuarioId) {
        jdbc.update("UPDATE usuario SET correo_confirmado = true, actualizado_en = now() WHERE id = ?", usuarioId);
    }

    /** Fecha del enlace mas reciente, para no reenviar varias veces en pocos segundos. */
    public Optional<Instant> ultimoEnlace(long usuarioId, Proposito proposito) {
        return jdbc.query("""
                SELECT max(creado_en) AS ultimo FROM token_un_uso WHERE usuario_id = ? AND proposito = ?""",
                (rs, row) -> instant(rs, "ultimo"), usuarioId, proposito.name())
                .stream().filter(Objects::nonNull).findFirst();
    }

    /** Al emitir un enlace nuevo, los anteriores sin usar dejan de servir. */
    public void descartarEnlacesSinUsar(long usuarioId, Proposito proposito) {
        jdbc.update("DELETE FROM token_un_uso WHERE usuario_id = ? AND proposito = ? AND usado_en IS NULL",
                usuarioId, proposito.name());
    }

    private static Enlace enlace(ResultSet rs, int row) throws SQLException {
        return new Enlace(rs.getLong("id"), rs.getLong("usuario_id"), rs.getString("correo"),
                instant(rs, "expira_en"), instant(rs, "usado_en"));
    }

    private static Instant instant(ResultSet rs, String columna) throws SQLException {
        OffsetDateTime valor = rs.getObject(columna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
