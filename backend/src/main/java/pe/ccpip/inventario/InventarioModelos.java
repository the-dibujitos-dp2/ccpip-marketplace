package pe.ccpip.inventario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Respuestas de inventario del punto de acopio (Figma A-03). */
public final class InventarioModelos {

    private InventarioModelos() {
    }

    /** Pestanas de A-03: cada una agrupa tipos de movimiento. */
    public enum Grupo {
        ENTRADAS(List.of("ENTRADA", "TRANSFERENCIA_ENTRADA")),
        SALIDAS(List.of("SALIDA_VENTA", "TRANSFERENCIA_SALIDA")),
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
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Movimiento(long id, Instant registradoEn, String tipo, long productoId, String producto, String lote,
                             BigDecimal cantidad, String pedido, String registradoPor, String observacion) {
    }

    public record Movimientos(String ubicacion, List<Movimiento> items, int pagina, int tamano, long total,
                              int totalPaginas) {
    }
}
