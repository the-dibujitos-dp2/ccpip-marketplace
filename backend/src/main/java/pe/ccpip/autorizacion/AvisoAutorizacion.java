package pe.ccpip.autorizacion;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import pe.ccpip.aviso.Avisos;
import pe.ccpip.aviso.Correo;

/**
 * Notifica al productor cada decision humana sobre su venta (CCPIP-37, CCPIP-297, CCPIP-39,
 * CCPIP-291, CCPIP-330, CCPIP-85, CCPIP-322) y sobre la publicacion de sus ofertas (CCPIP-331, CCPIP-332). El canal es el acordado el 30/09 (Figma, CCPIP-103): correo, y el aviso en
 * la plataforma, que son el estado y el motivo que el productor ve en sus pantallas.
 *
 * <p>Se llama despues de que la decision quedo guardada: si el correo falla, la decision no se deshace
 * y queda el aviso en la plataforma.
 */
@Component
public class AvisoAutorizacion {

    /**
     * Que se decidio. {@code producto} es nulo en las decisiones sobre el productor. Las dos ultimas son
     * las variantes de restituir y desbloquear cuando la otra restriccion sigue vigente: el aviso no
     * promete que los productos vuelven a la venta. PUBLICACION_*: la moderacion de una oferta.
     */
    public enum Tipo { APROBADA, RECHAZADA, REVOCADA, HABILITAR_TIENDA, REVOCAR_VENTA, RESTITUIR_VENTA, BLOQUEAR_VENTA, DESBLOQUEAR_VENTA,
        RESTITUIR_VENTA_SIGUE_BLOQUEADA, DESBLOQUEAR_VENTA_SIGUE_REVOCADA, PUBLICACION_APROBADA,
        PUBLICACION_RECHAZADA }

    private record Destinatario(long usuarioId, String correo, String nombre, boolean ingles) {
    }

    /** Asunto (con %s para el producto, si lo hay) y detalle de un aviso en un idioma. */
    private record Texto(String asunto, String detalle) {
    }

    /** Lo comun de un idioma: saludo, etiqueta del motivo, firma y los textos de cada decision. */
    private record Idioma(String saludo, String motivo, String firma, Map<Tipo, Texto> textos) {
    }

    private static final Idioma ES = new Idioma("Hola", "Motivo",
            "Cámara de Comercio de los Pueblos Indígenas del Perú", textos(
                    "Autorizamos la venta de %s", "Ya puedes vender este producto en Mercado CCPIP.",
                    "No autorizamos la venta de %s", "Puedes adjuntar nuevas evidencias desde Mis productos para una nueva revisión.",
                    "Revocamos la autorización de venta de %s", "Desde ahora no se puede comprar. Los pedidos ya confirmados "
                            + "se atienden con normalidad. Puedes adjuntar nuevas evidencias para una nueva revisión.",
                    "Tu tienda en Mercado CCPIP está habilitada", "Ya puedes publicar tu tienda desde Mi tienda.",
                    "Revocamos la autorización de venta de tu organización", "Desde ahora no se pueden comprar tus productos. "
                            + "Los pedidos ya confirmados se atienden con normalidad.",
                    "Restituimos la autorización de venta de tu organización", "Tus productos autorizados vuelven a estar a la venta.",
                    "Bloqueamos las ventas de tu organización", "Mientras dure el bloqueo no se pueden comprar tus productos; "
                            + "siguen visibles en venta pausada. Los pedidos ya confirmados se atienden con normalidad.",
                    "Desbloqueamos las ventas de tu organización", "Tus productos autorizados vuelven a estar a la venta.",
                    "Restituimos la autorización de venta de tu organización", "Tu autorización de venta está vigente de nuevo. "
                            + "Sin embargo, tus productos todavía no se pueden comprar, porque tus ventas siguen bloqueadas. "
                            + "Te avisaremos cuando la Cámara levante el bloqueo.",
                    "Levantamos el bloqueo de tu organización", "Ya no tienes un bloqueo de ventas. Sin embargo, tus productos "
                            + "todavía no se pueden comprar, porque la autorización de venta de tu organización sigue revocada. "
                            + "Te avisaremos cuando la Cámara la restituya.",
                    "Publicamos tu oferta %s", "Ya está en el catálogo de Mercado CCPIP.",
                    "No publicamos tu oferta %s", "Puedes corregirla desde Mis productos y volver a enviarla a revisión."));

