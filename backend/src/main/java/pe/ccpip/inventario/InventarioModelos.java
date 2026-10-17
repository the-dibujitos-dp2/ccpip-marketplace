package pe.ccpip.inventario;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Inventario del punto de acopio (Figma A-02 y A-03) y movimientos manuales de entrada y salida
 * (CCPIP-121, CCPIP-287, CCPIP-283).
 */
public final class InventarioModelos {

    private InventarioModelos() {
    }

    /** Pestanas de A-03: cada una agrupa tipos de movimiento. */
    public enum Grupo {
        ENTRADAS(List.of("ENTRADA", "TRANSFERENCIA_ENTRADA")),
        SALIDAS(List.of("SALIDA", "SALIDA_VENTA", "TRANSFERENCIA_SALIDA")),
        AJUSTES(List.of("AJUSTE"));

        private final List<String> tipos;

        Grupo(List<String> tipos) {
            this.tipos = tipos;
        }

        public List<String> tipos() {
            return tipos;
        }
    }

    /**
     * Un movimiento de existencias del punto. {@code cantidad} es siempre positiva: el signo lo da el
     * tipo. {@code pedido} es el numero del pedido en una venta. {@code registradoPor} es nulo en una
     * venta: la registra el comprador al confirmar, y sus datos no se muestran al punto (RNF-05).
     * {@code motivo} solo en una entrada o salida manual (CCPIP-121, CCPIP-287).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Movimiento(long id, Instant registradoEn, String tipo, long productoId, String producto, String lote,
                             BigDecimal cantidad, String pedido, String registradoPor, String observacion, String motivo) {
    }

    public record Movimientos(String ubicacion, List<Movimiento> items, int pagina, int tamano, long total,
                              int totalPaginas) {
    }

    // ---------- Movimientos manuales (CCPIP-121, CCPIP-287, CCPIP-283) ----------

    /** Donde se registra el movimiento: define quien puede y con que motivos. */
    public enum Lugar { PUNTO_ACOPIO, ALMACEN_PRODUCTOR }

    /**
     * Catalogo cerrado de motivos, cada uno con su tipo y su lugar. No hay ajuste de conteo (el
     * CHANGELOG del Figma del 30/09 lo quito del punto) ni merma, que tiene su propia historia (A-04).
     */
    public enum Motivo {
        /** El punto recibe mercaderia del productor en un lote suyo (RF-59). */
        RECEPCION("ENTRADA", Lugar.PUNTO_ACOPIO),
        /** El punto devuelve al productor parte de un lote (RF-208). */
        DEVOLUCION_PRODUCTOR("SALIDA", Lugar.PUNTO_ACOPIO),
        /** El productor suma a un lote de su almacen lo que cosecho o elaboro (RF-203). */
        PRODUCCION("ENTRADA", Lugar.ALMACEN_PRODUCTOR),
        /** El productor saca de su almacen para uso propio o una venta fuera de la plataforma (RF-203). */
        RETIRO_PRODUCTOR("SALIDA", Lugar.ALMACEN_PRODUCTOR);

        private final String tipo;
        private final Lugar lugar;

        Motivo(String tipo, Lugar lugar) {
            this.tipo = tipo;
            this.lugar = lugar;
        }

        public String tipo() {
            return tipo;
        }

        public Lugar lugar() {
            return lugar;
        }
    }

    /**
     * Lo que envia el formulario. {@code tipo}: ENTRADA o SALIDA. {@code clave}: un UUID por envio del
     * formulario; si llega dos veces, el movimiento se registra una sola vez (RNF-15).
     */
    public record DatosMovimiento(
            @NotBlank(message = "Elige si es entrada o salida") String tipo,
            @NotNull(message = "Elige el lote") Long loteId,
            @NotNull(message = "Escribe la cantidad")
            @DecimalMin(value = "0.001", message = "La cantidad debe ser mayor a cero")
            @Digits(integer = 9, fraction = 3, message = "Usa hasta 3 decimales") BigDecimal cantidad,
            @NotBlank(message = "Elige el motivo") String motivo,
            @Size(max = 255, message = "Máximo 255 caracteres") String observacion,
            @NotNull(message = "Falta la clave del envío") UUID clave) {
    }

    /**
     * El movimiento registrado y el saldo del lote despues de el. {@code repetido}: la clave ya se
     * habia usado y no se movio nada nuevo.
     */
    public record MovimientoRegistrado(Movimiento movimiento, BigDecimal saldoLote, boolean repetido) {
    }

    // ---------- Inventario del punto, A-02 (CCPIP-162, RF-131) ----------

    /** Estado de un lote del punto. Solo DISPONIBLE y POR_VENCER se pueden vender. */
    public enum EstadoLote { DISPONIBLE, POR_VENCER, VENCIDO, AGOTADO }

    /** Filtro "Estado del lote" de A-02. ACTIVOS: con saldo. */
    public enum FiltroEstado { ACTIVOS, AGOTADOS, TODOS }

    /**
     * Una fila de A-02: un lote de un punto del administrador. {@code entradas} y {@code salidas}
     * suman sus movimientos en ese punto; {@code stock} es el saldo del lote.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LotePunto(long id, String codigo, long productoId, String producto, long productorId, String productor,
                            long ubicacionId, String ubicacion, LocalDate fechaVencimiento, BigDecimal entradas,
                            BigDecimal salidas, BigDecimal stock, EstadoLote estado, boolean vendible) {
    }

    public record Productor(long id, String nombre) {
    }

    /**
     * Totales de los lotes filtrados, sumados una vez por lote (sin duplicar unidades). {@code vendible}
     * excluye lo vencido; lo que esta en transito o es merma nunca llega a un lote del punto.
     */
    public record ResumenPunto(BigDecimal stock, BigDecimal vendible, BigDecimal noVendible, long lotes) {
    }

    public record InventarioPunto(String ubicacion, int diasAviso, ResumenPunto resumen, List<Productor> productores,
                                  List<LotePunto> items, int pagina, int tamano, long total, int totalPaginas) {
    }

    /** Un lote que el formulario de A-02 ofrece para registrar un movimiento, con o sin saldo. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoteOpcion(long id, String codigo, long productoId, String producto, long productorId, String productor,
                             String ubicacion, BigDecimal stock, LocalDate fechaVencimiento, boolean vencido) {
    }
}
