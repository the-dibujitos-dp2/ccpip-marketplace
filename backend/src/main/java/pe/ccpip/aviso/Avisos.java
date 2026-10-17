package pe.ccpip.aviso;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

/**
 * CCPIP-69 y CCPIP-273: registro de avisos a una persona. Cada hecho (un pedido listo, una oferta que
 * vuelve a venderse) se avisa una sola vez por canal: la tabla {@code aviso} tiene clave unica. El canal
 * es el correo mientras CCPIP-103 no defina otro.
 */
@Service
public class Avisos {

    private static final Logger LOG = LoggerFactory.getLogger(Avisos.class);
    private static final int MAX_ERROR = 300;

    public enum Tipo { PEDIDO_LISTO, INTERES_VENDIBLE }

    private final NamedParameterJdbcTemplate jdbc;
    private final EnvioCorreo envio;

    public Avisos(NamedParameterJdbcTemplate jdbc, EnvioCorreo envio) {
        this.jdbc = jdbc;
        this.envio = envio;
    }

    /**
     * Registra el aviso si no existia. Devuelve su id, o vacio si ese hecho ya se aviso (o se esta
     * avisando) a esa persona: quien llama no debe enviar nada.
     */
    public Optional<Long> registrar(long usuarioId, Tipo tipo, long referenciaId) {
        return jdbc.query("""
                INSERT INTO aviso (usuario_id, tipo, referencia_id) VALUES (:usuario, :tipo, :referencia)
                ON CONFLICT ON CONSTRAINT aviso_sin_duplicar DO NOTHING
                RETURNING id""", new MapSqlParameterSource("usuario", usuarioId).addValue("tipo", tipo.name())
                .addValue("referencia", referenciaId), (rs, row) -> rs.getLong("id")).stream().findFirst();
    }

    /**
     * Envia el correo de un aviso ya registrado y deja el resultado. Un servidor de correo caido no
     * detiene la operacion que origino el aviso: queda FALLIDO, con el motivo.
     */
    public boolean enviar(long avisoId, Correo correo) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", avisoId);
        try {
            envio.enviar(correo);
            jdbc.update("UPDATE aviso SET estado = 'ENVIADO', enviado_en = now(), error = NULL WHERE id = :id", p);
            return true;
        } catch (MailException e) {
            LOG.error("No se pudo enviar el aviso {}: {}", avisoId, e.getMessage());
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update("UPDATE aviso SET estado = 'FALLIDO', error = :error WHERE id = :id",
                    p.addValue("error", error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error));
            return false;
        }
    }

    /** El idioma de los correos de la persona: el que eligio en la plataforma (CCPIP-265). */
    public static boolean ingles(String idioma) {
        return "en".equalsIgnoreCase(idioma);
    }
}