    // El motivo lo escribe el subadministrador en espanol: se envia tal cual
    private static final Idioma EN = new Idioma("Hello", "Reason",
            "Chamber of Commerce of the Indigenous Peoples of Peru", textos(
                    "We authorized the sale of %s", "You can now sell this product on Mercado CCPIP.",
                    "We did not authorize the sale of %s", "You can attach new evidence from My products for a new review.",
                    "We revoked the sale authorization of %s", "It can no longer be purchased. Confirmed orders are fulfilled "
                            + "as usual. You can attach new evidence for a new review.",
                    "Your Mercado CCPIP store is enabled", "You can now publish your store from My store.",
                    "We revoked your organization's sale authorization", "Your products can no longer be purchased. "
                            + "Confirmed orders are fulfilled as usual.",
                    "We restored your organization's sale authorization", "Your authorized products are on sale again.",
                    "We blocked your organization's sales", "While the block lasts your products cannot be purchased; "
                            + "they remain visible as paused. Confirmed orders are fulfilled as usual.",
                    "We unblocked your organization's sales", "Your authorized products are on sale again.",
                    "We restored your organization's sale authorization", "Your sale authorization is valid again. "
                            + "However, your products cannot be purchased yet, because your sales are still blocked. "
                            + "We will let you know when the Chamber lifts the block.",
                    "We lifted your organization's block", "Your sales are no longer blocked. However, your products cannot "
                            + "be purchased yet, because your organization's sale authorization is still revoked. "
                            + "We will let you know when the Chamber restores it.",
                    "We published your offer %s", "It is now in the Mercado CCPIP catalog.",
                    "We did not publish your offer %s", "You can fix it from My products and send it for review again."));

    private final JdbcTemplate jdbc;
    private final Avisos avisos;

    public AvisoAutorizacion(JdbcTemplate jdbc, Avisos avisos) {
        this.jdbc = jdbc;
        this.avisos = avisos;
    }

    /**
     * Envia el aviso al usuario del productor, en su idioma, y lo deja en el registro de avisos.
     * {@code decisionId} es la fila de autorizacion_decision (decisiones por producto), de
     * productor_decision (decisiones sobre el productor) o de moderacion_publicacion (la publicacion de una
     * oferta). Devuelve false si no salio.
     */
    public boolean notificar(long productorId, Tipo tipo, String producto, String motivo, long decisionId) {
        Destinatario d = jdbc.query("""
                        SELECT u.id, u.correo, coalesce(p.nombre_tienda, p.comunidad), u.idioma
                        FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE p.id = ?""",
                (rs, row) -> new Destinatario(rs.getLong(1), rs.getString(2), rs.getString(3), Avisos.ingles(rs.getString(4))),
                productorId).stream().findFirst().orElse(null);
        if (d == null) {
            return false;
        }
        Avisos.Tipo registro = registro(tipo, producto);
        Correo correo = correo(d, d.ingles() ? EN : ES, tipo, producto, motivo);
        return avisos.registrar(d.usuarioId(), registro, decisionId)
                .map(avisoId -> avisos.enviar(avisoId, correo))
                .orElse(false);
    }

    private static Avisos.Tipo registro(Tipo tipo, String producto) {
        if (tipo.name().startsWith("PUBLICACION_")) {
            return Avisos.Tipo.DECISION_MODERACION;
        }
        return producto == null ? Avisos.Tipo.DECISION_PRODUCTOR : Avisos.Tipo.DECISION_AUTORIZACION;
    }

    private static Correo correo(Destinatario d, Idioma idioma, Tipo tipo, String producto, String motivo) {
        Texto texto = idioma.textos().get(tipo);
        return new Correo(d.correo(), texto.asunto().formatted(producto) + " · Mercado CCPIP", """
                %s, %s:

                %s

                %s: %s

                %s
                """.formatted(idioma.saludo(), d.nombre(), texto.detalle(), idioma.motivo(), motivo, idioma.firma()));
    }

    /** Asunto y detalle de cada decision, en el orden de {@link Tipo}. */
    private static Map<Tipo, Texto> textos(String... pares) {
        Map<Tipo, Texto> textos = new EnumMap<>(Tipo.class);
        for (Tipo tipo : Tipo.values()) {
            textos.put(tipo, new Texto(pares[2 * tipo.ordinal()], pares[2 * tipo.ordinal() + 1]));
        }
        return Map.copyOf(textos);
    }
}
