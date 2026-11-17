package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cuantos puntos da un pedido: {@code puntos} por cada {@code cadaSoles} completos de sus productos (sin envio),
 * hasta {@code tope} si lo hay. Es el unico calculo de puntos: lo usan la acumulacion, las proyecciones y las
 * metricas de las campanas.
 *
 * <p>CCPIP-86 (decision del 13/11): la regla base es 1 punto por cada S/ 10, redondeado hacia abajo
 * (S/ 85,50 da 8 puntos; S/ 9,90, ninguno).
 */
public record ReglaPuntos(int puntos, BigDecimal cadaSoles, Integer tope) {

    public static final ReglaPuntos BASE = new ReglaPuntos(1, BigDecimal.TEN, null);

    /** Los puntos de un pedido de {@code monto} soles en productos. */
    public int puntosDe(BigDecimal monto) {
        return puntosDe(monto, BigDecimal.ONE);
    }

    /**
     * CCPIP-88: los puntos con el multiplicador del nivel del comprador, redondeados hacia abajo. El tope de la
     * campana limita el resultado final.
     */
    public int puntosDe(BigDecimal monto, BigDecimal multiplicador) {
        if (monto == null || monto.signum() <= 0) {
            return 0;
        }
        long veces = monto.divideToIntegralValue(cadaSoles).longValue();
        long total = BigDecimal.valueOf(veces * puntos).multiply(multiplicador).setScale(0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(Integer.MAX_VALUE)).longValue();
        return (int) (tope == null ? total : Math.min(total, tope));
    }

    /** Puntos por cada sol, para comparar una campana con la regla base. */
    public BigDecimal porSol() {
        return BigDecimal.valueOf(puntos).divide(cadaSoles, 6, RoundingMode.HALF_UP);
    }
}
