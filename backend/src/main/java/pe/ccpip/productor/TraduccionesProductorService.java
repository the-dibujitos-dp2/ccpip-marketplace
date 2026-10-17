package pe.ccpip.productor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.Idioma;
import pe.ccpip.productor.ProductoModelos.Historia;
import pe.ccpip.productor.ProductoModelos.ProductoEdicion;
import pe.ccpip.productor.TiendaService.TiendaEdicion;
import pe.ccpip.traduccion.TraduccionNoDisponible;
import pe.ccpip.traduccion.Traducciones;
import pe.ccpip.traduccion.Traducciones.Entidad;
import pe.ccpip.traduccion.Traducciones.Estado;
import pe.ccpip.traduccion.Traducciones.Fila;
import pe.ccpip.traduccion.TraductorAutomatico;

/**
 * Traducciones del contenido del productor desde PW-04 (producto e historia) y PW-08 (tienda).
 *
 * CCPIP-147 (RF-168): el productor pide la traduccion automatica con el proveedor configurado; sin
 * proveedor responde 503 y nada cambia (RNF-34). La traduccion queda PENDIENTE y solo se publica
 * cuando el productor la revisa, porque el relato cultural no puede publicarse sin que la
 * comunidad lo valide (RNF-33). CCPIP-170: cada traduccion queda ligada a la version del original.
 * Toda consulta filtra por el productor de la sesion: un producto ajeno no existe (404, RNF-05).
 */
@Service
public class TraduccionesProductorService {

    /** Un campo traducible con su original y, si existe, su traduccion en el idioma pedido. */
    public record CampoTraduccion(Entidad entidad, String campo, String original, int version, String texto,
                                  Estado estado, Traducciones.Origen origen, String proveedor, boolean vigente) {
    }

    /** {@code automaticaDisponible} falso: no hay proveedor y la pantalla lo avisa sin intentar. */
    public record TraduccionesContenido(String idioma, boolean automaticaDisponible, List<CampoTraduccion> campos) {
    }

    public record TextoTraducido(@NotNull(message = "Indica que contenido traduces") Entidad entidad,
                                 @NotBlank(message = "Indica el campo") String campo,
                                 @NotBlank(message = "Escribe la traduccion") @Size(max = 4000, message = "Máximo 4000 caracteres") String texto) {
    }

    /** Textos corregidos por el productor; los que no vienen se publican como los propuso el proveedor. */
    public record Aprobar(@Valid List<TextoTraducido> textos) {
    }

    /** Un texto original traducible: de que entidad y campo es, y su valor actual. */
    private record Original(Entidad entidad, String campo, String texto) {
    }

    private final ProductosService productos;
    private final TiendaService tienda;
    private final Traducciones traducciones;
    private final TraductorAutomatico traductor;

    public TraduccionesProductorService(ProductosService productos, TiendaService tienda, Traducciones traducciones,
                                        TraductorAutomatico traductor) {
        this.productos = productos;
        this.tienda = tienda;
        this.traducciones = traducciones;
        this.traductor = traductor;
    }

    // ---------- Producto e historia (PW-04 y PW-05) ----------

    public TraduccionesContenido deProducto(long productorId, long productoId, String idioma) {
        Idioma destino = destino(idioma);
        return estado(productoId, originalesProducto(productorId, productoId), destino);
    }

    @Transactional
    public TraduccionesContenido traducirProducto(long productorId, long productoId, String idioma) {
        Idioma destino = destino(idioma);
        List<Original> originales = originalesProducto(productorId, productoId);
        traducir(productoId, originales, destino);
        return estado(productoId, originales, destino);
    }

    @Transactional
    public TraduccionesContenido aprobarProducto(long productorId, long productoId, String idioma, Aprobar datos) {
        Idioma destino = destino(idioma);
        List<Original> originales = originalesProducto(productorId, productoId);
        aprobar(productoId, originales, destino, datos);
        return estado(productoId, originales, destino);
    }

    // ---------- Tienda y comunidad (PW-08) ----------

