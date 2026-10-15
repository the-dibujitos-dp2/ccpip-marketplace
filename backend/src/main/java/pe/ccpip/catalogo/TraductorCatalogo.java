package pe.ccpip.catalogo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import pe.ccpip.catalogo.CatalogoModelos.Busqueda;
import pe.ccpip.catalogo.CatalogoModelos.Catalogos;
import pe.ccpip.catalogo.CatalogoModelos.Codigo;
import pe.ccpip.catalogo.CatalogoModelos.Faceta;
import pe.ccpip.catalogo.CatalogoModelos.Facetas;
import pe.ccpip.catalogo.CatalogoModelos.HistoriaPublica;
import pe.ccpip.catalogo.CatalogoModelos.Inicio;
import pe.ccpip.catalogo.CatalogoModelos.LineaCatalogo;
import pe.ccpip.catalogo.CatalogoModelos.PartesHistoria;
import pe.ccpip.catalogo.CatalogoModelos.ProductoDetalle;
import pe.ccpip.catalogo.CatalogoModelos.ProductoResumen;
import pe.ccpip.catalogo.CatalogoModelos.TiendaDetalle;
import pe.ccpip.catalogo.CatalogoModelos.TiendaResumen;
import pe.ccpip.catalogo.CatalogoModelos.TiendaTarjeta;
import pe.ccpip.comun.Idioma;
import pe.ccpip.traduccion.Traducciones;
import pe.ccpip.traduccion.Traducciones.Entidad;

/**
 * Pone el contenido aprobado en ingles sobre las respuestas del catalogo (CCPIP-166 productos,
 * CCPIP-357 tiendas, CCPIP-358 comunidades). Cada texto sin traduccion aprobada queda en su
 * original en espanol. En espanol devuelve la misma respuesta sin consultar nada.
 */
@Component
public class TraductorCatalogo {

    private final Traducciones traducciones;

    public TraductorCatalogo(Traducciones traducciones) {
        this.traducciones = traducciones;
    }

    public ProductoDetalle detalle(ProductoDetalle p, Idioma idioma) {
        if (idioma == Idioma.ES) {
            return p;
        }
        Claves c = new Claves();
        c.producto(p.id());
        c.historia(p.id());
        c.tienda(p.tienda());
        c.codigo(Entidad.LINEA, p.linea());
        p.lineas().forEach(l -> c.codigo(Entidad.LINEA, l));
        c.codigo(Entidad.UNIDAD, p.unidad());
        p.relacionados().forEach(c::resumen);
        Textos t = cargar(c, idioma);
        return new ProductoDetalle(p.id(), t.de(Entidad.PRODUCTO, p.id(), "nombre", p.nombre()),
                t.de(Entidad.PRODUCTO, p.id(), "descripcion", p.descripcion()), p.contenido(),
                t.codigo(Entidad.UNIDAD, p.unidad()), t.codigo(Entidad.LINEA, p.linea()),
                p.lineas().stream().map(l -> t.codigo(Entidad.LINEA, l)).toList(), p.modalidad(), p.precio(),
                p.precioMayorista(), p.cantidadMinimaMayorista(), p.disponible(), p.comprable(), p.imagenes(),
                t.tienda(p.tienda()), t.historia(p.id(), p.historia()), p.cosecha(),
                p.relacionados().stream().map(t::resumen).toList());
    }

    public Busqueda busqueda(Busqueda b, Idioma idioma) {
        if (idioma == Idioma.ES) {
            return b;
        }
        Claves c = new Claves();
        b.items().forEach(c::resumen);
        b.facetas().lineas().forEach(f -> c.clave(Entidad.LINEA, f.codigo()));
        b.facetas().comunidades().forEach(f -> c.clave(Entidad.TIENDA, f.codigo()));
        Textos t = cargar(c, idioma);
        Facetas f = b.facetas();
        Facetas traducidas = new Facetas(t.facetas(Entidad.LINEA, f.lineas()), f.regiones(), f.modalidades(),
                t.facetas(Entidad.TIENDA, f.comunidades()));
        return new Busqueda(b.items().stream().map(t::resumen).toList(), b.pagina(), b.tamano(), b.total(),
                b.totalPaginas(), b.totalComunidades(), traducidas);
    }

