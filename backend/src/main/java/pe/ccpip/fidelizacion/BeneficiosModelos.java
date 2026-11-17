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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pe.ccpip.fidelizacion.Beneficio.Tipo;

/**
 * CCPIP-88 (niveles por recurrencia), CCPIP-89 (canje de puntos por cupones), CCPIP-173 y 374 (saldo y movimientos
 * del comprador) y los codigos de descuento de las campanas.
 */
public final class BeneficiosModelos {

    private BeneficiosModelos() {
    }

    // --- Niveles (CCPIP-88) ---

    /** Un nivel: desde {@code pedidosMinimos} pedidos entregados, los puntos se multiplican por {@code multiplicador}. */
    public record Nivel(long id, String nombre, int pedidosMinimos, BigDecimal multiplicador) {
    }

    public record NuevoNivel(
            @NotBlank(message = "Ponle un nombre al nivel") @Size(max = 40, message = "Máximo 40 caracteres") String nombre,
            @NotNull(message = "Indica desde cuántos pedidos entregados")
            @Min(value = 1, message = "Desde 1 pedido") @Max(value = 10000, message = "Hasta 10 000 pedidos") Integer pedidosMinimos,
            @NotNull(message = "Indica el multiplicador")
            @DecimalMin(value = "1.01", message = "El multiplicador es mayor que 1")
            @DecimalMax(value = "5", message = "El multiplicador es como máximo 5")
            @Digits(integer = 1, fraction = 2, message = "Usa hasta 2 decimales") BigDecimal multiplicador) {
    }

    // --- Beneficio de cupones y codigos ---

    /** Lo que descuenta un cupon o un codigo. {@code topeDescuento} solo para porcentajes. */
    public record NuevoBeneficio(
            @NotNull(message = "Elige si descuenta un monto o un porcentaje") Tipo tipo,
            @NotNull(message = "Indica cuánto descuenta")
            @DecimalMin(value = "0.01", message = "El descuento es mayor que 0")
            @DecimalMax(value = "10000", message = "Hasta S/ 10 000")
            @Digits(integer = 5, fraction = 2, message = "Usa hasta 2 decimales") BigDecimal valor,
            @DecimalMin(value = "0", message = "El mínimo no puede ser negativo")
            @Digits(integer = 8, fraction = 2, message = "Usa hasta 2 decimales") BigDecimal montoMinimo,
            @DecimalMin(value = "0.01", message = "El tope es mayor que 0")
            @Digits(integer = 8, fraction = 2, message = "Usa hasta 2 decimales") BigDecimal topeDescuento) {

        Beneficio beneficio() {
            return new Beneficio(tipo, valor, montoMinimo == null ? BigDecimal.ZERO : montoMinimo, topeDescuento);
        }
    }

    // --- Cupones del catalogo (CCPIP-89) ---

    /** Como esta un cupon del catalogo, como las campanas: antes de su periodo, dentro o despues (o finalizado a mano). */
    public enum EstadoCatalogo { PROGRAMADO, ACTIVO, FINALIZADO }

    /**
     * Un cupon del catalogo, con cuantos se canjearon y cuantos ya se usaron en una compra. Se canjea de
     * {@code disponibleDesde} a {@code disponibleHasta} (nula: sin fin) mientras este {@code activo}.
     */
    public record Cupon(long id, String nombre, String descripcion, int costoPuntos, Tipo tipo, BigDecimal valor,
                        BigDecimal montoMinimo, BigDecimal topeDescuento, int vigenciaDias, boolean activo, int canjes,
                        int usados, BigDecimal descuentoOtorgado, String registradoPor, Instant registradoEn,
                        LocalDate disponibleDesde, LocalDate disponibleHasta, EstadoCatalogo estado) {
    }

    public record NuevoCupon(
            @NotBlank(message = "Ponle un nombre al cupón") @Size(max = 80, message = "Máximo 80 caracteres") String nombre,
            @Size(max = 300, message = "Máximo 300 caracteres") String descripcion,
            @NotNull(message = "Indica cuántos puntos cuesta")
            @Min(value = 1, message = "Cuesta al menos 1 punto") @Max(value = 1000000, message = "Hasta 1 000 000 de puntos")
            Integer costoPuntos,
            @NotNull(message = "Indica el descuento") @jakarta.validation.Valid NuevoBeneficio beneficio,
            @NotNull(message = "Indica cuántos días vale después del canje")
            @Min(value = 1, message = "Vale al menos 1 día") @Max(value = 365, message = "Vale como máximo 365 días") Integer vigenciaDias,
            LocalDate disponibleDesde, LocalDate disponibleHasta) {
    }

