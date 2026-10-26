package pe.ccpip.moderacion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import pe.ccpip.autorizacion.AutorizacionModelos.Evidencia;
import pe.ccpip.autorizacion.AutorizacionModelos.Productor;
import pe.ccpip.imagenes.ImagenService.Imagen;

/**
 * Moderacion de la publicacion de ofertas (CCPIP-46, CCPIP-331, CCPIP-332). La autorizacion de venta del
 * producto (CCPIP-174, CCPIP-284) es otro proceso: aqui solo se muestra, para que la publicacion de una
 * oferta no autorizada no se apruebe.
 */
public final class ModeracionModelos {

    private ModeracionModelos() {
    }

    /** Una solicitud de publicacion: PENDIENTE hasta que el subadministrador la aprueba o la rechaza. */
    public enum Estado { PENDIENTE, APROBADA, RECHAZADA }

    /** Pendientes ahora y decididas en el mes en curso (hora de Lima). */
    public record Contadores(int pendientes, int aprobadasMes, int rechazadasMes) {
    }

    /**
     * Una fila de la bandeja. {@code republicacion}: la oferta ya estuvo publicada antes. {@code autorizacion}:
     * el estado de su autorizacion de venta (SIN_SOLICITUD si no la pidio) y {@code autorizada}, si esta
     * aprobada y vigente hoy.
     */
    public record Fila(long id, Estado estado, long productoId, String producto, String linea, long productorId,
                       String organizacion, String pueblo, String region, boolean republicacion, String autorizacion,
                       boolean autorizada, Instant solicitadaEn, Instant decididaEn) {
    }

    public record Bandeja(Contadores contadores, List<Fila> solicitudes) {
    }

    /** Lo que se publicaria: los datos de la oferta como los ve el comprador, con su stock en venta. */
    public record Oferta(long id, String nombre, String descripcion, List<String> lineas, String unidad, String contenido,
                         String modalidad, BigDecimal precio, BigDecimal precioMayorista, Integer cantidadMinimaMayorista,
                         BigDecimal enVenta, String historiaTitulo, String historia, List<Imagen> imagenes) {
    }

    /**
     * La autorizacion de venta del producto (CCPIP-174): {@code id} nulo y estado SIN_SOLICITUD si no la
     * pidio. Sus evidencias vigentes se abren con la ruta de S-02.
     */
    public record AutorizacionVenta(Long id, String estado, LocalDate vigenteHasta, boolean vigente,
                                    List<Evidencia> evidencias) {
    }

    /** Una solicitud de publicacion de la oferta, para el historial del expediente. */
    public record Solicitud(long id, Estado estado, Instant solicitadaEn, String solicitadaPor, String motivo,
                            String decididaPor, Instant decididaEn) {
    }

    /**
     * CCPIP-46: el expediente de una solicitud. {@code faltan} dice por que no se podria aprobar ahora (sin
     * autorizacion vigente, sin stock, datos incompletos); {@code historial}, todas las solicitudes de la
     * oferta, de la mas reciente a la mas antigua.
     */
    public record Expediente(long id, Estado estado, Instant solicitadaEn, String solicitadaPor, boolean republicacion,
                             Oferta oferta, Productor productor, AutorizacionVenta autorizacion, List<Solicitud> historial,
                             List<String> faltan, boolean puedeDecidir, boolean puedeAprobar) {
    }

    /** Toda decision lleva motivo, que el productor ve (CCPIP-331, CCPIP-332). */
    public record NuevaDecision(
            @NotBlank(message = "Escribe el motivo de la decisión") @Size(max = 500, message = "El motivo admite hasta 500 caracteres") String motivo) {
    }
}
