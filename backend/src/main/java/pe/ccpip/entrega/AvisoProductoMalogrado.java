package pe.ccpip.entrega;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import pe.ccpip.aviso.Avisos;
import pe.ccpip.aviso.Correo;

/**
 * CCPIP-485 y CCPIP-486: el comprador recibe un correo cuando un producto de su pedido se malogro en el
 * punto: si se reemplazo con otro lote (el precio no cambia) o si no se entregara y cuanto se le devuelve.
 * En la plataforma lo ve en el detalle de su pedido. Un aviso por incidencia (tabla aviso); se llama
 * despues de guardar, asi que un correo caido no la deshace.
 */
@Component
public class AvisoProductoMalogrado {

    private record Datos(long usuarioId, String correo, String nombre, String idioma, long pedidoId, String numero,
                         String estado, String tipo, BigDecimal cantidad, String unidad, String producto,
                         BigDecimal reembolsado) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Avisos avisos;
    private final String urlApp;

    public AvisoProductoMalogrado(NamedParameterJdbcTemplate jdbc, Avisos avisos, @Value("${ccpip.app.url}") String urlApp) {
        this.jdbc = jdbc;
        this.avisos = avisos;
        this.urlApp = urlApp.endsWith("/") ? urlApp.substring(0, urlApp.length() - 1) : urlApp;
    }

    public void avisar(long incidenciaId) {
        jdbc.query("""
                SELECT u.id AS usuario_id, u.correo, u.nombre, u.idioma, pe.id AS pedido_id, pe.numero, pe.estado,
                       f.tipo, f.cantidad, um.nombre AS unidad, pr.nombre AS producto, r.monto AS reembolsado
                FROM incidencia_pedido f
                JOIN pedido pe ON pe.id = f.pedido_id
                JOIN usuario u ON u.id = pe.usuario_id
                JOIN item_pedido i ON i.id = f.item_pedido_id
                JOIN producto pr ON pr.id = i.producto_id
                JOIN unidad_medida um ON um.id = pr.unidad_medida_id
                LEFT JOIN reembolso r ON r.id = f.reembolso_id
                WHERE f.id = :id""", new MapSqlParameterSource("id", incidenciaId),
                (rs, row) -> new Datos(rs.getLong("usuario_id"), rs.getString("correo"), rs.getString("nombre"),
                        rs.getString("idioma"), rs.getLong("pedido_id"), rs.getString("numero"), rs.getString("estado"),
                        rs.getString("tipo"), rs.getBigDecimal("cantidad"), rs.getString("unidad"), rs.getString("producto"),
                        rs.getBigDecimal("reembolsado")))
                .stream().findFirst()
                .ifPresent(d -> avisos.registrar(d.usuarioId(), Avisos.Tipo.PRODUCTO_MALOGRADO, incidenciaId)
                        .ifPresent(aviso -> avisos.enviar(aviso, correo(d))));
    }

    private Correo correo(Datos d) {
        String url = urlApp + "/pedidos/" + d.pedidoId();
        String cantidad = d.cantidad().stripTrailingZeros().toPlainString() + " " + d.unidad();
        boolean anulado = "CANCELADO".equals(d.estado());
        if (Avisos.ingles(d.idioma())) {
            String que = "REEMPLAZO".equals(d.tipo())
                    ? "We replaced them with another batch of the same product. Your order and its price do not change."
                    : "We could not replace them, so they will not be delivered. We refunded S/ " + d.reembolsado()
                            .toPlainString() + " to your card." + (anulado
                            ? " Nothing is left to deliver, so the order was cancelled and the shipping cost refunded too."
                            : " The rest of your order is delivered as planned.");
            return new Correo(d.correo(), "A product in your order " + d.numero() + " was damaged", """
                    Hello, %s:

                    Before delivery, %s of %s from your order %s was damaged at the collection point.

                    %s

                    You can see the details here:

                    %s

                    Chamber of Commerce of the Indigenous Peoples of Peru
                    """.formatted(d.nombre(), cantidad, d.producto(), d.numero(), que, url));
        }
        String que = "REEMPLAZO".equals(d.tipo())
                ? "Lo reemplazamos con otro lote del mismo producto. Tu pedido y su precio no cambian."
                : "No pudimos reemplazarlo, así que no se entregará. Te devolvimos S/ " + d.reembolsado().toPlainString()
                        + " a tu tarjeta." + (anulado
                        ? " Ya no queda nada por entregar: el pedido se anuló y también te devolvimos el envío."
                        : " El resto de tu pedido se entrega como estaba previsto.");
        return new Correo(d.correo(), "Un producto de tu pedido " + d.numero() + " se malogró", """
                Hola, %s:

                Antes de la entrega, %s de %s de tu pedido %s se malogró en el punto de acopio.

                %s

                Puedes ver el detalle aquí:

                %s

                Cámara de Comercio de los Pueblos Indígenas del Perú
                """.formatted(d.nombre(), cantidad, d.producto(), d.numero(), que, url));
    }
}
