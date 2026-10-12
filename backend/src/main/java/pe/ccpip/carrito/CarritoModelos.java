package pe.ccpip.carrito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import pe.ccpip.catalogo.CatalogoModelos.TiendaResumen;

/** Respuestas del carrito (CCPIP-155). Precios y disponibilidad se recalculan en cada consulta. */
public final class CarritoModelos {

    private CarritoModelos() {
    }

    /**
     * Situacion actual de una oferta del carrito. Solo DISPONIBLE suma al total: lo demas se muestra
     * para que el comprador lo ajuste o lo quite, pero no se podria cobrar.
     */
    public enum EstadoItem {
        DISPONIBLE,
        /** Hay saldo, pero menos de lo que esta en el carrito. */
        STOCK_INSUFICIENTE,
        AGOTADO,
        /** La tarifa mayorista llega con CCPIP-134. */
        SOLO_MAYORISTA,
        /** Retirada del catalogo, o su tienda ya no esta publicada. */
        NO_DISPONIBLE,
        /**
         * Sigue visible, pero su productor no tiene cuenta bancaria activa o autorizacion vigente
         * para el producto (CCPIP-269, RF-142).
         */
        VENTA_PAUSADA
    }

    /**
     * {@code precio} y {@code subtotal} son los vigentes (RF-124); {@code subtotal} es nulo si el item
     * no se puede comprar. {@code disponibles} solo se informa con STOCK_INSUFICIENTE. {@code linea}
     * es la linea principal: sin foto, la pantalla muestra su ilustracion (Figma CW-05). {@code cosecha}
     * es la produccion mas reciente entre sus lotes vendibles, como en la ficha; nula si no hay.
     */
    public record Item(long productoId, String nombre, String contenido, String unidad, String imagen,
                       String linea, LocalDate cosecha, TiendaResumen tienda, BigDecimal precio, BigDecimal cantidad, BigDecimal subtotal,
                       EstadoItem estado, BigDecimal disponibles) {
    }

    /**
     * {@code unidades} cuenta todo lo que hay en el carrito (para la cabecera); {@code total} y
     * {@code comunidades} solo lo que se puede comprar.
     */
    public record Carrito(List<Item> items, BigDecimal unidades, long comunidades, BigDecimal total) {
    }
}