    public List<TiendaTarjeta> tarjetas(List<TiendaTarjeta> tarjetas, Idioma idioma) {
        if (idioma == Idioma.ES) {
            return tarjetas;
        }
        Claves c = new Claves();
        tarjetas.forEach(tt -> c.productor(tt.id()));
        Textos t = cargar(c, idioma);
        return tarjetas.stream().map(t::tarjeta).toList();
    }

    public TiendaDetalle tienda(TiendaDetalle d, Idioma idioma) {
        if (idioma == Idioma.ES) {
            return d;
        }
        Claves c = new Claves();
        c.productor(d.id());
        d.lineas().forEach(f -> c.clave(Entidad.LINEA, f.codigo()));
        d.productos().forEach(c::resumen);
        Textos t = cargar(c, idioma);
        String id = String.valueOf(d.id());
        return new TiendaDetalle(d.id(), t.de(Entidad.TIENDA, id, "nombre", d.nombre()),
                t.de(Entidad.TIENDA, id, "descripcion", d.descripcion()),
                t.de(Entidad.COMUNIDAD, id, "historia", d.historia()), t.de(Entidad.COMUNIDAD, id, "cita", d.cita()),
                d.pueblo(), d.comunidad(), d.region(), d.localidad(), d.socios(), d.origenVerificado(), d.desde(),
                d.portada(), d.galeria(), t.facetas(Entidad.LINEA, d.lineas()),
                d.productos().stream().map(t::resumen).toList(), d.destacados());
    }

    public Inicio inicio(Inicio i, Idioma idioma) {
        if (idioma == Idioma.ES) {
            return i;
        }
        Claves c = new Claves();
        i.tiendas().forEach(tt -> c.productor(tt.id()));
        i.lineas().forEach(f -> c.clave(Entidad.LINEA, f.codigo()));
        i.destacados().forEach(c::resumen);
        i.novedades().forEach(c::resumen);
        Textos t = cargar(c, idioma);
        return new Inicio(i.tiendas().stream().map(t::tarjeta).toList(), t.facetas(Entidad.LINEA, i.lineas()),
                i.destacados().stream().map(t::resumen).toList(), i.novedades().stream().map(t::resumen).toList());
    }

    public Catalogos catalogos(Catalogos k, Idioma idioma) {
        if (idioma == Idioma.ES) {
            return k;
        }
        Claves c = new Claves();
        k.lineas().forEach(l -> c.clave(Entidad.LINEA, l.codigo()));
        k.unidades().forEach(u -> c.codigo(Entidad.UNIDAD, u));
        Textos t = cargar(c, idioma);
        List<LineaCatalogo> lineas = k.lineas().stream()
                .map(l -> new LineaCatalogo(l.codigo(), t.de(Entidad.LINEA, l.codigo(), "nombre", l.nombre()),
                        l.requiereVencimiento(), l.unidadSugerida()))
                .toList();
        return new Catalogos(lineas, k.unidades().stream().map(u -> t.codigo(Entidad.UNIDAD, u)).toList(),
                k.comisionPorcentaje());
    }

    private Textos cargar(Claves claves, Idioma idioma) {
        Map<Entidad, Map<String, Map<String, String>>> textos = new EnumMap<>(Entidad.class);
        claves.porEntidad.forEach((entidad, lista) -> textos.put(entidad, traducciones.aprobadas(entidad, lista, idioma)));
        return new Textos(textos);
    }

    /** Las claves que necesita una respuesta, para leer sus traducciones con una consulta por entidad. */
    private static final class Claves {

        private final Map<Entidad, Set<String>> porEntidad = new EnumMap<>(Entidad.class);

        void clave(Entidad entidad, String clave) {
            if (clave != null) {
                porEntidad.computeIfAbsent(entidad, e -> new HashSet<>()).add(clave);
            }
        }

        void codigo(Entidad entidad, Codigo codigo) {
            if (codigo != null) {
                clave(entidad, codigo.codigo());
            }
        }

