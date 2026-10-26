package pe.ccpip.reportes.gestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import pe.ccpip.reportes.ReportesModelos.Desglose;

/**
 * Reportes de gestion del superadministrador (Figma SA-05 y SA-10) por periodo. Solo cifras agregadas: ningun
 * dato de un comprador. Un pedido cuenta si se pago y no se cancelo, por la fecha en que se confirmo el pago
 * (hora de Lima), como en las ventas de la plataforma (CCPIP-345).
 */
public final class GestionModelos {

    private GestionModelos() {
    }

    /**
     * CCPIP-122: lo vendido por un productor en el rango. {@code participacion} es el porcentaje de las ventas
     * de la plataforma, con un decimal.
     */
    public record ProductorVentas(long productorId, String productor, String pueblo, long pedidos, BigDecimal unidades,
                                  BigDecimal b2c, BigDecimal b2b, BigDecimal total, BigDecimal participacion) {
    }

    /**
     * CCPIP-122 (RF-86): productores con mayores ventas en el rango, de mayor a menor (hasta {@code limite}).
     * {@code totales} son las ventas de toda la plataforma y {@code productoresConVentas}, cuantos vendieron.
     */
    public record ReporteProductores(LocalDate desde, LocalDate hasta, Desglose totales, long productoresConVentas,
                                     int limite, List<ProductorVentas> productores) {
    }

    /**
     * CCPIP-355: lo vendido desde un punto de acopio en el rango (los pedidos salen de un punto). Solo cifras
     * agregadas: sin stock editable ni pedidos individuales. {@code participacion} es su porcentaje de las
     * ventas de la plataforma.
     */
    public record PuntoReporte(long puntoId, String punto, String departamento, long pedidos, BigDecimal b2c,
                               BigDecimal b2b, BigDecimal total, BigDecimal participacion) {
    }

    /**
     * CCPIP-355 (RF-245): ventas de cada punto de acopio en el rango, de mayor a menor. Incluye los puntos
     * activos sin ventas, en cero.
     */
    public record ReportePuntos(LocalDate desde, LocalDate hasta, Desglose totales, long pedidos,
                                List<PuntoReporte> puntos) {
    }
}
