package pe.ccpip.entrega;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Respuestas de los pedidos del punto de acopio (Figma A-05) y la entrega a domicilio (CCPIP-59). */
public final class EntregaModelos {

    private EntregaModelos() {
    }

    /** Nombres de personas: letras (con tildes), espacios, apostrofos, puntos y guiones. */
    public static final String NOMBRE = "^\\p{L}[\\p{L}\\s'’.-]*$";

    /** CCPIP-59: un distrito del catalogo territorial, con su codigo de ubigeo. */
    public record Distrito(String ubigeo, String departamento, String provincia, String distrito) {
    }

    /** CCPIP-59: un punto de acopio que cubre un distrito con cobertura activa. */
    public record Cobertura(Distrito distrito, long puntoId) {
    }

    /**
     * CCPIP-59: a donde y a quien se entrega un pedido a domicilio, tal como lo escribe el comprador.
     * {@code ubigeo} es el distrito de destino, que el punto de origen debe cubrir.
     */
    public record Destino(@NotBlank(message = "Elige el distrito de entrega")
                          @Pattern(regexp = "[0-9]{6}", message = "El distrito de entrega no es válido") String ubigeo,
                          @NotBlank(message = "Indica la dirección")
                          @Size(max = 255, message = "La dirección es demasiado larga") String direccion,
                          @Size(max = 255, message = "La referencia es demasiado larga") String referencia,
                          @NotBlank(message = "Indica quién recibe")
                          @Size(max = 160, message = "El nombre es demasiado largo")
                          @Pattern(regexp = NOMBRE, message = "Usa solo letras en el nombre de quien recibe") String contactoNombre,
                          @NotBlank(message = "Indica un celular de contacto")
                          @Size(max = 20, message = "El celular es demasiado largo") String contactoCelular) {
    }

    /**
     * CCPIP-59: la entrega a domicilio de un pedido ENVIO, con el distrito ya resuelto. {@code estado}:
     * DISPONIBLE hasta que un distribuidor la asuma. No expone al distribuidor.
     */
    public record Entrega(String ubigeo, String departamento, String provincia, String distrito, String direccion,
                          String referencia, String contactoNombre, String contactoCelular, String estado) {
    }

    /**
     * Un lote del que sale parte de una linea (CCPIP-74): el que vence antes se asigna primero y uno
     * vencido nunca (RNF-14). {@code fechaVencimiento} es nula en lo que no vence, como una artesania.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoteAsignado(String codigo, BigDecimal cantidad, LocalDate fechaVencimiento) {
    }

    /**
     * Lo que se entrega de una linea: producto, comunidad que lo vende y cantidad. {@code lotes} son
     * los lotes asignados al confirmar la compra, mas proximo a vencer primero; esta vacia mientras
     * el pedido no descuenta existencias.
     */
    public record Linea(String producto, String comunidad, BigDecimal cantidad, List<LoteAsignado> lotes) {
    }

    /**
     * Un cambio de estado del pedido. {@code responsable} es quien lo hizo en el punto; es nulo
     * cuando lo registro el comprador (la confirmacion), cuyos datos no se muestran al punto (RNF-05).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cambio(String estado, Instant registradoEn, String responsable) {
    }

    /**
     * Un pedido para recoger en el punto, sin datos del comprador. {@code recogidoPor} es quien se lo
     * llevo (CCPIP-71): el comprador o la persona autorizada; nulo mientras no se entrega.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PedidoPunto(String numero, String estado, Instant confirmadoEn, Instant actualizadoEn,
                              BigDecimal total, List<Linea> lineas, List<Cambio> historial, String recogidoPor) {
    }

    /** {@code items}: confirmados, listos para recojo y entregados hoy, en ese orden de columnas. */
    public record PedidosPunto(String ubicacion, List<PedidoPunto> items) {
    }
}
