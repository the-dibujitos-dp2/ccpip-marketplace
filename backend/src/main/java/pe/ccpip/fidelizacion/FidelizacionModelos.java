package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.ccpip.reportes.gestion.GestionModelos.Indicador;

/** CCPIP-86: campanas de puntos del sub administrador, sus metricas y los puntos de cada pedido. */
public final class FidelizacionModelos {

    private FidelizacionModelos() {
    }

    /**
     * PROGRAMADA empieza despues de hoy; ACTIVA rige hoy; FINALIZADA ya termino (por fecha o porque se finalizo
     * antes); CANCELADA se finalizo antes de empezar.
     */
    public enum EstadoCampana { PROGRAMADA, ACTIVA, FINALIZADA, CANCELADA }

    /** Lo que llevan los pedidos de una campana: los que cumplen su minimo dentro de su periodo. */
    public record Resumen(int pedidos, BigDecimal ventas, int clientes, long puntosOtorgados) {
    }

    /**
     * Una campana. {@code finEfectivo}: el ultimo dia en que rigio o regira (antes de {@code hasta} si se
     * finalizo antes); nulo si se cancelo. {@code topePuntos} nulo: sin tope por pedido.
     */
    public record Campana(long id, String nombre, String descripcion, LocalDate desde, LocalDate hasta,
                          LocalDate finEfectivo, int puntos, BigDecimal cadaSoles, BigDecimal montoMinimo,
                          Integer topePuntos, EstadoCampana estado, String registradoPor, Instant registradoEn,
                          String finalizadoPor, Instant finalizadoEn, Resumen resumen) {

        ReglaPuntos regla() {
            return new ReglaPuntos(puntos, cadaSoles, topePuntos);
        }
    }

    /** Una campana nueva, o los cambios de una que aun no empieza. Montos en soles, de productos sin envio. */
    public record NuevaCampana(
            @NotBlank(message = "Ponle un nombre a la campaña") @Size(max = 80, message = "Máximo 80 caracteres") String nombre,
            @Size(max = 300, message = "Máximo 300 caracteres") String descripcion,
            @NotNull(message = "Indica desde cuándo rige") LocalDate desde,
            @NotNull(message = "Indica hasta cuándo rige") LocalDate hasta,
            @NotNull(message = "Indica cuántos puntos se ganan")
            @Min(value = 1, message = "Gana al menos 1 punto") @Max(value = 1000, message = "Máximo 1000 puntos") Integer puntos,
            @NotNull(message = "Indica por cada cuántos soles")
            @DecimalMin(value = "1", message = "Desde S/ 1") @DecimalMax(value = "10000", message = "Hasta S/ 10 000")
            @Digits(integer = 5, fraction = 2, message = "Usa hasta 2 decimales") BigDecimal cadaSoles,
            @DecimalMin(value = "0", message = "El mínimo no puede ser negativo")
            @Digits(integer = 8, fraction = 2, message = "Usa hasta 2 decimales") BigDecimal montoMinimo,
            @Min(value = 1, message = "El tope es de al menos 1 punto") @Max(value = 1000000, message = "Máximo 1 000 000 de puntos")
            Integer topePuntos) {
    }

    /** Lo acumulado por todos los compradores, con y sin campana. */
    public record Totales(long puntosOtorgados, long puntosDeCampanas, int clientes, int pedidos) {
    }

    /** La seccion Fidelizacion: la regla base, lo acumulado y las campanas, las mas recientes primero. */
    public record Panel(LocalDate hoy, ReglaPuntos reglaBase, Totales totales, List<Campana> campanas) {
    }

    /** Un dia de la campana: pedidos que la cumplieron, lo vendido y los puntos que generan. */
    public record Dia(LocalDate fecha, int pedidos, BigDecimal ventas, long puntos) {
    }

    /** Un producto entre los mas vendidos de la campana. */
    public record ProductoTop(long id, String nombre, String comunidad, BigDecimal unidades, BigDecimal ventas) {
    }

    /**
     * El desempeno de una campana en {@code desde}..{@code hasta} (hasta hoy si sigue activa), frente al periodo
     * anterior de igual duracion ({@code anteriorDesde}..{@code anteriorHasta}) con el mismo pedido minimo.
     * {@code puntosPorAcreditar}: los que daran los pedidos de la campana aun no entregados.
     * {@code clientesNuevos}: sin compras antes de la campana; {@code clientesRecurrentes}: con 2 o mas pedidos en ella.
     * {@code pedidosBajoMinimo}: del periodo, por debajo del minimo (ganaron con la regla base). Los puntos y el minimo
 * se cuentan sobre lo pagado por los productos despues del descuento; {@code codigos}: el uso de sus codigos.
     */
    public record Metricas(Campana campana, LocalDate desde, LocalDate hasta, LocalDate anteriorDesde,
                           LocalDate anteriorHasta, Indicador pedidos, Indicador ventas, Indicador ticketPromedio,
                           Indicador clientes, long puntosOtorgados, long puntosPorAcreditar, int pedidosEntregados,
                           int pedidosPorEntregar, int pedidosBajoMinimo, int clientesNuevos,
                           int clientesRecurrentes, List<Dia> dias, List<ProductoTop> productos, UsoCodigos codigos) {
    }

    /** Compras pagadas con los codigos de descuento de la campana y lo que descontaron. */
    public record UsoCodigos(int usos, BigDecimal descuento) {
    }

    /** CCPIP-86: los puntos que gano un pedido entregado; {@code campana} es nula si fue con la regla base. */
    public record PuntosPedido(int puntos, String campana) {
    }
}
