package pe.ccpip.compra;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import pe.ccpip.comision.ComisionModelos.ComisionPedido;
import pe.ccpip.entrega.EntregaModelos.Distrito;
import pe.ccpip.entrega.EntregaModelos.Entrega;

/**
 * Respuestas del flujo de compra: recojo (CCPIP-157), pago (CCPIP-62, CCPIP-163), confirmacion (CCPIP-64)
 * y los pedidos del comprador (CCPIP-112, CCPIP-70, CCPIP-340).
 */
public final class CompraModelos {

    private CompraModelos() {
    }

    /** Punto de acopio donde se puede recoger todo el carrito. Sin coordenadas: los mapas son simulados (RNF-31). */
    public record PuntoRecojo(long id, String nombre, String departamento, String provincia, String distrito,
                              String direccion) {
    }

    /**
     * CCPIP-59: un distrito al que se entrega a domicilio todo el carrito y los puntos de acopio que lo
     * cubren con existencias para todas sus lineas. {@code envio}: la tarifa de envio a ese distrito (CCPIP-482).
     */
    public record DistritoConPuntos(Distrito distrito, BigDecimal envio, List<PuntoRecojo> puntos) {
    }

    /**
     * CCPIP-295 (RF-155): un producto perecible del carrito y hasta cuando se consume lo que saldria del punto
     * elegido: el vencimiento de su lote mas proximo a vencer, que es el primero que se despacha (FEFO).
     * {@code diasRestantes}: de hoy (Lima) a esa fecha. No estima cuando llega el pedido.
     */
    public record AdvertenciaPerecible(long productoId, String nombre, LocalDate consumirAntesDe, long diasRestantes) {
    }

    /**
     * CCPIP-60 (RF-41, RNF-45): si el carrito se puede entregar desde el punto elegido, antes de cobrar.
     * {@code existencias}: el punto tiene lotes vigentes para todas las lineas. {@code cobertura}: el punto
     * cubre el distrito de destino; en el recojo siempre es verdadera. {@code motivos}: los codigos de error
     * que se obtendrian al confirmar o pagar ({@code PUNTO_SIN_EXISTENCIAS}, {@code SIN_COBERTURA}).
     */
    public record Factibilidad(boolean factible, boolean existencias, boolean cobertura, List<String> motivos) {
        static Factibilidad de(boolean existencias, boolean cobertura) {
            List<String> motivos = new ArrayList<>();
            if (!existencias) {
                motivos.add("PUNTO_SIN_EXISTENCIAS");
            }
            if (!cobertura) {
                motivos.add("SIN_COBERTURA");
            }
            return new Factibilidad(motivos.isEmpty(), existencias, cobertura, List.copyOf(motivos));
        }
    }

    /**
     * Linea del pedido con el precio copiado al confirmar (RNF-13), nunca el vigente. {@code tarifa}:
     * MINORISTA o MAYORISTA, la modalidad con que se vendio (CCPIP-178, CCPIP-370), tambien copiada.
     * {@code noEntregada}: lo que se malogro y se reembolso en vez de entregarse (CCPIP-486); 0 si no falta nada.
     */
    public record LineaPedido(long productoId, long productorId, String nombre, String tienda, BigDecimal cantidad,
                              BigDecimal precioUnitario, BigDecimal subtotal, String tarifa, BigDecimal noEntregada) {
    }

    /**
     * CCPIP-485/486: un producto del pedido que se malogro en el punto antes de entregarse. REEMPLAZO: se
     * cubrio con otro lote y el precio no cambio. FALTANTE: no se entrega y se devolvio {@code reembolsado}
     * (con el envio, si ya no quedo nada por entregar). No dice quien lo registro.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IncidenciaPedido(String tipo, String producto, BigDecimal cantidad, BigDecimal reembolsado,
                                   Instant registradaEn) {
    }

    /**
     * Un intento de pago (CCPIP-163): estado, monto e identificador, sin datos de la tarjeta salvo la
     * marca. {@code mensaje} explica un rechazo o error al comprador.
     */
    public record IntentoPago(long id, String estado, BigDecimal monto, String marca, String referencia,
                              String mensaje, Instant creadoEn, Instant resueltoEn) {
    }

    /**
     * CCPIP-340: un cambio de estado del pedido y cuando ocurrio. No dice quien lo hizo: eso es del
     * punto de acopio, no del comprador.
     */
    public record EventoPedido(String estado, Instant registradoEn) {
    }

    /**
     * Pedido del comprador. {@code personaAutorizada} es nula si recoge el mismo comprador;
     * {@code confirmadoEn} es nulo mientras espera su pago. {@code historial}: sus estados en orden
     * cronologico (CCPIP-340). {@code comision}: la vigente al confirmar, ya incluida en el total
     * (CCPIP-167); nula mientras espera su pago o si se confirmo sin comision configurada.
     * {@code entrega} es el destino de un pedido a domicilio (CCPIP-59), nula si es para recojo; en ese
     * caso {@code punto} es el punto de acopio de donde sale. {@code total} es la suma de los productos;
     * {@code costoEnvio} (CCPIP-483) es 0 en recojo y {@code totalAPagar} = total + costoEnvio.
     * CCPIP-485/486: {@code incidencias}, lo que se malogro antes de la entrega; {@code reembolsado}, lo que
     * se devolvio por ello, y {@code totalFinal}, lo que el comprador termina pagando. {@code moneda}: ISO
     * 4217 de todos los montos; solo PEN, porque esta version vende solo en Peru (CCPIP-178, CCPIP-370).
     */
    public record Pedido(long id, String numero, String estado, String modalidad, BigDecimal total, Instant creadoEn,
                         Instant confirmadoEn, PuntoRecojo punto, String personaAutorizada, String celularContacto,
                         List<LineaPedido> lineas, List<IntentoPago> pagos, List<EventoPedido> historial,
                         ComisionPedido comision, Entrega entrega, BigDecimal costoEnvio, BigDecimal totalAPagar,
                         List<IncidenciaPedido> incidencias, BigDecimal reembolsado, BigDecimal totalFinal,
                         String moneda) {
    }

    /**
     * CCPIP-112 y CCPIP-70 (CW-09): un pedido en la lista del comprador, con su estado actual, el total
     * que pago (no el precio vigente), cuantos productos y comunidades incluye y su historial.
     * {@code primerProducto}: el nombre del primer producto del pedido, para reconocerlo sin abrirlo (CCPIP-501).
     */
    public record ResumenPedido(long id, String numero, String estado, String modalidad, BigDecimal total,
                                Instant creadoEn, Instant confirmadoEn, PuntoRecojo punto, int productos,
                                int comunidades, List<EventoPedido> historial, BigDecimal costoEnvio,
                                BigDecimal totalAPagar, String primerProducto) {
    }

    /** Resultado de un intento de pago y el pedido como quedo. */
    public record ResultadoPago(String estado, String mensaje, Pedido pedido) {
    }
}
