package pe.ccpip.autorizacion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** CCPIP-43: premios de un producto y su lote. */
public final class PremiosModelos {

    private PremiosModelos() {
    }

    /** Un lote del producto, para elegir a cual corresponde el premio. El codigo solo lo ve su productor. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoteOpcion(long id, String codigo, LocalDate produccion, LocalDate vencimiento) {
    }

    /**
     * Un premio, como lo ve su productor. {@code vigente}: hoy (Lima) no paso su vigencia; un premio sin
     * vencimiento siempre lo esta. {@code url}: su archivo, solo con sesion (RNF-08).
     */
    public record Premio(long id, long productoId, LoteOpcion lote, String titulo, String emisor, LocalDate otorgadoEn,
                         LocalDate vigenteHasta, boolean vigente, String nombreArchivo, String formato, int bytes,
                         Instant registradoEn, String url) {
    }

    /** Los premios vigentes (sin retirar) de un producto propio y sus lotes para registrar otro. */
    public record PremiosProducto(long productoId, String producto, List<Premio> premios, List<LoteOpcion> lotes,
                                  boolean puedeRegistrar, int maximo) {
    }

    public record NuevoPremio(String titulo, String emisor, LocalDate otorgadoEn, LocalDate vigenteHasta, Long loteId) {
    }
}
