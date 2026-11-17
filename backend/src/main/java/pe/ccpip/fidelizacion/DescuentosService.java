package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.fidelizacion.BeneficiosModelos.DescuentoAplicado;
import pe.ccpip.fidelizacion.BeneficiosModelos.EstadoCupon;
import pe.ccpip.fidelizacion.BeneficiosModelos.Origen;
import pe.ccpip.fidelizacion.BeneficiosModelos.SolicitudDescuento;
import pe.ccpip.fidelizacion.FidelizacionModelos.Campana;

/**
 * El descuento de una compra (decisiones del 17/11): uno solo por pedido, de un cupon que el comprador canjeo con sus
 * puntos (CCPIP-89) o de un codigo de una campana que rige (CCPIP-88). Se calcula sobre los productos, sin envio, y lo
 * asume la Camara. Al confirmar el pedido se bloquean el cupon o el codigo: dos compras a la vez no lo usan de mas.
 */
@Service
public class DescuentosService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CampanasService campanas;

    public DescuentosService(NamedParameterJdbcTemplate jdbc, CampanasService campanas) {
        this.jdbc = jdbc;
        this.campanas = campanas;
    }

    /** El descuento que pide {@code usuarioId} para {@code productos} soles, ya validado; o un error que explica por que no. */
    @Transactional
    public DescuentoAplicado resolver(long usuarioId, SolicitudDescuento solicitud, BigDecimal productos) {
        boolean conCodigo = solicitud.codigo() != null && !solicitud.codigo().isBlank();
        if (solicitud.cuponId() != null && conCodigo) {
            throw ErrorNegocio.invalido("UN_SOLO_DESCUENTO", "Usa un cupón o un código, no los dos.");
        }
        return conCodigo ? delCodigo(usuarioId, CodigosService.normal(solicitud.codigo()), productos)
                : delCupon(usuarioId, solicitud.cuponId(), productos);
    }

    private DescuentoAplicado delCupon(long usuarioId, long cuponId, BigDecimal productos) {
        record Canjeado(String nombre, EstadoCupon estado, Beneficio beneficio) {
        }
        Canjeado cupon = jdbc.query("""
                        SELECT cc.nombre, cc.tipo, cc.valor, cc.monto_minimo, cc.tope_descuento, %s AS estado
                        FROM cupon_cliente cc %s
                        WHERE cc.id = :id AND cc.usuario_id = :usuario
                        FOR UPDATE OF cc""".formatted(CuponesService.ESTADO_CUPON, CuponesService.PEDIDO_DEL_CUPON),
                        new MapSqlParameterSource("id", cuponId).addValue("usuario", usuarioId),
                        (rs, row) -> new Canjeado(rs.getString("nombre"), EstadoCupon.valueOf(rs.getString("estado")),
                                Beneficio.de(rs)))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("CUPON_NO_ENCONTRADO", "No encontramos ese cupón entre los tuyos."));
        switch (cupon.estado()) {
            case USADO, EN_USO -> throw new ErrorNegocio(HttpStatus.CONFLICT, "CUPON_USADO", "Ya usaste este cupón en otra compra.");
            case VENCIDO -> throw new ErrorNegocio(HttpStatus.CONFLICT, "CUPON_VENCIDO", "Este cupón ya venció.");
            default -> {
                // Disponible
            }
        }
        return aplicado(Origen.CUPON, cuponId, null, cupon.nombre(), cupon.beneficio(), productos);
    }

    private DescuentoAplicado delCodigo(long usuarioId, String codigo, BigDecimal productos) {
        record DeCampana(long id, long campanaId, Integer maximos, int porCliente, Beneficio beneficio) {
        }
        DeCampana d = jdbc.query("""
                        SELECT * FROM codigo_descuento WHERE codigo = :codigo AND desactivado_en IS NULL FOR UPDATE""",
                        new MapSqlParameterSource("codigo", codigo),
                        (rs, row) -> new DeCampana(rs.getLong("id"), rs.getLong("campana_id"),
                                rs.getObject("usos_maximos", Integer.class), rs.getInt("usos_por_cliente"), Beneficio.de(rs)))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("CODIGO_NO_VALIDO", "Ese código no existe o ya no está activo."));
        Campana vigente = campanas.vigenteAl(Instant.now()).filter(c -> c.id() == d.campanaId())
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT, "CODIGO_FUERA_DE_CAMPANA",
                        "Este código no está vigente: su campaña aún no empieza o ya terminó."));
        MapSqlParameterSource p = new MapSqlParameterSource("codigo", d.id()).addValue("usuario", usuarioId);
        String usos = "SELECT count(*) FROM pedido WHERE codigo_descuento_id = :codigo AND estado <> 'CANCELADO'";
        Integer total = jdbc.queryForObject(usos, p, Integer.class);
        if (d.maximos() != null && total != null && total >= d.maximos()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CODIGO_AGOTADO", "Este código ya se usó todas las veces posibles.");
        }
        Integer delCliente = jdbc.queryForObject(usos + " AND usuario_id = :usuario", p, Integer.class);
        if (delCliente != null && delCliente >= d.porCliente()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CODIGO_YA_USADO", "Ya usaste este código.");
        }
        return aplicado(Origen.CODIGO, null, d.id(), codigo + " · " + vigente.nombre(), d.beneficio(), productos);
    }

    private static DescuentoAplicado aplicado(Origen origen, Long cuponId, Long codigoId, String nombre, Beneficio beneficio,
                                              BigDecimal productos) {
        if (!beneficio.aplica(productos)) {
            throw ErrorNegocio.invalido("DESCUENTO_BAJO_MINIMO",
                    "Este descuento es para compras desde S/ " + beneficio.montoMinimo().setScale(2, RoundingMode.HALF_UP).toPlainString()
                            + " en productos.");
        }
        BigDecimal monto = beneficio.descuentoPara(productos);
        if (monto.signum() <= 0) {
            throw ErrorNegocio.invalido("DESCUENTO_SIN_MONTO", "Tu compra es muy pequeña para este descuento.");
        }
        return new DescuentoAplicado(origen, cuponId, codigoId, nombre, monto);
    }
}
