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
     *
     * <p>CCPIP-356: {@code denuncias} recibidas en el rango sobre ofertas que estan en el punto (el producto
     * denunciado, o cualquier producto del productor si la denuncia no nombra uno, tiene lotes ahi);
     * {@code denunciasAbiertas}, las que aun no se resuelven (recibidas o en revision).
     */
    public record PuntoReporte(long puntoId, String punto, String departamento, long pedidos, BigDecimal b2c,
                               BigDecimal b2b, BigDecimal total, BigDecimal participacion, long denuncias,
                               long denunciasAbiertas) {
    }

    /** CCPIP-356: denuncias de la plataforma recibidas en el rango, por estado. */
    public record Denuncias(long total, long abiertas, long resueltas, long desestimadas) {
    }

    /**
     * CCPIP-355 (RF-245) y CCPIP-356 (RF-246): ventas y denuncias de cada punto de acopio en el rango, de mayor a
     * menor venta. Incluye los puntos activos sin ventas, en cero. {@code denuncias} son las de toda la
     * plataforma: una denuncia suma en cada punto donde esta la oferta, asi que los puntos pueden sumar mas.
     */
    public record ReportePuntos(LocalDate desde, LocalDate hasta, Desglose totales, long pedidos, Denuncias denuncias,
                                List<PuntoReporte> puntos) {
    }

    /** CCPIP-123: como cambio el promedio frente al periodo anterior de igual duracion. */
    public enum Tendencia { SUBE, ESTABLE, BAJA }

    /** Una linea de producto, opcion del filtro. */
    public record Linea(String codigo, String nombre) {
    }

    /** CCPIP-123: cuantas calificaciones de {@code estrellas} hubo. */
    public record Estrellas(int estrellas, long cantidad) {
    }

    /**
     * CCPIP-123: calificaciones del rango. {@code promedio} (un decimal) es nulo si no se alcanza el minimo de
     * calificaciones configurado (CCPIP-82); {@code distribucion} va de 5 a 1 estrellas.
     */
    public record ResumenCalificaciones(BigDecimal promedio, long resenas, List<Estrellas> distribucion) {
    }

    /**
     * CCPIP-123: un producto calificado en el rango. {@code promedio} es nulo bajo el minimo;
     * {@code denuncias}, las recibidas en el rango sobre el producto; {@code tendencia}, nula si alguno de los
     * dos periodos no alcanza el minimo.
     */
    public record ProductoCalificado(long productoId, String producto, String productor, BigDecimal promedio,
                                     long resenas, long denuncias, Tendencia tendencia) {
    }

    /**
     * CCPIP-123 (RF-87): reporte de calificaciones de productos. {@code anteriorDesde} y {@code anteriorHasta}
     * son el periodo anterior de igual duracion con el que se calcula la tendencia; {@code minimo}, las
     * calificaciones necesarias para mostrar un promedio; {@code linea}, el filtro aplicado (nulo si ninguno).
     */
    public record ReporteCalificaciones(LocalDate desde, LocalDate hasta, LocalDate anteriorDesde, LocalDate anteriorHasta,
                                        int minimo, String linea, ResumenCalificaciones resumen,
                                        List<ProductoCalificado> productos, List<Linea> lineas) {
    }
}
