package pe.ccpip.entrega;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import pe.ccpip.aviso.Avisos;
import pe.ccpip.aviso.Correo;

/**
 * CCPIP-69 (RF-51): cuando el punto marca el pedido como listo, el comprador recibe un correo con el
 * punto, quien puede recoger y el enlace a su pedido. Se avisa una sola vez por pedido (tabla aviso).
 * Se llama despues de confirmar el cambio de estado: un correo caido no lo deshace.
 */
@Component
public class AvisoPedidoListo {

    private record Datos(long pedidoId, String numero, String personaAutorizada, long usuarioId, String correo,
                         String nombre, String idioma, String punto, String direccion) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Avisos avisos;
    private final String urlApp;

    public AvisoPedidoListo(NamedParameterJdbcTemplate jdbc, Avisos avisos, @Value("${ccpip.app.url}") String urlApp) {
        this.jdbc = jdbc;
        this.avisos = avisos;
        this.urlApp = urlApp.endsWith("/") ? urlApp.substring(0, urlApp.length() - 1) : urlApp;
    }

    /** Avisa al comprador si el pedido sigue listo para recojo y aun no se le aviso. */
    public void avisar(String numero) {
        jdbc.query("""
                SELECT pe.id, pe.numero, pe.persona_autorizada, u.id AS usuario_id, u.correo, u.nombre, u.idioma,
                       l.nombre AS punto, l.direccion, l.distrito, l.departamento
                FROM pedido pe
                JOIN usuario u ON u.id = pe.usuario_id
                JOIN ubicacion l ON l.id = pe.ubicacion_origen_id
                WHERE upper(pe.numero) = upper(:numero) AND pe.estado = 'LISTO_PARA_RECOJO'""",
                new MapSqlParameterSource("numero", numero),
                (rs, row) -> new Datos(rs.getLong("id"), rs.getString("numero"), rs.getString("persona_autorizada"),
                        rs.getLong("usuario_id"), rs.getString("correo"), rs.getString("nombre"), rs.getString("idioma"),
                        rs.getString("punto"), Stream.of(rs.getString("direccion"), rs.getString("distrito"),
                                rs.getString("departamento")).filter(s -> s != null && !s.isBlank())
                                .collect(Collectors.joining(", "))))
                .stream().findFirst()
                .ifPresent(d -> avisos.registrar(d.usuarioId(), Avisos.Tipo.PEDIDO_LISTO, d.pedidoId())
                        .ifPresent(aviso -> avisos.enviar(aviso, correo(d))));
    }

    private Correo correo(Datos d) {
        String url = urlApp + "/pedidos/" + d.pedidoId();
        if (Avisos.ingles(d.idioma())) {
            String quien = d.personaAutorizada() == null ? "You, with your ID document."
                    : d.personaAutorizada() + ", with their ID document.";
            return new Correo(d.correo(), "Your order " + d.numero() + " is ready for pickup", """
                    Hello, %s:

                    Your order %s is ready for pickup at:

                    %s
                    %s

                    Who picks it up: %s
                    Bring the order number. You can see the details here:

                    %s

                    Chamber of Commerce of the Indigenous Peoples of Peru
                    """.formatted(d.nombre(), d.numero(), d.punto(), d.direccion(), quien, url));
        }
        String quien = d.personaAutorizada() == null ? "Tú, con tu documento de identidad."
                : d.personaAutorizada() + ", con su documento de identidad.";
        return new Correo(d.correo(), "Tu pedido " + d.numero() + " está listo para recoger", """
                Hola, %s:

                Tu pedido %s ya está listo para recoger en:

                %s
                %s

                Recoge: %s
                Lleva el número de pedido. Puedes ver el detalle aquí:

                %s

                Cámara de Comercio de los Pueblos Indígenas del Perú
                """.formatted(d.nombre(), d.numero(), d.punto(), d.direccion(), quien, url));
    }
}
