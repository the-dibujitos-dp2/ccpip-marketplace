package pe.ccpip.catalogo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import pe.ccpip.video.VideosService.VideoPublico;

/**
 * Respuestas publicas del catalogo (CW-01 a CW-04). Nunca exponen codigos de lote ni
 * cantidades de stock: solo si hay o no disponibilidad (CCPIP-53, CCPIP-41).
 */
public final class CatalogoModelos {

    private CatalogoModelos() {
    }

    public record Codigo(String codigo, String nombre) {
    }

    public record TiendaResumen(long id, String nombre, String pueblo, String comunidad, String region, String localidad,
                                boolean origenVerificado) {
    }

    /** {@code lineas}: todas las lineas del producto (CCPIP-45); {@code linea} es la principal. */
    public record ProductoResumen(long id, String nombre, String modalidad, BigDecimal precio, BigDecimal precioMayorista,
                                  String contenido, Codigo unidad, Codigo linea, List<String> lineas, String imagen,
                                  boolean disponible, TiendaResumen tienda) {
    }

    public record Faceta(String codigo, String nombre, long total) {
    }

    public record Facetas(List<Faceta> lineas, List<Faceta> regiones, List<Faceta> modalidades, List<Faceta> comunidades) {
    }

    public record Busqueda(List<ProductoResumen> items, int pagina, int tamano, long total, int totalPaginas,
                           long totalComunidades, Facetas facetas) {
    }

    public record Imagen(String id, String url, String miniatura) {
    }

    /** Las cuatro respuestas de la historia, presentadas en CW-03 como "Del bosque a tu mesa". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PartesHistoria(String comunidad, String cultivo, String proceso, String significado) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoriaPublica(String titulo, String texto, boolean asistida, PartesHistoria partes) {
    }

    /** Datos de la cosecha que se venden hoy, sin el codigo tecnico del lote. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cosecha(LocalDate produccion, LocalDate consumirAntesDe) {
    }

    /**
     * {@code comprable} es falso cuando el productor no tiene cuenta bancaria activa o autorizacion
     * vigente para el producto (CCPIP-269): la ficha se ve igual, pero en "Venta pausada" (CW-03c).
     * {@code materiaPrima}: el productor la marco como materia prima, no producto terminado.
     * {@code relacionados}: de su misma linea y su misma clase (materia prima o terminado).
     * {@code porMateriaPrima} (CCPIP-55): de la otra clase y con alguna linea en comun; para un producto
     * terminado, las materias primas de su linea, y para una materia prima, lo terminado de su linea.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductoDetalle(long id, String nombre, String descripcion, String contenido, Codigo unidad,
                                  Codigo linea, List<Codigo> lineas, String modalidad, BigDecimal precio,
                                  BigDecimal precioMayorista, Integer cantidadMinimaMayorista, boolean disponible,
                                  boolean comprable, List<Imagen> imagenes, TiendaResumen tienda, HistoriaPublica historia, Cosecha cosecha,
                                  boolean materiaPrima, List<ProductoResumen> relacionados, List<ProductoResumen> porMateriaPrima,
                                  Map<String, CampoTraducido> traducciones, ProductoMaestro maestro) {
    }

    /**
     * CCPIP-171 (RF-163) y CCPIP-373: que textos de la ficha o de la tienda llegan traducidos y de
     * donde salieron, por nombre de campo ("descripcion", "historia.texto", "cita"...). Solo en otro
     * idioma que el original.
     * - {@code origen}: COMUNIDAD (una persona) o AUTOMATICA (CCPIP-147), para etiquetarla.
     * - {@code vigente}: falso si el original cambio despues de traducirlo; entonces el texto servido
     *   ya es el original y la pantalla avisa que la traduccion esta desactualizada.
     * - {@code original}: el texto en su idioma original, para "Ver original". Solo si es vigente.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CampoTraducido(String origen, boolean vigente, String original) {
    }

    /**
     * CCPIP-42 (RF-18): una oferta visible de un producto del catalogo maestro. Cada una conserva su
     * productor, historia, precio y disponibilidad propios.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Oferta(long id, String nombre, String modalidad, BigDecimal precio, BigDecimal precioMayorista,
                         String contenido, Codigo unidad, String imagen, boolean disponible, boolean comprable,
                         TiendaResumen tienda, HistoriaPublica historia) {
    }

    /** CCPIP-42: el producto generico de la Camara con las ofertas de cada productor, lo disponible primero. */
    public record ProductoMaestro(long id, String codigo, String nombre, String descripcion, Codigo linea,
                                  List<Oferta> ofertas) {
    }

    public record TiendaTarjeta(long id, String nombre, String pueblo, String comunidad, String region, String localidad,
                                String cita, boolean origenVerificado, String portada, long productos) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TiendaDetalle(long id, String nombre, String descripcion, String historia, String cita, String pueblo,
                                String comunidad, String region, String localidad, Integer socios, boolean origenVerificado,
                                Integer desde, String portada, List<GaleriaItem> galeria, List<VideoPublico> videos,
                                List<Faceta> lineas, List<ProductoResumen> productos, List<Long> destacados,
                                Map<String, CampoTraducido> traducciones) {
    }

    public record GaleriaItem(String id, String categoria, String url, String miniatura) {
    }

    /** CW-01. Las novedades y los mas vendidos se piden aparte, por periodo (CCPIP-338, CCPIP-339). */
    public record Inicio(List<TiendaTarjeta> tiendas, List<Faceta> lineas, List<ProductoResumen> destacados) {
    }

    public record LineaCatalogo(String codigo, String nombre, boolean requiereVencimiento, String unidadSugerida) {
    }

    /** {@code comisionPorcentaje}: comision vigente de la Camara para la vista previa de PW-04 (CCPIP-90); nula si no hay ninguna configurada. */
    public record Catalogos(List<LineaCatalogo> lineas, List<Codigo> unidades, BigDecimal comisionPorcentaje) {
    }

    /**
     * CCPIP-339 (RF-229) y CCPIP-338 (RF-228): periodos que el visitante puede elegir para ver los mas
     * vendidos y lo publicado recientemente. Todos terminan hoy (fecha de Lima).
     */
    public enum PeriodoVentas { ULTIMOS_30_DIAS, ULTIMOS_3_MESES, ESTE_ANIO }

    /**
     * CCPIP-339: los productos mas vendidos del periodo, en orden de puesto (el primero es el mas vendido),
     * cada uno con su productor. Solo el puesto: ni unidades ni montos, que son datos comerciales del
     * productor (SA-08). Si nada se vendio en el periodo la lista va vacia; no se rellena.
     */
    public record MasVendidos(PeriodoVentas periodo, LocalDate desde, LocalDate hasta, List<ProductoResumen> productos) {
    }

    /**
     * CCPIP-338: los productos publicados en el periodo y en venta, del mas reciente al mas antiguo, cada uno
     * con su productor. Si nada se publico en el periodo la lista va vacia; no se rellena con lo anterior.
     */
    public record Novedades(PeriodoVentas periodo, LocalDate desde, LocalDate hasta, List<ProductoResumen> productos) {
    }
}