    public TraduccionesContenido deTienda(long productorId, String idioma) {
        Idioma destino = destino(idioma);
        return estado(productorId, originalesTienda(productorId), destino);
    }

    @Transactional
    public TraduccionesContenido traducirTienda(long productorId, String idioma) {
        Idioma destino = destino(idioma);
        List<Original> originales = originalesTienda(productorId);
        traducir(productorId, originales, destino);
        return estado(productorId, originales, destino);
    }

    @Transactional
    public TraduccionesContenido aprobarTienda(long productorId, String idioma, Aprobar datos) {
        Idioma destino = destino(idioma);
        List<Original> originales = originalesTienda(productorId);
        aprobar(productorId, originales, destino, datos);
        return estado(productorId, originales, destino);
    }

    // ---------- Internos ----------

    /** El espanol es el original de todo el contenido: solo se traduce a otro idioma (hoy, ingles). */
    private static Idioma destino(String codigo) {
        Idioma idioma = Idioma.deCodigo(codigo).orElse(null);
        if (idioma == null || idioma == Idioma.ES) {
            throw ErrorNegocio.invalido("IDIOMA_NO_TRADUCIBLE",
                    "El contenido se escribe en español; solo se puede traducir al inglés.");
        }
        return idioma;
    }

    /** Lo que el catalogo muestra del producto. La historia solo cuenta si esta aprobada (CCPIP-49). */
    private List<Original> originalesProducto(long productorId, long productoId) {
        ProductoEdicion p = productos.obtener(productorId, productoId);
        List<Original> o = new ArrayList<>();
        agregar(o, Entidad.PRODUCTO, "nombre", p.nombre());
        agregar(o, Entidad.PRODUCTO, "descripcion", p.descripcion());
        Historia h = p.historia();
        if (h.aprobada()) {
            agregar(o, Entidad.HISTORIA, "titulo", h.titulo());
            agregar(o, Entidad.HISTORIA, "texto", h.textoFinal());
            agregar(o, Entidad.HISTORIA, "comunidad", h.quienes());
            agregar(o, Entidad.HISTORIA, "cultivo", h.cuidado());
            agregar(o, Entidad.HISTORIA, "proceso", h.como());
            agregar(o, Entidad.HISTORIA, "significado", h.significado());
        }
        return o;
    }

    private List<Original> originalesTienda(long productorId) {
        TiendaEdicion t = tienda.obtener(productorId);
        List<Original> o = new ArrayList<>();
        agregar(o, Entidad.TIENDA, "nombre", t.nombreTienda());
        agregar(o, Entidad.TIENDA, "descripcion", t.descripcionCorta());
        agregar(o, Entidad.COMUNIDAD, "historia", t.historia());
        agregar(o, Entidad.COMUNIDAD, "cita", t.cita());
        return o;
    }

    private static void agregar(List<Original> o, Entidad entidad, String campo, String texto) {
        if (texto != null && !texto.isBlank()) {
            o.add(new Original(entidad, campo, texto.trim()));
        }
    }

    /**
     * Pide al proveedor los campos sin una traduccion vigente aprobada: una traduccion ya publicada y
     * al dia (por ejemplo, escrita por la comunidad) no se reemplaza. Si el proveedor falla no se
     * escribe nada y el original queda como estaba.
     */
    private void traducir(long clave, List<Original> originales, Idioma destino) {
        if (!traductor.disponible()) {
            throw new TraduccionNoDisponible();
        }
        Map<String, Fila> filas = filas(clave, originales, destino);
        Map<String, Original> porTraducir = new LinkedHashMap<>();
        Map<String, String> textos = new LinkedHashMap<>();
        for (Original o : originales) {
            String id = id(o.entidad(), o.campo());
            Fila f = filas.get(id);
            if (f == null || f.estado() != Estado.APROBADA || !vigente(clave, o, f)) {
                porTraducir.put(id, o);
                textos.put(id, o.texto());
            }
        }
        if (textos.isEmpty()) {
            return;
        }
        Map<String, String> traducidos;
        try {
            traducidos = traductor.traducir(textos, Idioma.ES, destino);
        } catch (TraduccionNoDisponible e) {
            throw e;
        } catch (RuntimeException e) {
            // Un proveedor caido o que no respondio a tiempo es lo mismo para el productor (RNF-34)
            throw new TraduccionNoDisponible();
        }
        String k = String.valueOf(clave);
        porTraducir.forEach((id, o) -> {
            String texto = traducidos.get(id);
            if (texto != null && !texto.isBlank()) {
                traducciones.guardarAutomatica(o.entidad(), k, o.campo(), destino, texto.trim(), traductor.proveedor(),
                        traducciones.version(o.entidad(), k, o.campo()));
            }
        });
    }

