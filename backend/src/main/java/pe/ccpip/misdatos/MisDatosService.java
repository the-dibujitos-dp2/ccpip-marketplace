package pe.ccpip.misdatos;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CW-13 "Mis datos": CCPIP-400 (RF-292) el consumidor consulta sus datos personales y de contacto, y
 * CCPIP-113 (RF-09) los edita. Siempre los de la sesion, nunca por id. Sin genero ni fecha de
 * nacimiento. El correo es el usuario para ingresar: se muestra pero aqui no se cambia.
 *
 * <p>Los pedidos guardan su propio contacto (celular y persona autorizada), asi que editar estos datos
 * no cambia ningun pedido ya hecho.
 */
@Service
public class MisDatosService {

    /** {@code nombres} trae el nombre completo en una cuenta anterior a CCPIP-113 (sin apellidos guardados). */
    public record MisDatos(String nombres, String apellidos, String correo, boolean correoConfirmado, String celular) {
    }

    private final JdbcTemplate jdbc;

    public MisDatosService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public MisDatos consultar(long usuarioId) {
        return jdbc.query("""
                SELECT coalesce(nombres, nombre) AS nombres, coalesce(apellidos, '') AS apellidos, correo,
                       correo_confirmado, telefono
                FROM usuario WHERE id = ?""",
                (rs, row) -> new MisDatos(rs.getString("nombres"), rs.getString("apellidos"), rs.getString("correo"),
                        rs.getBoolean("correo_confirmado"), rs.getString("telefono")), usuarioId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("CUENTA_NO_ENCONTRADA", "La cuenta no existe."));
    }

    /** Guarda nombres, apellidos y celular; el nombre completo de la cuenta se actualiza con ellos. */
    @Transactional
    public MisDatos editar(long usuarioId, String nombres, String apellidos, String celular) {
        String n = espacios(nombres);
        String a = espacios(apellidos);
        String c = celular == null || celular.isBlank() ? null : espacios(celular);
        jdbc.update("""
                UPDATE usuario SET nombres = ?, apellidos = ?, nombre = ?, telefono = ?, actualizado_en = now()
                WHERE id = ?""", n, a, (n + " " + a).trim(), c, usuarioId);
        return consultar(usuarioId);
    }

    private static String espacios(String texto) {
        return texto.trim().replaceAll("\\s+", " ");
    }
}
