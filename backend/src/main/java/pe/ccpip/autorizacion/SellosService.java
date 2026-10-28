package pe.ccpip.autorizacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.autorizacion.CertificacionPublicaService.Certificacion;
import pe.ccpip.autorizacion.CertificacionPublicaService.Estado;
import pe.ccpip.autorizacion.CertificacionPublicaService.EvidenciaPublica;

/**
 * CCPIP-38 (RF-15): los sellos que el visitante ve en la ficha de un producto, cada uno con su respaldo.
 *
 * <p>Solo hay sello si hay respaldo (no se atribuyen sellos no acreditados):
 * <ul>
 *   <li>ORIGEN_VERIFICADO: la Camara verifico el origen del productor ({@code productor.origen_verificado},
 *       que la Camara registra; no hay postulacion en la plataforma). Respalda que lo elabora esa comunidad,
 *       nada mas.</li>
 *   <li>CERTIFICADO: cada certificado publico y vigente que sustento la aprobacion vigente del producto
 *       (las mismas reglas de CCPIP-365). Un certificado vencido o una aprobacion sin certificados no da
 *       sello.</li>
 * </ul>
 * Reglas como cero deforestacion o cero pesticidas no son universales (CCPIP-34, C11): solo las respalda el
 * certificado que las acredite, con su titulo y su emisor. El sello comercial queda pendiente de C6.
 */
@Service
public class SellosService {

    public enum TipoSello { ORIGEN_VERIFICADO, CERTIFICADO }

    /**
     * Un sello y su respaldo. En ORIGEN_VERIFICADO, {@code titulo} es la comunidad, {@code emisor} la region
     * y {@code desde} el dia de la verificacion (puede faltar); en CERTIFICADO son los datos del certificado.
     */
    public record Sello(TipoSello tipo, String titulo, String emisor, LocalDate desde, LocalDate hasta) {
    }

    public record SellosProducto(long productoId, List<Sello> sellos) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final CertificacionPublicaService certificacion;

    public SellosService(NamedParameterJdbcTemplate jdbc, CertificacionPublicaService certificacion) {
        this.jdbc = jdbc;
        this.certificacion = certificacion;
    }

    /** Responde 404 si el producto no es visible en el catalogo (lo valida CCPIP-365). */
    @Transactional(readOnly = true)
    public SellosProducto consultar(long productoId) {
        Certificacion cert = certificacion.consultar(productoId);
        List<Sello> sellos = new ArrayList<>();
        jdbc.query("""
                SELECT pr.comunidad, pr.region, pr.verificado_en
                FROM producto p JOIN productor pr ON pr.id = p.productor_id
                WHERE p.id = :id AND pr.origen_verificado""", new MapSqlParameterSource("id", productoId),
                (RowCallbackHandler) rs -> sellos.add(new Sello(TipoSello.ORIGEN_VERIFICADO,
                        rs.getString("comunidad"), rs.getString("region"),
                        rs.getObject("verificado_en", LocalDate.class), null)));
        if (cert.estado() == Estado.VERIFICADA) {
            for (EvidenciaPublica e : cert.evidencias()) {
                if ("CERTIFICADO".equals(e.tipo()) && e.vigente()) {
                    sellos.add(new Sello(TipoSello.CERTIFICADO, e.titulo(), e.emisor(), e.vigenteDesde(), e.vigenteHasta()));
                }
            }
        }
        return new SellosProducto(productoId, List.copyOf(sellos));
    }
}
