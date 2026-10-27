package pe.ccpip.gobernanza;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Acciones de gobernanza (Figma S-05). Cada una ya queda registrada, con autor, fecha y motivo, en la
 * tabla de su modulo (solo insercion o cambio unico de estado); aqui solo se leen y se presentan juntas.
 */
public final class GobernanzaModelos {

    private GobernanzaModelos() {
    }

    /** Filtro "Tipo de accion" de S-05: el modulo donde quedo registrada. */
    public enum Tipo { AUTORIZACIONES, MODERACION, PRODUCTORES, DENUNCIAS, CUENTAS }

    /** A que pantalla lleva "Sobre": la autorizacion, la moderacion, el productor, sus denuncias o la cuenta. */
    public enum Objeto { AUTORIZACION, MODERACION, PRODUCTOR, DENUNCIAS, CUENTA }

    /**
     * Una accion. {@code clave} es unica en la lista (tabla de origen e id). {@code accion} es el codigo
     * que la pantalla traduce, por ejemplo {@code AUTORIZACION_APROBADA}, {@code BLOQUEAR_VENTA} o
     * {@code DENUNCIA_RESUELTA}. {@code sobre} es la organizacion o la persona; {@code detalle}, el
     * producto o el numero de denuncia, si aplica. {@code objetoId} es el id de la pantalla de destino.
     */
    public record Accion(String clave, Instant fecha, Tipo tipo, String accion, String sobre, String detalle,
                         Objeto objeto, long objetoId, String motivo) {
    }

    /** CCPIP-126: el historial de un periodo (fechas de Lima, inclusive), de la mas reciente a la mas antigua. */
    public record Historial(LocalDate desde, LocalDate hasta, Tipo tipo, List<Accion> acciones) {
    }
}
