package pe.ccpip.catalogo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.Publico;
import pe.ccpip.catalogo.CatalogoModelos.Busqueda;
import pe.ccpip.catalogo.CatalogoModelos.Catalogos;
import pe.ccpip.catalogo.CatalogoModelos.Codigo;
import pe.ccpip.catalogo.CatalogoModelos.Facetas;
import pe.ccpip.catalogo.CatalogoModelos.Inicio;
import pe.ccpip.catalogo.CatalogoModelos.LineaCatalogo;
import pe.ccpip.catalogo.CatalogoModelos.ProductoDetalle;
import pe.ccpip.catalogo.CatalogoModelos.TiendaDetalle;
import pe.ccpip.catalogo.CatalogoModelos.TiendaTarjeta;
import pe.ccpip.catalogo.CatalogoRepository.Filtro;
import pe.ccpip.catalogo.CatalogoRepository.Orden;
import pe.ccpip.comision.ComisionService;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.Idioma;

/**
 * Catalogo publico: no requiere sesion (CCPIP-53, CCPIP-154). Responde en el idioma de la cabecera
 * Accept-Language con el contenido aprobado en ese idioma; lo que no esta traducido sale en
 * espanol, el original (CCPIP-166, CCPIP-357, CCPIP-358).
 */
@Publico
@RestController
public class CatalogoController {

    private final CatalogoRepository catalogo;
    private final TiendasRepository tiendas;
    private final JdbcTemplate jdbc;
    private final TraductorCatalogo traductor;
    private final ComisionService comision;

    public CatalogoController(CatalogoRepository catalogo, TiendasRepository tiendas, JdbcTemplate jdbc, TraductorCatalogo traductor,
                              ComisionService comision) {
        this.catalogo = catalogo;
        this.tiendas = tiendas;
        this.jdbc = jdbc;
        this.traductor = traductor;
        this.comision = comision;
    }

