package pe.ccpip.compra;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Respuestas del flujo de compra: recojo (CCPIP-157), pago (CCPIP-62, CCPIP-163) y confirmacion (CCPIP-64). */
public final class CompraModelos {

    private CompraModelos() {
    }

    /** Punto de acopio donde se puede recoger todo el carrito. Sin coordenadas: los mapas son simulados (RNF-31). */
    public record PuntoRecojo(long id, String nombre, String departamento, String provincia, String distrito,
                              String direccion) {
    }

    /** Linea del pedido con el precio copiado al confirmar (RNF-13), nunca el vigente. */
    public record LineaPedido(long productoId, String nombre, String tienda, BigDecimal cantidad,
                              BigDecimal precioUnitario, BigDecimal subtotal) {
    }

    /**
     * Un intento de pago (CCPIP-163): estado, monto e identificador, sin datos de la tarjeta salvo la
     * marca. {@code mensaje} explica un rechazo o error al comprador.
     */
    public record IntentoPago(long id, String estado, BigDecimal monto, String marca, String referencia,
                              String mensaje, Instant creadoEn, Instant resueltoEn) {
    }

    /**
     * Pedido del comprador. {@code personaAutorizada} es nula si recoge el mismo comprador;
     * {@code confirmadoEn} es nulo mientras espera su pago.
     */
    public record Pedido(long id, String numero, String estado, String modalidad, BigDecimal total, Instant creadoEn,
                         Instant confirmadoEn, PuntoRecojo punto, String personaAutorizada, String celularContacto,
                         List<LineaPedido> lineas, List<IntentoPago> pagos) {
    }

    /** Resultado de un intento de pago y el pedido como quedo. */
    public record ResultadoPago(String estado, String mensaje, Pedido pedido) {
    }
}
