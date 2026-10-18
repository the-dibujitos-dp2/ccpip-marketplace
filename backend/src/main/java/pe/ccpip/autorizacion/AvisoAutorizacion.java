package pe.ccpip.autorizacion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

import pe.ccpip.aviso.Correo;
import pe.ccpip.aviso.EnvioCorreo;

/**
 * Notifica al productor cada decision humana sobre su venta (CCPIP-37, CCPIP-297, CCPIP-39,
 * CCPIP-291, CCPIP-330). El canal es el acordado el 30/09 (Figma, CCPIP-103): correo, y el aviso en
 * la plataforma, que son el estado y el motivo que el productor ve en sus pantallas.
 *
 * <p>Se llama despues de que la decision quedo guardada: si el correo falla, la decision no se deshace
 * y queda el aviso en la plataforma.
 */
@Component
public class AvisoAutorizacion {

    private static final Logger LOG = LoggerFactory.getLogger(AvisoAutorizacion.class);
    private static final String FIRMA_ES = "\nCámara de Comercio de los Pueblos Indígenas del Perú\n";
    private static final String FIRMA_EN = "\nChamber of Commerce of the Indigenous Peoples of Peru\n";

    /** Que se decidio. {@code producto} es nulo en las decisiones sobre el productor. */
    public enum Tipo { APROBADA, RECHAZADA, REVOCADA, HABILITAR_TIENDA, REVOCAR_VENTA, RESTITUIR_VENTA }

    private record Destinatario(String correo, String nombre, boolean ingles) {
    }

    private final JdbcTemplate jdbc;
    private final EnvioCorreo envio;

    public AvisoAutorizacion(JdbcTemplate jdbc, EnvioCorreo envio) {
        this.jdbc = jdbc;
        this.envio = envio;
    }

    /** Envia el aviso al usuario del productor, en su idioma. Devuelve false si no salio. */
    public boolean notificar(long productorId, Tipo tipo, String producto, String motivo) {
        Destinatario d = jdbc.query("""
                        SELECT u.correo, coalesce(p.nombre_tienda, p.comunidad), u.idioma
                        FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE p.id = ?""",
                (rs, row) -> new Destinatario(rs.getString(1), rs.getString(2), "en".equals(rs.getString(3))),
                productorId).stream().findFirst().orElse(null);
        if (d == null) {
            return false;
        }
        Correo correo = d.ingles() ? enIngles(d, tipo, producto, motivo) : enEspanol(d, tipo, producto, motivo);
        try {
            envio.enviar(correo);
            return true;
        } catch (MailException e) {
            LOG.error("No se pudo avisar la decision {} al productor {}: {}", tipo, productorId, e.getMessage());
            return false;
        }
    }

    private static Correo enEspanol(Destinatario d, Tipo tipo, String producto, String motivo) {
        String asunto = switch (tipo) {
            case APROBADA -> "Autorizamos la venta de " + producto;
            case RECHAZADA -> "No autorizamos la venta de " + producto;
            case REVOCADA -> "Revocamos la autorización de venta de " + producto;
            case HABILITAR_TIENDA -> "Tu tienda en Mercado CCPIP está habilitada";
            case REVOCAR_VENTA -> "Revocamos la autorización de venta de tu organización";
            case RESTITUIR_VENTA -> "Restituimos la autorización de venta de tu organización";
        };
        String detalle = switch (tipo) {
            case APROBADA -> "Ya puedes vender este producto en Mercado CCPIP.";
            case RECHAZADA -> "Puedes adjuntar nuevas evidencias desde Mis productos para una nueva revisión.";
            case REVOCADA -> "Desde ahora no se puede comprar. Los pedidos ya confirmados se atienden con normalidad. "
                    + "Puedes adjuntar nuevas evidencias para una nueva revisión.";
            case HABILITAR_TIENDA -> "Ya puedes publicar tu tienda desde Mi tienda.";
            case REVOCAR_VENTA -> "Desde ahora no se pueden comprar tus productos. Los pedidos ya confirmados se "
                    + "atienden con normalidad.";
            case RESTITUIR_VENTA -> "Tus productos autorizados vuelven a estar a la venta.";
        };
        return new Correo(d.correo(), asunto + " · Mercado CCPIP", """
                Hola, %s:

                %s

                Motivo: %s

                %s
                """.formatted(d.nombre(), detalle, motivo, FIRMA_ES));
    }

    private static Correo enIngles(Destinatario d, Tipo tipo, String producto, String motivo) {
        String asunto = switch (tipo) {
            case APROBADA -> "We authorized the sale of " + producto;
            case RECHAZADA -> "We did not authorize the sale of " + producto;
            case REVOCADA -> "We revoked the sale authorization of " + producto;
            case HABILITAR_TIENDA -> "Your Mercado CCPIP store is enabled";
            case REVOCAR_VENTA -> "We revoked your organization's sale authorization";
            case RESTITUIR_VENTA -> "We restored your organization's sale authorization";
        };
        String detalle = switch (tipo) {
            case APROBADA -> "You can now sell this product on Mercado CCPIP.";
            case RECHAZADA -> "You can attach new evidence from My products for a new review.";
            case REVOCADA -> "It can no longer be purchased. Confirmed orders are fulfilled as usual. "
                    + "You can attach new evidence for a new review.";
            case HABILITAR_TIENDA -> "You can now publish your store from My store.";
            case REVOCAR_VENTA -> "Your products can no longer be purchased. Confirmed orders are fulfilled as usual.";
            case RESTITUIR_VENTA -> "Your authorized products are on sale again.";
        };
        // El motivo lo escribe el subadministrador en espanol: se envia tal cual
        return new Correo(d.correo(), asunto + " · Mercado CCPIP", """
                Hello, %s:

                %s

                Reason: %s

                %s
                """.formatted(d.nombre(), detalle, motivo, FIRMA_EN));
    }
}