    /**
     * CCPIP-52, 54 y 165: busqueda en nombre y descripcion sin distinguir mayusculas ni tildes,
     * con filtros por linea, modalidad, region, comunidad y precio. Por defecto solo disponibles.
     * CCPIP-363: la comunidad debe ser real y con tienda publicada; si no, se rechaza en vez de
     * devolver una lista vacia que parece "sin resultados".
     */
    @GetMapping("/api/productos")
    public Busqueda buscar(
            @RequestParam(name = "q", required = false) @Size(max = 100) String q,
            @RequestParam(name = "linea", required = false) List<String> lineas,
            @RequestParam(required = false) String modalidad,
            @RequestParam(name = "region", required = false) List<String> regiones,
            @RequestParam(required = false) Long tienda,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal precioMin,
            @RequestParam(required = false) @DecimalMin("0") BigDecimal precioMax,
            @RequestParam(defaultValue = "true") boolean disponibles,
            @RequestParam(defaultValue = "RELEVANCIA") String orden,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "12") @Min(1) @Max(48) int tamano,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            HttpServletResponse response) {
        String mod = modalidad == null || modalidad.isBlank() ? null : modalidad.toUpperCase(Locale.ROOT);
        if (mod != null && !List.of("MINORISTA", "MAYORISTA").contains(mod)) {
            throw ErrorNegocio.invalido("MODALIDAD_INVALIDA", "La modalidad debe ser MINORISTA o MAYORISTA.");
        }
        if (tienda != null && !tiendas.publicada(tienda)) {
            throw ErrorNegocio.invalido("COMUNIDAD_INVALIDA", "Esa comunidad no existe o aún no tiene su tienda publicada.");
        }
        Orden ordenElegido;
        try {
            ordenElegido = Orden.valueOf(orden.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ErrorNegocio.invalido("ORDEN_INVALIDO", "Orden desconocido.");
        }
        Filtro f = new Filtro(q == null || q.isBlank() ? null : q.trim(), limpiar(lineas), mod, limpiar(regiones), tienda,
                precioMin, precioMax, disponibles);
        long total = catalogo.contar(f);
        Facetas facetas = new Facetas(catalogo.facetaLineas(f), catalogo.faceta(f, "pr.region", "pr.region"),
                catalogo.facetaModalidades(f),
                catalogo.faceta(f, "pr.id::text", "coalesce(pr.nombre_tienda, pr.comunidad)"));
        Busqueda busqueda = new Busqueda(catalogo.buscar(f, ordenElegido, pagina, tamano), pagina, tamano, total,
                (int) ((total + tamano - 1) / tamano), catalogo.contarComunidades(f), facetas);
        return traductor.busqueda(busqueda, idioma(acceptLanguage, response));
    }

    /** CCPIP-53 y CCPIP-152: ficha publica. Un producto sin stock se ve igual, marcado como agotado. */
    @GetMapping("/api/productos/{id}")
    public ProductoDetalle detalle(@PathVariable long id,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            HttpServletResponse response) {
        ProductoDetalle detalle = catalogo.detalle(id).orElseThrow(() ->
                ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o ya no está disponible"));
        return traductor.detalle(detalle, idioma(acceptLanguage, response));
    }

    /** CCPIP-153: comunidades con tienda publicada. */
    @GetMapping("/api/tiendas")
    public List<TiendaTarjeta> tiendas(@RequestParam(required = false) @Min(1) @Max(100) Integer limite,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            HttpServletResponse response) {
        return traductor.tarjetas(tiendas.listar(limite), idioma(acceptLanguage, response));
    }

    /** CCPIP-154 y CCPIP-56: tienda de la comunidad con su historia, galeria y productos. */
    @GetMapping("/api/tiendas/{id}")
    public TiendaDetalle tienda(@PathVariable long id,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            HttpServletResponse response) {
        TiendaDetalle detalle = tiendas.detalle(id).orElseThrow(() ->
                ErrorNegocio.noEncontrado("TIENDA_NO_ENCONTRADA", "La tienda no existe o aún no está publicada"));
        return traductor.tienda(detalle, idioma(acceptLanguage, response));
    }

    /** CW-01: comunidades, lineas con conteo y productos para descubrir. */
    @GetMapping("/api/inicio")
    public Inicio inicio(@RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
                         HttpServletResponse response) {
        Filtro todos = new Filtro(null, List.of(), null, List.of(), null, null, null, true);
        Inicio inicio = new Inicio(tiendas.listar(3), catalogo.facetaLineas(todos), catalogo.destacados(8), catalogo.novedades(8));
        return traductor.inicio(inicio, idioma(acceptLanguage, response));
    }

    /** Catalogos cerrados para los formularios (RNF-32). */
    @GetMapping("/api/catalogos")
    public Catalogos catalogos(@RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
                               HttpServletResponse response) {
        List<LineaCatalogo> lineas = jdbc.query("""
                SELECT l.codigo, l.nombre, l.requiere_vencimiento, u.codigo AS unidad
                FROM linea_producto l LEFT JOIN unidad_medida u ON u.id = l.unidad_sugerida_id
                WHERE l.activo ORDER BY l.orden, l.nombre""",
                (rs, row) -> new LineaCatalogo(rs.getString("codigo"), rs.getString("nombre"),
                        rs.getBoolean("requiere_vencimiento"), rs.getString("unidad")));
        List<Codigo> unidades = jdbc.query("SELECT codigo, nombre FROM unidad_medida ORDER BY nombre",
                (rs, row) -> new Codigo(rs.getString("codigo"), rs.getString("nombre")));
        return traductor.catalogos(new Catalogos(lineas, unidades, comision.porcentajeVigente()), idioma(acceptLanguage, response));
    }

    /**
     * El idioma de la respuesta segun Accept-Language. Se declara en Content-Language, y Vary avisa a
     * cualquier cache que la misma URL cambia con el idioma.
     */
    private static Idioma idioma(String acceptLanguage, HttpServletResponse response) {
        Idioma idioma = Idioma.deCabecera(acceptLanguage);
        response.setHeader(HttpHeaders.CONTENT_LANGUAGE, idioma.codigo());
        response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);
        return idioma;
    }

    private static List<String> limpiar(List<String> valores) {
        return valores == null ? List.of() : valores.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }
}