    // --- Codigos de descuento de las campanas ---

    /** Un codigo de una campana, con cuantas veces se uso y cuanto desconto. */
    public record Codigo(long id, long campanaId, String codigo, Tipo tipo, BigDecimal valor, BigDecimal montoMinimo,
                         BigDecimal topeDescuento, Integer usosMaximos, int usosPorCliente, boolean activo, int usos,
                         BigDecimal descuentoOtorgado, Instant registradoEn) {
    }

    public record NuevoCodigo(
            @NotBlank(message = "Escribe el código")
            @Pattern(regexp = "[A-Za-z0-9]{4,20}", message = "De 4 a 20 letras o números, sin espacios") String codigo,
            @NotNull(message = "Indica el descuento") @jakarta.validation.Valid NuevoBeneficio beneficio,
            @Min(value = 1, message = "Al menos 1 uso") @Max(value = 1000000, message = "Hasta 1 000 000 de usos") Integer usosMaximos,
            @Min(value = 1, message = "Al menos 1 uso por cliente") @Max(value = 100, message = "Hasta 100 usos por cliente")
            Integer usosPorCliente) {
    }

    /** La seccion de beneficios del sub administrador: niveles y cupones del catalogo. */
    public record PanelBeneficios(List<Nivel> niveles, List<Cupon> cupones) {
    }

    // --- El comprador (CCPIP-173, 374, 88, 89) ---

    /**
     * El nivel del comprador por sus pedidos entregados; {@code actual} nulo si aun no llega al primero.
     * {@code siguiente} y {@code faltan}: el proximo nivel y cuantos pedidos le faltan (nulos si esta en el mas alto).
     */
    public record NivelCliente(int pedidosEntregados, Nivel actual, Nivel siguiente, Integer faltan) {
    }

    public enum TipoMovimiento { ACUMULACION, CANJE }

    /** Un movimiento: puntos ganados por un pedido o gastados en un cupon. {@code referencia}: el pedido o el cupon. */
    public record Movimiento(TipoMovimiento tipo, int puntos, Instant fecha, String referencia, String campana,
                             String nivel) {
    }

    /** DISPONIBLE para usar; EN_USO en un pedido que espera su pago; USADO en una compra; VENCIDO sin usar. */
    public enum EstadoCupon { DISPONIBLE, EN_USO, USADO, VENCIDO }

    /** Un cupon que el comprador canjeo, con el beneficio que tenia al canjearlo. */
    public record MiCupon(long id, String codigo, String nombre, Tipo tipo, BigDecimal valor, BigDecimal montoMinimo,
                          BigDecimal topeDescuento, Instant canjeadoEn, Instant venceEn, EstadoCupon estado,
                          String pedido) {
    }

    /** Un cupon del catalogo que el comprador puede canjear; {@code alcanza}: si le alcanzan los puntos. */
    public record CuponCanjeable(long id, String nombre, String descripcion, int costoPuntos, Tipo tipo, BigDecimal valor,
                                 BigDecimal montoMinimo, BigDecimal topeDescuento, int vigenciaDias, boolean alcanza) {
    }

    /** El modulo de puntos del comprador. */
    public record MisPuntos(long saldo, NivelCliente nivel, List<Nivel> niveles, List<Movimiento> movimientos,
                            List<CuponCanjeable> catalogo, List<MiCupon> cupones) {
    }

    // --- El descuento de una compra ---

    public enum Origen { CUPON, CODIGO }

    /** Lo que el comprador pide aplicar: uno de sus cupones canjeados o un codigo de campana (no ambos). */
    public record SolicitudDescuento(Long cuponId, String codigo) {
        public boolean vacia() {
            return cuponId == null && (codigo == null || codigo.isBlank());
        }
    }

    /** Un descuento que si aplica a la compra. */
    public record DescuentoAplicado(Origen origen, Long cuponId, Long codigoId, String nombre, BigDecimal monto) {
    }
}
