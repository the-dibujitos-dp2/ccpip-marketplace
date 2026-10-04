package pe.ccpip.productor;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.ia.AsistenteRedaccion;
import pe.ccpip.ia.AsistenteRedaccion.Propuesta;
import pe.ccpip.ia.AsistenteRedaccion.Variante;
import pe.ccpip.productor.Auditoria.Accion;
import pe.ccpip.productor.ProductoModelos.AprobarHistoria;
import pe.ccpip.productor.ProductoModelos.DatosHistoria;
import pe.ccpip.productor.ProductoModelos.Historia;

/**
 * Historia del producto (PW-05, CCPIP-48 y CCPIP-49). El productor responde cuatro preguntas;
 * la IA puede proponer un texto, pero lo publicado es siempre lo que el productor aprueba.
 * Se conservan la propuesta original y la version final.
 */
@Service
public class HistoriaService {

    private final JdbcTemplate jdbc;
    private final ProductosService productos;
    private final AsistenteRedaccion asistente;
    private final Auditoria auditoria;

    public HistoriaService(JdbcTemplate jdbc, ProductosService productos, AsistenteRedaccion asistente, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.productos = productos;
        this.asistente = asistente;
        this.auditoria = auditoria;
    }

    /** Guarda respuestas y borrador sin publicar nada. */
    @Transactional
    public Historia guardar(long productorId, long productoId, DatosHistoria d) {
        productos.obtener(productorId, productoId);
        jdbc.update("""
                INSERT INTO producto_historia (producto_id, quienes, como, significado, cuidado, titulo, texto_final, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (producto_id) DO UPDATE SET quienes = EXCLUDED.quienes, como = EXCLUDED.como,
                    significado = EXCLUDED.significado, cuidado = EXCLUDED.cuidado, titulo = EXCLUDED.titulo,
                    texto_final = EXCLUDED.texto_final, aprobada_en = NULL, aprobada_por = NULL, actualizado_en = now()""",
                productoId, TiendaService.limpio(d.quienes()), TiendaService.limpio(d.como()),
                TiendaService.limpio(d.significado()), TiendaService.limpio(d.cuidado()), TiendaService.limpio(d.titulo()),
                TiendaService.limpio(d.textoFinal()));
        return productos.historia(productoId);
    }

    /** Pide a la IA un borrador a partir de las respuestas. Si no esta disponible responde 503 y se escribe a mano. */
    @Transactional
    public Propuesta proponer(long productorId, long productoId, DatosHistoria d, Variante variante) {
        String producto = productos.obtener(productorId, productoId).nombre();
        String comunidad = jdbc.queryForObject("SELECT coalesce(nombre_tienda, comunidad) FROM productor WHERE id = ?",
                String.class, productorId);
        Propuesta propuesta = asistente.proponerHistoria(new AsistenteRedaccion.DatosHistoria(producto, comunidad,
                d.quienes(), d.como(), d.significado(), d.cuidado(), variante == null ? Variante.NORMAL : variante));
        jdbc.update("""
                INSERT INTO producto_historia (producto_id, quienes, como, significado, cuidado, titulo, texto_propuesto,
                                               proveedor_ia, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (producto_id) DO UPDATE SET quienes = EXCLUDED.quienes, como = EXCLUDED.como,
                    significado = EXCLUDED.significado, cuidado = EXCLUDED.cuidado, titulo = EXCLUDED.titulo,
                    texto_propuesto = EXCLUDED.texto_propuesto, proveedor_ia = EXCLUDED.proveedor_ia, actualizado_en = now()""",
                productoId, TiendaService.limpio(d.quienes()), TiendaService.limpio(d.como()),
                TiendaService.limpio(d.significado()), TiendaService.limpio(d.cuidado()), propuesta.titulo(),
                propuesta.texto(), propuesta.proveedor());
        return propuesta;
    }

    /** CCPIP-49: la historia solo se publica cuando el productor la aprueba de forma explicita. */
    @Transactional
    public Historia aprobar(long productorId, long usuarioId, long productoId, AprobarHistoria d) {
        productos.obtener(productorId, productoId);
        Boolean hayPropuesta = jdbc.query("SELECT texto_propuesto IS NOT NULL FROM producto_historia WHERE producto_id = ?",
                (rs, row) -> rs.getBoolean(1), productoId).stream().findFirst().orElse(false);
        boolean asistida = d.asistida() && hayPropuesta;
        String texto = d.textoFinal().trim();
        jdbc.update("""
                INSERT INTO producto_historia (producto_id, titulo, texto_final, asistida, aprobada_en, aprobada_por, actualizado_en)
                VALUES (?, ?, ?, ?, now(), ?, now())
                ON CONFLICT (producto_id) DO UPDATE SET titulo = EXCLUDED.titulo, texto_final = EXCLUDED.texto_final,
                    asistida = EXCLUDED.asistida, aprobada_en = now(), aprobada_por = EXCLUDED.aprobada_por,
                    actualizado_en = now()""",
                productoId, TiendaService.limpio(d.titulo()), texto, asistida, usuarioId);
        // Lo que lee el comprador es la version aprobada
        jdbc.update("UPDATE producto SET historia_origen = ?, actualizado_en = now() WHERE id = ?", texto, productoId);
        auditoria.registrar(productoId, usuarioId, Accion.HISTORIA, Map.of("asistida", asistida));
        return productos.historia(productoId);
    }

    /** CCPIP-48 en PW-04: borrador de descripcion. Se guarda como propuesta; el productor decide si la usa. */
    @Transactional
    public Propuesta proponerDescripcion(long productorId, Long productoId, String nombre, List<String> lineas,
                                         String contenido, String notas) {
        if (productoId != null) {
            productos.obtener(productorId, productoId);
        }
        String comunidad = jdbc.queryForObject("SELECT coalesce(nombre_tienda, comunidad) FROM productor WHERE id = ?",
                String.class, productorId);
        List<String> nombresLineas = lineas == null || lineas.isEmpty() ? List.of()
                : jdbc.queryForList("SELECT nombre FROM linea_producto WHERE codigo = ANY (?) ORDER BY orden",
                        String.class, (Object) lineas.toArray(String[]::new));
        Propuesta propuesta = asistente.proponerDescripcion(
                new AsistenteRedaccion.DatosDescripcion(nombre, nombresLineas, contenido, comunidad, notas));
        if (productoId != null) {
            jdbc.update("UPDATE producto SET descripcion_propuesta = ? WHERE id = ?", propuesta.texto(), productoId);
        }
        return propuesta;
    }
}
