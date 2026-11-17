package pe.ccpip.fidelizacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * CCPIP-88/89: el descuento de un cupon canjeado o de un codigo de campana: S/ fijos o un porcentaje de los
 * productos (con tope opcional), desde un pedido minimo. Es el unico calculo de descuentos.
 */
public record Beneficio(Tipo tipo, BigDecimal valor, BigDecimal montoMinimo, BigDecimal tope) {

    public enum Tipo { MONTO, PORCENTAJE }

    /** Siempre se paga al menos esto de productos: un descuento nunca deja el pedido gratis. */
    static final BigDecimal MINIMO_A_PAGAR = BigDecimal.ONE;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /** Si {@code productos} soles llegan al pedido minimo. */
    public boolean aplica(BigDecimal productos) {
        return productos.compareTo(montoMinimo) >= 0;
    }

    /** El descuento sobre {@code productos} soles (sin envio): cero si no llega al minimo. */
    public BigDecimal descuentoPara(BigDecimal productos) {
        if (!aplica(productos)) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal descuento = tipo == Tipo.MONTO ? valor
                : productos.multiply(valor).divide(CIEN, 2, RoundingMode.HALF_UP);
        if (tope != null) {
            descuento = descuento.min(tope);
        }
        BigDecimal maximo = productos.subtract(MINIMO_A_PAGAR).max(BigDecimal.ZERO);
        return descuento.min(maximo).setScale(2, RoundingMode.HALF_UP);
    }

    /** El beneficio de una fila con las columnas tipo, valor, monto_minimo y tope_descuento. */
    static Beneficio de(ResultSet rs) throws SQLException {
        return new Beneficio(Tipo.valueOf(rs.getString("tipo")), rs.getBigDecimal("valor"), rs.getBigDecimal("monto_minimo"),
                rs.getBigDecimal("tope_descuento"));
    }
}
