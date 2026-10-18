package pe.ccpip.interes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import pe.ccpip.aviso.Avisos;
import pe.ccpip.aviso.Correo;
import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.catalogo.CatalogoRepository.Vendible;

/**
 * CCPIP-273 (RF-150): cuando una oferta con interesados vuelve a venderse (llego stock o el productor
 * reactivo su cuenta o su autorizacion), cada interesado recibe un correo y su interes se cierra. Es la
 * misma oferta y el mismo productor; no reserva ni compra nada por el.
 *
 * <p>Una revision periodica cubre todas las causas sin engancharse en cada flujo. El interes se cierra y
 * el aviso se registra en una transaccion; el correo sale despues, y un fallo queda en el aviso.
 */
@Component
public class AvisoInteresVendible {

    private static final Logger LOG = LoggerFactory.getLogger(AvisoInteresVendible.class);

    private record Cerrado(long id, long usuarioId, long productoId, int cantidad, String correo, String nombre,
                           String idioma, String producto, String tienda) {
    }

    private record PorAvisar(long aviso, Cerrado interes) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CatalogoRepository catalogo;
    private final Avisos avisos;
    private final String urlApp;

    public AvisoInteresVendible(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, CatalogoRepository catalogo,
                                Avisos avisos, @Value("${ccpip.app.url}") String urlApp) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.catalogo = catalogo;
        this.avisos = avisos;
        this.urlApp = urlApp.endsWith("/") ? urlApp.substring(0, urlApp.length() - 1) : urlApp;
    }

    @Scheduled(fixedDelayString = "${ccpip.intereses.revision:PT5M}",
            initialDelayString = "${ccpip.intereses.revision-inicial:PT1M}")
    public void revisarProgramado() {
        try {
            int avisados = revisar();
            if (avisados > 0) {
                LOG.info("Avisos de oferta disponible enviados: {}", avisados);
            }
        } catch (RuntimeException e) {
            LOG.error("Fallo la revision de intereses: {}", e.getMessage(), e);
        }
    }

    /** Avisa a los interesados en las ofertas que ya se pueden comprar. Devuelve cuantos avisos envio. */
    public int revisar() {
        List<Long> conInteres = jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT producto_id FROM interes_compra WHERE estado = 'PENDIENTE'", Long.class);
        if (conInteres.isEmpty()) {
            return 0;
        }
        Map<Long, Vendible> vendibles = catalogo.vendibles(conInteres);
        List<Long> listos = conInteres.stream().filter(id -> InteresService.vendible(vendibles.get(id))).toList();
        if (listos.isEmpty()) {
            return 0;
        }
        List<PorAvisar> porAvisar = tx.execute(s -> cerrar(listos));
        porAvisar.forEach(a -> avisos.enviar(a.aviso(), correo(a.interes())));
        return porAvisar.size();
    }

    /** Cierra los intereses pendientes de esas ofertas y registra un aviso por cada uno, sin duplicar. */
    private List<PorAvisar> cerrar(List<Long> productos) {
        List<Cerrado> cerrados = jdbc.query("""
                UPDATE interes_compra i SET estado = 'AVISADO', avisado_en = now(), actualizado_en = now()
                FROM usuario u, producto p, productor pr
                WHERE i.estado = 'PENDIENTE' AND i.producto_id IN (:productos)
                  AND u.id = i.usuario_id AND p.id = i.producto_id AND pr.id = i.productor_id
                RETURNING i.id, i.usuario_id, i.producto_id, i.cantidad, u.correo, u.nombre, u.idioma,
                          p.nombre AS producto, coalesce(pr.nombre_tienda, pr.comunidad) AS tienda""",
                new MapSqlParameterSource("productos", productos),
                (rs, row) -> new Cerrado(rs.getLong("id"), rs.getLong("usuario_id"), rs.getLong("producto_id"),
                        rs.getInt("cantidad"), rs.getString("correo"), rs.getString("nombre"), rs.getString("idioma"),
                        rs.getString("producto"), rs.getString("tienda")));
        return cerrados.stream()
                .map(c -> avisos.registrar(c.usuarioId(), Avisos.Tipo.INTERES_VENDIBLE, c.id()).map(a -> new PorAvisar(a, c)))
                .flatMap(Optional::stream)
                .toList();
    }

    private Correo correo(Cerrado c) {
        String url = urlApp + "/productos/" + c.productoId();
        if (Avisos.ingles(c.idioma())) {
            return new Correo(c.correo(), c.producto() + " is available again", """
                    Hello, %s:

                    %s from %s is available again. You registered interest in %d unit(s).

                    It is not a reservation: buy it like any other customer while there is stock.

                    %s

                    Chamber of Commerce of the Indigenous Peoples of Peru
                    """.formatted(c.nombre(), c.producto(), c.tienda(), c.cantidad(), url));
        }
        return new Correo(c.correo(), c.producto() + " vuelve a estar disponible", """
                Hola, %s:

                %s de %s vuelve a estar disponible. Registraste tu interés por %d unidad(es).

                No es una reserva: puedes comprarlo como cualquier cliente mientras haya stock.

                %s

                Cámara de Comercio de los Pueblos Indígenas del Perú
                """.formatted(c.nombre(), c.producto(), c.tienda(), c.cantidad(), url));
    }
}
