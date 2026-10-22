package pe.ccpip.reportes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Reportes de la plataforma para el superadministrador (Figma SA-05). Cifras agregadas: nunca datos de
 * un comprador ni el detalle comercial de un productor (SA-08). Cada importe se separa en B2C (lineas
 * vendidas a tarifa minorista) y B2B (a tarifa mayorista), segun {@code item_pedido.tarifa}.
 */
public final class ReportesModelos {

    private ReportesModelos() {
    }

    /** Como se agrupan los periodos. La semana empieza el lunes (ISO 8601). */
    public enum Agrupacion { DIA, SEMANA, MES }

    /** Un importe separado en B2C y B2B; {@code total} es su suma exacta. */
    public record Desglose(BigDecimal b2c, BigDecimal b2b, BigDecimal total) {
    }

    /**
     * Un periodo de la serie, recortado al rango pedido: {@code inicio} y {@code fin} son fechas de Lima,
     * inclusive. {@code acumulado} es el total desde el inicio del rango hasta el fin de este periodo.
     */
    public record Periodo(LocalDate inicio, LocalDate fin, BigDecimal b2c, BigDecimal b2b, BigDecimal total,
                          BigDecimal acumulado) {
    }

    /**
     * Una metrica acumulada entre {@code desde} y {@code hasta}. La serie incluye los periodos sin
     * movimiento, en cero, para que el acumulado se lea sin huecos. {@code totales} es la suma de la serie.
     */
    public record Reporte(String metrica, LocalDate desde, LocalDate hasta, Agrupacion agrupacion, Desglose totales,
                          List<Periodo> periodos) {
    }
}
