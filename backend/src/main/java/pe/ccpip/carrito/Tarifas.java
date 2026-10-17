package pe.ccpip.carrito;

import java.math.BigDecimal;

/**
 * CCPIP-134 (RF-108): la tarifa que corresponde a la modalidad de la oferta y a la cantidad pedida.
 * Se resuelve siempre en el servidor, en cada consulta del carrito, y la compra copia lo que sale de
 * aqui (RNF-13). Es una funcion pura para poder probar la regla sin base de datos.
 *
 * <ul>
 * <li>MINORISTA: precio minorista.</li>
 * <li>MAYORISTA: solo desde el minimo, a precio mayorista; bajo el minimo no se puede comprar.</li>
 * <li>AMBAS: desde el minimo, el mayorista automaticamente; bajo el minimo, el minorista.</li>
 * </ul>
 * Nunca se aplica el mayorista bajo el minimo (Figma "DS · Acuerdos 30/09").
 */
public final class Tarifas {

    private Tarifas() {
    }

    public enum Tarifa { MINORISTA, MAYORISTA }

    /**
     * {@code precio} es el que se aplica a esa cantidad. Si no es {@code comprable}, la oferta es solo
     * por mayor y no llega al minimo: {@code precio} es el mayorista, el unico al que se venderia.
     * {@code faltante} son las unidades que faltan para el minimo mayorista (cero si ya se llego), y es
     * nulo si la oferta no tiene tarifa mayorista. Sin precio aplicable (dato incompleto) todo es nulo.
     */
    public record Resolucion(Tarifa tarifa, BigDecimal precio, boolean comprable, BigDecimal faltante) {
    }

    private static final Resolucion SIN_PRECIO = new Resolucion(null, null, false, null);

    public static Resolucion resolver(String modalidad, BigDecimal minorista, BigDecimal mayorista, Integer minimo,
                                      BigDecimal cantidad) {
        // AMBAS sin precio minorista se comporta como solo mayorista, y viceversa
        boolean vendeMinorista = !"MAYORISTA".equals(modalidad) && minorista != null;
        boolean vendeMayorista = !"MINORISTA".equals(modalidad) && mayorista != null && minimo != null;
        if (!vendeMayorista) {
            return vendeMinorista ? new Resolucion(Tarifa.MINORISTA, minorista, true, null) : SIN_PRECIO;
        }
        BigDecimal faltante = BigDecimal.valueOf(minimo).subtract(cantidad).max(BigDecimal.ZERO);
        if (faltante.signum() == 0) {
            return new Resolucion(Tarifa.MAYORISTA, mayorista, true, BigDecimal.ZERO);
        }
        return vendeMinorista
                ? new Resolucion(Tarifa.MINORISTA, minorista, true, faltante)
                : new Resolucion(Tarifa.MAYORISTA, mayorista, false, faltante);
    }
}
