package pe.ccpip.carrito;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import pe.ccpip.carrito.Tarifas.Tarifa;
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
        /**
         * CCPIP-134: se vende solo por mayor y la cantidad no llega al minimo; {@code faltanteMayorista}
         * dice cuantas unidades faltan.
         */
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
     * <p>
     * CCPIP-134 (RF-108): {@code precio} es el de la {@code tarifa} que corresponde a la modalidad y la
     * cantidad (ver {@link Tarifas}). {@code precioMinorista} y {@code precioMayorista} son los de la
     * oferta (nulos si no vende asi), {@code minimoMayorista} su minimo y {@code faltanteMayorista} las
     * unidades que faltan para llegar a el (cero si ya se aplica; nulo sin tarifa mayorista). Una oferta
     * que ya no esta visible no informa tarifa.
     */
    public record Item(long productoId, String nombre, String contenido, String unidad, String imagen,
                       String linea, LocalDate cosecha, TiendaResumen tienda, BigDecimal precio, BigDecimal cantidad, BigDecimal subtotal,
                       EstadoItem estado, BigDecimal disponibles, Tarifa tarifa, BigDecimal precioMinorista,
                       BigDecimal precioMayorista, Integer minimoMayorista, BigDecimal faltanteMayorista) {
    }

    /**
     * CCPIP-63 (RF-44): las ofertas de un mismo productor (su tienda o comunidad, Figma CW-05), en el
     * orden en que se agregaron. {@code subtotal} suma solo lo que se puede comprar, igual que el total.
     * Cada item es una oferta concreta de ese productor: no se juntan ofertas ni inventarios de otros.
     */
    public record Grupo(TiendaResumen tienda, List<Item> items, BigDecimal subtotal) {
    }

    /**
     * {@code unidades} cuenta todo lo que hay en el carrito (para la cabecera); {@code total} y
     * {@code comunidades} solo lo que se puede comprar. {@code total} es exactamente la suma de los
     * subtotales de {@code grupos} (CCPIP-63).
     * <p>
     * CCPIP-142 (RF-109): {@code vigenteHasta} es hasta cuando se guarda el carrito si no se vuelve a
     * cambiar (nulo si esta vacio); {@code vigenciaHoras}, el plazo que rige. {@code vencidoEn} dice
     * cuando se vacio por vencer, para avisarlo; nulo si no vencio o si ya se volvio a agregar algo.
     */
    public record Carrito(List<Item> items, List<Grupo> grupos, BigDecimal unidades, long comunidades,
                          BigDecimal total, Instant vigenteHasta, long vigenciaHoras, Instant vencidoEn) {
    }
}
