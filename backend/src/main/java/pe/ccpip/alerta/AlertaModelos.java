package pe.ccpip.alerta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Alertas del productor (Figma PW-21) y del punto de acopio (Figma A-08): CCPIP-145, 79, 337, 293,
 * 366 y 367.
 */
public final class AlertaModelos {

    private AlertaModelos() {
    }

    /** El motivo de la alerta. Las importantes salen tambien por correo si la persona lo pidio. */
    public enum Tipo {
        /** CCPIP-79: un lote con saldo vence dentro de la anticipacion configurada. */
        POR_VENCER(true, true),
        /** CCPIP-337: un lote con saldo en un punto no tiene salidas hace N dias. */
        ESTANCADO(true, true),
        /** CCPIP-293: una oferta publicada se quedo sin stock en venta. */
        REPOSICION(true, false),
        /** CCPIP-366: compradores registraron interes en una oferta. */
        INTERES(true, false),
        /** CCPIP-367: un punto recibio una transferencia del productor. */
        TRANSFERENCIA_RECIBIDA(false, false),
        /** CCPIP-367: un pedido confirmado incluye productos del productor. */
        PEDIDO_NUEVO(false, false);

        private final boolean importante;
        private final boolean delPunto;

        Tipo(boolean importante, boolean delPunto) {
            this.importante = importante;
            this.delPunto = delPunto;
        }

        public boolean importante() {
            return importante;
        }

        public static Set<Tipo> delProductor() {
            return EnumSet.allOf(Tipo.class);
        }

        public static Set<Tipo> delPunto() {
            EnumSet<Tipo> tipos = EnumSet.noneOf(Tipo.class);
            for (Tipo t : values()) {
                if (t.delPunto) {
                    tipos.add(t);
                }
            }
            return tipos;
        }
    }

    /**
     * La configuracion vigente. {@code provisional} es verdadero mientras no la guarde: rigen los dias
     * de application.properties. {@code disponibles} son los tipos que su rol puede recibir.
     */
    public record Preferencias(int diasVencimiento, int diasEstancado, Set<Tipo> tipos, boolean correo,
                               boolean provisional, Set<Tipo> disponibles) {
    }

    /** Lo que guarda "Configurar alertas". {@code tipos} son los que quiere recibir. */
    public record NuevasPreferencias(@NotNull @Min(1) @Max(365) Integer diasVencimiento,
                                     @NotNull @Min(1) @Max(365) Integer diasEstancado,
                                     @NotNull Set<Tipo> tipos,
                                     boolean correo) {
    }

    /** La configuracion de uno de los puntos del administrador (A-08). */
    public record PreferenciasPunto(long ubicacionId, String punto, Preferencias preferencias) {
    }

    /**
     * Una alerta de la bandeja. Los datos que la explican dependen del tipo: el lote y su punto
     * (POR_VENCER, ESTANCADO), las personas y unidades de interes (INTERES), el codigo de la
     * transferencia o el numero del pedido. {@code fecha} es el vencimiento, la ultima salida, el dia
     * en que se agoto, el ultimo interes, la recepcion o la confirmacion.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Alerta(long id, Tipo tipo, LocalDate fecha, OffsetDateTime creadaEn, boolean leida, boolean resuelta,
                         boolean porCorreo, Long productoId, String producto, String lote, String ubicacion,
                         BigDecimal cantidad, Integer personas, String referencia, List<String> productos) {
    }

    /** La bandeja: las alertas de los ultimos dias, las mas nuevas primero, y cuantas no leyo. */
    public record Bandeja(List<Alerta> alertas, long sinLeer) {
    }
}
