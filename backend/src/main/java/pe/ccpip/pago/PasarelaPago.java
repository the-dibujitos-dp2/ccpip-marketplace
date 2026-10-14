package pe.ccpip.pago;

import java.math.BigDecimal;

/**
 * Pasarela de pagos (CCPIP-62). Hoy hay una simulada; Culqi o Niubiz en modo pruebas se agregan como
 * otra implementacion y se eligen con {@code ccpip.pagos.pasarela} (ver {@link PagosConfig}), sin tocar
 * el flujo de compra. Sus llaves iran en variables de entorno del servidor, nunca en el navegador.
 *
 * <p>La tarjeta llega solo para cobrar y no se guarda en ninguna parte (RNF del criterio de CCPIP-62).
 * Con una pasarela real lo ideal es que el navegador la tokenice y aqui llegue solo el token.
 */
public interface PasarelaPago {

    /** Nombre que queda en {@code pago.pasarela}, por ejemplo SIMULADA, CULQI o NIUBIZ. */
    String nombre();

    /**
     * Intenta cobrar. No lanza excepciones por un rechazo: lo devuelve como resultado. Si la pasarela
     * no responde, lanza {@link PasarelaNoDisponible} y el intento queda en ERROR.
     *
     * @param referencia identificador del cobro para la pasarela; el numero del pedido
     */
    ResultadoCobro cobrar(String referencia, BigDecimal monto, Tarjeta tarjeta);

    /** Devuelve un cobro aprobado, cuando el stock ya no alcanzo para confirmar el pedido. */
    void devolver(String referenciaPasarela, BigDecimal monto);

    enum Estado {
        APROBADO, RECHAZADO, ERROR
    }

    /**
     * Respuesta de la pasarela. {@code referencia} es su identificador de la transaccion (obligatorio
     * si se aprobo); {@code codigo} y {@code mensaje} explican un rechazo o un error al comprador.
     */
    record ResultadoCobro(Estado estado, String referencia, String codigo, String mensaje) {
    }

    /** La pasarela no respondio o fallo de su lado: el intento queda en ERROR y se puede reintentar. */
    class PasarelaNoDisponible extends RuntimeException {
        public PasarelaNoDisponible(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
