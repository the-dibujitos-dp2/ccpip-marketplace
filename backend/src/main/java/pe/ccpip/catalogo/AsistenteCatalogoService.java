package pe.ccpip.catalogo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;

import pe.ccpip.catalogo.CatalogoModelos.Busqueda;
import pe.ccpip.catalogo.CatalogoModelos.Facetas;
import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;
import pe.ccpip.catalogo.CatalogoRepository.Filtro;
import pe.ccpip.catalogo.CatalogoRepository.Orden;
import pe.ccpip.comun.Idioma;
import pe.ccpip.ia.AsistenteCatalogo;
import pe.ccpip.ia.AsistenteCatalogo.Candidato;
import pe.ccpip.ia.AsistenteCatalogo.Interpretacion;
import pe.ccpip.ia.AsistenteCatalogo.Opcion;

/**
 * CCPIP-51 y CCPIP-175 (RF-75, RF-167): responde preguntas sobre el catalogo con productos reales.
 * <ul>
 *   <li>La busqueda la hace siempre el catalogo, con los mismos criterios publicos de CW-02: la IA
 *       solo traduce la pregunta a filtros y redacta con lo encontrado.</li>
 *   <li>Sin coincidencias se dice que no hay informacion, sin preguntar a la IA (no puede inventar).</li>
 *   <li>Los productos se devuelven en el orden de relevancia de la busqueda, no en el que elija la
 *       IA, para no favorecer a un productor.</li>
 *   <li>Si la IA falla responde 503 y la pantalla lleva a la busqueda normal, que no depende de ella.</li>
 * </ul>
 */
@Service
public class AsistenteCatalogoService {

    /** Productos por termino y en total que se le muestran a la IA. */
    static final int POR_TERMINO = 6;
    static final int MAX_CANDIDATOS = 8;

    /** Criterios equivalentes en el buscador, para "ver en la búsqueda". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Consulta(String q, List<String> lineas, List<String> regiones, String modalidad, BigDecimal precioMax) {
    }

    /**
     * {@code productos}: solo los citados, todos reales y visibles. {@code sinResultados}: no hubo
     * productos que mostrar. {@code fueraDeAlcance}: la pregunta no era sobre el catalogo.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Respuesta(String texto, List<ProductoResumen> productos, Consulta busqueda, boolean sinResultados,
                            boolean fueraDeAlcance, String proveedor) {
    }

    private final AsistenteCatalogo asistente;
    private final CatalogoRepository catalogo;
    private final TraductorCatalogo traductor;

    public AsistenteCatalogoService(AsistenteCatalogo asistente, CatalogoRepository catalogo, TraductorCatalogo traductor) {
        this.asistente = asistente;
        this.catalogo = catalogo;
        this.traductor = traductor;
    }

    public Respuesta consultar(String pregunta, Idioma idioma) {
        boolean ingles = idioma == Idioma.EN;
        Filtro todo = new Filtro(null, List.of(), null, List.of(), null, null, null, true);
        List<Opcion> lineas = catalogo.facetaLineas(todo).stream().map(f -> new Opcion(f.codigo(), f.nombre())).toList();
        List<Opcion> regiones = catalogo.faceta(todo, "pr.region", "pr.region").stream()
                .map(f -> new Opcion(f.codigo(), f.nombre())).toList();

        Interpretacion i = asistente.interpretar(pregunta, lineas, regiones);
        if (i.fueraDeAlcance()) {
            return new Respuesta(ingles
                    ? "I can only help you find products in the Mercado CCPIP catalog. Tell me what you are looking for."
                    : "Solo puedo ayudarte a encontrar productos del catálogo de Mercado CCPIP. Cuéntame qué buscas.",
                    List.of(), null, true, true, asistente.proveedor());
        }

        List<ProductoResumen> encontrados = buscar(i, i.terminos());
        if (encontrados.isEmpty() && !i.terminos().isEmpty() && !i.lineas().isEmpty()) {
            // Las palabras no coinciden con ningun nombre, pero la linea si: se busca solo por la linea
            encontrados = buscar(i, List.of());
        }
        Consulta consulta = new Consulta(i.terminos().isEmpty() ? null : i.terminos().get(0), i.lineas(), i.regiones(),
                i.modalidad(), i.precioMax());
        if (encontrados.isEmpty()) {
            return new Respuesta(ingles
                    ? "I couldn't find catalog products that match your question. Try other words or use the filters."
                    : "No encontré productos del catálogo que coincidan con tu pregunta. Prueba con otras palabras o usa los filtros.",
                    List.of(), consulta, true, false, asistente.proveedor());
        }

        AsistenteCatalogo.Respuesta r = asistente.responder(pregunta, encontrados.stream().map(this::candidato).toList(), ingles);
        Set<Long> citados = Set.copyOf(r.citados());
        List<ProductoResumen> productos = encontrados.stream().filter(p -> citados.contains(p.id())).toList();
        return new Respuesta(r.texto(), traducir(productos, idioma), consulta, productos.isEmpty(), false,
                asistente.proveedor());
    }

    /** Une los resultados de cada termino (o una sola busqueda sin termino), sin repetir y en orden de relevancia. */
    private List<ProductoResumen> buscar(Interpretacion i, List<String> terminos) {
        List<String> porBuscar = terminos.isEmpty() ? Collections.singletonList(null) : terminos;
        Map<Long, ProductoResumen> unidos = new LinkedHashMap<>();
        for (String termino : porBuscar) {
            Filtro f = new Filtro(termino, i.lineas(), i.modalidad(), i.regiones(), null, null, i.precioMax(), true);
            catalogo.buscar(f, Orden.RELEVANCIA, 0, POR_TERMINO).forEach(p -> unidos.putIfAbsent(p.id(), p));
        }
        return new ArrayList<>(unidos.values()).subList(0, Math.min(MAX_CANDIDATOS, unidos.size()));
    }

    private Candidato candidato(ProductoResumen p) {
        String presentacion = p.contenido() != null ? p.contenido() : p.unidad() == null ? null : p.unidad().nombre();
        return new Candidato(p.id(), p.nombre(), p.linea() == null ? null : p.linea().nombre(),
                p.tienda() == null ? null : p.tienda().nombre(), p.tienda() == null ? null : p.tienda().region(),
                p.modalidad(), p.precio() != null ? p.precio() : p.precioMayorista(), presentacion, p.disponible());
    }

    private List<ProductoResumen> traducir(List<ProductoResumen> productos, Idioma idioma) {
        if (productos.isEmpty()) {
            return productos;
        }
        Busqueda b = new Busqueda(productos, 0, productos.size(), productos.size(), 1, 0,
                new Facetas(List.of(), List.of(), List.of(), List.of()));
        return traductor.busqueda(b, idioma).items();
    }
}