        void producto(long id) {
            clave(Entidad.PRODUCTO, String.valueOf(id));
        }

        void historia(long id) {
            clave(Entidad.HISTORIA, String.valueOf(id));
        }

        /** La tienda y la comunidad viven en la fila del productor: misma clave para ambas. */
        void productor(long id) {
            clave(Entidad.TIENDA, String.valueOf(id));
            clave(Entidad.COMUNIDAD, String.valueOf(id));
        }

        void tienda(TiendaResumen tienda) {
            if (tienda != null) {
                clave(Entidad.TIENDA, String.valueOf(tienda.id()));
            }
        }

        void resumen(ProductoResumen r) {
            producto(r.id());
            codigo(Entidad.LINEA, r.linea());
            codigo(Entidad.UNIDAD, r.unidad());
            tienda(r.tienda());
        }
    }

    /** Las traducciones leidas; cada consulta devuelve la traduccion o, si no hay, el original. */
    private static final class Textos {

        private final Map<Entidad, Map<String, Map<String, String>>> textos;

        Textos(Map<Entidad, Map<String, Map<String, String>>> textos) {
            this.textos = textos;
        }

        String de(Entidad entidad, String clave, String campo, String original) {
            if (original == null) {
                return null;
            }
            String traducido = textos.getOrDefault(entidad, Map.of()).getOrDefault(clave, Map.of()).get(campo);
            return traducido != null ? traducido : original;
        }

        String de(Entidad entidad, long id, String campo, String original) {
            return de(entidad, String.valueOf(id), campo, original);
        }

        Codigo codigo(Entidad entidad, Codigo codigo) {
            return codigo == null ? null : new Codigo(codigo.codigo(), de(entidad, codigo.codigo(), "nombre", codigo.nombre()));
        }

        List<Faceta> facetas(Entidad entidad, List<Faceta> facetas) {
            List<Faceta> traducidas = new ArrayList<>(facetas.size());
            for (Faceta f : facetas) {
                traducidas.add(new Faceta(f.codigo(), de(entidad, f.codigo(), "nombre", f.nombre()), f.total()));
            }
            return traducidas;
        }

        TiendaResumen tienda(TiendaResumen t) {
            return t == null ? null : new TiendaResumen(t.id(), de(Entidad.TIENDA, t.id(), "nombre", t.nombre()),
                    t.pueblo(), t.comunidad(), t.region(), t.localidad(), t.origenVerificado());
        }

        TiendaTarjeta tarjeta(TiendaTarjeta t) {
            return new TiendaTarjeta(t.id(), de(Entidad.TIENDA, t.id(), "nombre", t.nombre()), t.pueblo(), t.comunidad(),
                    t.region(), t.localidad(), de(Entidad.COMUNIDAD, t.id(), "cita", t.cita()), t.origenVerificado(),
                    t.portada(), t.productos());
        }

        ProductoResumen resumen(ProductoResumen r) {
            return new ProductoResumen(r.id(), de(Entidad.PRODUCTO, r.id(), "nombre", r.nombre()), r.modalidad(),
                    r.precio(), r.precioMayorista(), r.contenido(), codigo(Entidad.UNIDAD, r.unidad()),
                    codigo(Entidad.LINEA, r.linea()), r.lineas(), r.imagen(), r.disponible(), tienda(r.tienda()));
        }

        HistoriaPublica historia(long productoId, HistoriaPublica h) {
            if (h == null) {
                return null;
            }
            String id = String.valueOf(productoId);
            PartesHistoria p = h.partes();
            PartesHistoria partes = p == null ? null : new PartesHistoria(
                    de(Entidad.HISTORIA, id, "comunidad", p.comunidad()), de(Entidad.HISTORIA, id, "cultivo", p.cultivo()),
                    de(Entidad.HISTORIA, id, "proceso", p.proceso()), de(Entidad.HISTORIA, id, "significado", p.significado()));
            return new HistoriaPublica(de(Entidad.HISTORIA, id, "titulo", h.titulo()), de(Entidad.HISTORIA, id, "texto", h.texto()),
                    h.asistida(), partes);
        }
    }
}
