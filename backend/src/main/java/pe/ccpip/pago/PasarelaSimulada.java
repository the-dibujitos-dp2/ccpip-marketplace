package pe.ccpip.pago;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Pasarela de pruebas propia (CCPIP-62) para el MVP y las demos: no cobra nada. Responde segun el
 * numero, como las tarjetas de prueba de Culqi o Niubiz:
 *
 * <ul>
 *   <li>4000 0000 0000 0002: rechazada por fondos insuficientes.</li>
 *   <li>4000 0000 0000 0069: rechazada por tarjeta vencida en el banco.</li>
 *   <li>4000 0000 0000 0119: error de la pasarela (se puede reintentar).</li>
 *   <li>Cualquier otra tarjeta valida, por ejemplo 4111 1111 1111 1111: aprobada.</li>
 * </ul>
 */
public class PasarelaSimulada implements PasarelaPago {

    static final Map<String, ResultadoCobro> RESPUESTAS = Map.of(
            "4000000000000002", new ResultadoCobro(Estado.RECHAZADO, null, "FONDOS_INSUFICIENTES",
                    "Tu banco rechazó el pago por fondos insuficientes. Prueba con otra tarjeta."),
            "4000000000000069", new ResultadoCobro(Estado.RECHAZADO, null, "TARJETA_VENCIDA",
                    "Tu banco indica que la tarjeta está vencida. Prueba con otra tarjeta."),
            "4000000000000119", new ResultadoCobro(Estado.ERROR, null, "ERROR_PROCESAMIENTO",
                    "No pudimos procesar el pago. No se hizo ningún cobro; inténtalo otra vez."));

    @Override
    public String nombre() {
        return "SIMULADA";
    }

    @Override
    public ResultadoCobro cobrar(String referencia, BigDecimal monto, Tarjeta tarjeta) {
        ResultadoCobro respuesta = RESPUESTAS.get(tarjeta.digitos());
        if (respuesta != null) {
            return respuesta;
        }
        return new ResultadoCobro(Estado.APROBADO, "SIM-" + UUID.randomUUID(), "APROBADO", "Pago aprobado.");
    }

    @Override
    public void devolver(String referenciaPasarela, BigDecimal monto) {
        // Nada que devolver: la pasarela simulada no cobra
    }
}