    /**
     * Publica las traducciones pendientes, con las correcciones del productor si las envia. Un texto
     * escrito por el productor para un campo sin traduccion tambien se publica (origen COMUNIDAD).
     */
    private void aprobar(long clave, List<Original> originales, Idioma destino, Aprobar datos) {
        Map<String, Original> porId = new HashMap<>();
        originales.forEach(o -> porId.put(id(o.entidad(), o.campo()), o));
        Map<String, String> corregidos = new LinkedHashMap<>();
        if (datos != null && datos.textos() != null) {
            for (TextoTraducido t : datos.textos()) {
                String id = id(t.entidad(), t.campo());
                if (!porId.containsKey(id)) {
                    throw ErrorNegocio.invalido("CAMPO_NO_TRADUCIBLE",
                            "Ese campo no existe o no tiene texto en español para traducir.");
                }
                corregidos.put(id, t.texto().trim());
            }
        }
        String k = String.valueOf(clave);
        int publicadas = 0;
        for (Original o : originales) {
            String id = id(o.entidad(), o.campo());
            if (corregidos.containsKey(id)) {
                traducciones.aprobarTexto(o.entidad(), k, o.campo(), destino, corregidos.get(id),
                        traducciones.version(o.entidad(), k, o.campo()));
                publicadas++;
            } else {
                publicadas += traducciones.aprobarPendiente(o.entidad(), k, o.campo(), destino);
            }
        }
        if (publicadas == 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "SIN_TRADUCCION_PENDIENTE", "No hay traducciones por publicar.");
        }
    }

    private TraduccionesContenido estado(long clave, List<Original> originales, Idioma destino) {
        Map<String, Fila> filas = filas(clave, originales, destino);
        List<CampoTraduccion> campos = originales.stream().map(o -> {
            int version = traducciones.version(o.entidad(), String.valueOf(clave), o.campo());
            Fila f = filas.get(id(o.entidad(), o.campo()));
            if (f == null) {
                return new CampoTraduccion(o.entidad(), o.campo(), o.texto(), version, null, null, null, null, false);
            }
            boolean vigente = f.estado() != Estado.DESACTUALIZADA && f.versionOriginal() == version;
            return new CampoTraduccion(o.entidad(), o.campo(), o.texto(), version, f.texto(), f.estado(), f.origen(),
                    f.proveedor(), vigente);
        }).toList();
        return new TraduccionesContenido(destino.codigo(), traductor.disponible(), campos);
    }

    private boolean vigente(long clave, Original o, Fila f) {
        return f.estado() != Estado.DESACTUALIZADA
                && f.versionOriginal() == traducciones.version(o.entidad(), String.valueOf(clave), o.campo());
    }

    private Map<String, Fila> filas(long clave, List<Original> originales, Idioma destino) {
        Map<String, Fila> filas = new HashMap<>();
        List<Entidad> entidades = originales.stream().map(Original::entidad).distinct().toList();
        if (!entidades.isEmpty()) {
            traducciones.deContenido(entidades, String.valueOf(clave), destino)
                    .forEach(f -> filas.put(id(f.entidad(), f.campo()), f));
        }
        return filas;
    }

    private static String id(Entidad entidad, String campo) {
        return entidad.name() + "." + campo;
    }
}
