package pe.ccpip.interes;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Interes de compra en una oferta agotada o pausada (CCPIP-105, CCPIP-106, CCPIP-273). */
public final class InteresModelos {

    private InteresModelos() {
    }

    /** Cuantas unidades de venta le interesaria comprar. No reserva ni cobra nada. */
    public record NuevoInteres(
            @NotNull(message = "Indica la cantidad")
            @Min(value = 1, message = "La cantidad mínima es 1")
            @Max(value = 9999, message = "La cantidad máxima es 9999") Integer cantidad) {
    }

    /**
     * El interes pendiente del comprador en una oferta. {@code motivo}: AGOTADO o VENTA_PAUSADA, por que
     * no se podia comprar cuando lo registro.
     */
    public record Interes(long productoId, int cantidad, String motivo, Instant registradoEn, Instant actualizadoEn) {
    }

    /** Como esta hoy una oferta con interesados, para el productor. */
    public enum EstadoOferta { AGOTADO, VENTA_PAUSADA, DISPONIBLE, NO_PUBLICADO }

    /**
     * CCPIP-106: interes pendiente en una oferta del productor. {@code unidad}: codigo de la unidad de
     * venta; {@code cantidad}: suma de lo que pidieron los interesados. Sin datos de los compradores.
     */
    public record InteresOferta(long productoId, String nombre, String unidad, int interesados, int cantidad,
                                EstadoOferta estado, Instant ultimoRegistro) {
    }
}
