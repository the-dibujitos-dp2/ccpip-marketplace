package pe.ccpip.carrito;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.catalogo.CatalogoRepository.Vendible;
import pe.ccpip.comun.ErrorNegocio;

/**
 * Carrito persistido del comprador (CCPIP-58). Agregar solo consulta la disponibilidad: no
 * reserva ni descuenta existencias (RNF-35, ADR-11). El descuento ocurre al confirmar la compra.
 */
@Service
public class CarritoService {

    /** Resultado de agregar: cuanto hay de esa oferta en el carrito y cuantas ofertas distintas tiene. */
    public record Agregado(long productoId, BigDecimal cantidad, int articulos) {
    }

    private final JdbcTemplate jdbc;
    private final CatalogoRepository catalogo;

    public CarritoService(JdbcTemplate jdbc, CatalogoRepository catalogo) {
        this.jdbc = jdbc;
        this.catalogo = catalogo;
    }

    /**
     * La oferta debe estar visible, venderse al por menor (la tarifa mayorista llega con CCPIP-134)
     * y tener saldo vendible para lo que ya estaba en el carrito mas lo nuevo (RNF-09).
     */
    @Transactional
    public Agregado agregar(long usuarioId, long productoId, BigDecimal cantidad) {
        Vendible oferta = catalogo.vendible(productoId).orElseThrow(() ->
                ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o ya no está disponible"));
        if ("MAYORISTA".equals(oferta.modalidad())) {
            throw ErrorNegocio.invalido("SOLO_MAYORISTA", "La compra mayorista estará disponible pronto.");
        }
        if (oferta.enVenta().signum() == 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "AGOTADO", "Este producto está agotado por ahora.");
        }

        BigDecimal enCarrito = jdbc.query("""
                SELECT i.cantidad FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id
                WHERE c.usuario_id = ? AND i.producto_id = ?""",
                (rs, row) -> rs.getBigDecimal("cantidad"), usuarioId, productoId)
                .stream().findFirst().orElse(BigDecimal.ZERO);
        if (enCarrito.add(cantidad).compareTo(oferta.enVenta()) > 0) {
            String quedan = oferta.enVenta().stripTrailingZeros().toPlainString();
            throw new ErrorNegocio(HttpStatus.CONFLICT, "STOCK_INSUFICIENTE", enCarrito.signum() == 0
                    ? "Solo quedan " + quedan + " unidades disponibles."
                    : "Solo quedan " + quedan + " unidades y ya tienes "
                            + enCarrito.stripTrailingZeros().toPlainString() + " en tu carrito.");
        }

        Long carritoId = jdbc.queryForObject("""
                INSERT INTO carrito (usuario_id) VALUES (?)
                ON CONFLICT (usuario_id) DO UPDATE SET actualizado_en = now()
                RETURNING id""", Long.class, usuarioId);
        // Un producto aparece una sola vez por carrito: agregarlo de nuevo suma cantidad
        BigDecimal total = jdbc.queryForObject("""
                INSERT INTO item_carrito (carrito_id, producto_id, cantidad) VALUES (?, ?, ?)
                ON CONFLICT (carrito_id, producto_id) DO UPDATE SET cantidad = item_carrito.cantidad + EXCLUDED.cantidad
                RETURNING cantidad""", BigDecimal.class, carritoId, productoId, cantidad);
        Integer articulos = jdbc.queryForObject("SELECT count(*) FROM item_carrito WHERE carrito_id = ?",
                Integer.class, carritoId);
        return new Agregado(productoId, total, articulos == null ? 0 : articulos);
    }
}
