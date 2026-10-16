package pe.ccpip.imagenes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.productor.Auditoria;
import pe.ccpip.productor.Auditoria.Accion;

/**
 * Portada, galeria y fotos de producto. Cada archivo se sube por separado: si la conexion se
 * corta, se reintenta ese archivo sin perder los ya subidos (CCPIP-44). Las fotos de producto
 * no se borran: se retiran o se reemplazan y quedan en la base y en el bucket (CCPIP-388, CCPIP-389).
 */
@Service
public class ImagenService {

    public static final int MAX_FOTOS_PRODUCTO = 6;
    public static final int MAX_FOTOS_GALERIA = 24;
    public static final List<String> CATEGORIAS = List.of("COMUNIDAD", "CULTIVO", "PRODUCCION", "TIENDA_FISICA", "BRANDING");

    private static final Logger log = LoggerFactory.getLogger(ImagenService.class);

    public enum Tipo { PORTADA, GALERIA, PRODUCTO }

    public record Imagen(UUID id, Tipo tipo, String categoria, Long productoId, int ancho, int alto,
                         boolean principal, String url, String urlMiniatura) {
    }

    private final JdbcTemplate jdbc;
    private final ImagenStorage storage;
    private final ProcesadorImagen procesador;
    private final Auditoria auditoria;

    public ImagenService(JdbcTemplate jdbc, ImagenStorage storage, ProcesadorImagen procesador, Auditoria auditoria) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.procesador = procesador;
        this.auditoria = auditoria;
    }

    @Transactional
    public Imagen subir(long productorId, long usuarioId, Tipo tipo, String categoria, Long productoId, byte[] archivo) {
        validarDestino(productorId, tipo, categoria, productoId);
        ProcesadorImagen.Procesada procesada = procesador.procesar(archivo);

        if (tipo == Tipo.PORTADA) {
            // Una sola portada: la nueva reemplaza a la anterior
            jdbc.query("SELECT id FROM imagen WHERE productor_id = ? AND tipo = 'PORTADA'",
                    (rs, row) -> rs.getObject("id", UUID.class), productorId).forEach(vieja -> borrar(productorId, vieja));
        }
        boolean principal = tipo == Tipo.PRODUCTO && contar(VIGENTES_DEL_PRODUCTO, productoId) == 0;
        int orden = tipo == Tipo.PRODUCTO ? contar(VIGENTES_DEL_PRODUCTO, productoId) : contar("productor_id = ? AND tipo = 'GALERIA'", productorId);
        UUID id = guardar(productorId, usuarioId, tipo, tipo == Tipo.GALERIA ? categoria : null, productoId, procesada, orden, principal);
        return buscar(id).orElseThrow();
    }

    /**
     * Quitar una imagen propia (RNF-05). Una foto de producto se retira de forma logica y queda
     * registrado quien y cuando (CCPIP-389); si era la principal, otra vigente toma su lugar. No se
     * exige un minimo de fotos: esa regla aun no esta definida. Portada y galeria se borran.
     */
    @Transactional
    public void eliminar(long productorId, long usuarioId, UUID id) {
        Vigente foto = vigente(productorId, id);
        if (foto.productoId() == null) {
            borrar(productorId, id);
            return;
        }
        jdbc.update("UPDATE imagen SET principal = false, retirada_en = now(), retirada_por = ? WHERE id = ?", usuarioId, id);
        if (foto.principal()) {
            jdbc.update("""
                    UPDATE imagen SET principal = true
                    WHERE id = (SELECT id FROM imagen WHERE producto_id = ? AND retirada_en IS NULL
                                ORDER BY orden, subida_en LIMIT 1)""",
                    foto.productoId());
        }
        auditoria.registrar(foto.productoId(), usuarioId, Accion.IMAGEN, Map.of("retirada", id.toString()));
    }

    /**
     * CCPIP-388: cambia una foto de producto por otra en el mismo lugar (orden y principal). Se
     * valida el archivo como al subir; la anterior queda retirada y apunta a la nueva.
     */
    @Transactional
    public Imagen reemplazar(long productorId, long usuarioId, UUID id, byte[] archivo) {
        Vigente foto = vigente(productorId, id);
        if (foto.productoId() == null) {
            throw ErrorNegocio.invalido("SOLO_FOTOS_DE_PRODUCTO", "Solo se reemplazan las fotos de tus productos.");
        }
        ProcesadorImagen.Procesada procesada = procesador.procesar(archivo);
        // Primero se libera la principal: solo puede haber una por producto
        jdbc.update("UPDATE imagen SET principal = false, retirada_en = now(), retirada_por = ? WHERE id = ?", usuarioId, id);
        UUID nueva = guardar(productorId, usuarioId, Tipo.PRODUCTO, null, foto.productoId(), procesada, foto.orden(), foto.principal());
        jdbc.update("UPDATE imagen SET reemplazada_por = ? WHERE id = ?", nueva, id);
        auditoria.registrar(foto.productoId(), usuarioId, Accion.IMAGEN, Map.of("reemplazada", id.toString(), "nueva", nueva.toString()));
        return buscar(nueva).orElseThrow();
    }

    /** CCPIP-387: las fotos vigentes de un producto propio; el de otro productor no existe para el. */
    public List<Imagen> dePublicacion(long productorId, long productoId) {
        Integer propio = jdbc.queryForObject("SELECT count(*) FROM producto WHERE id = ? AND productor_id = ?",
                Integer.class, productoId, productorId);
        if (propio == null || propio == 0) {
            throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe.");
        }
        return deProducto(productoId);
    }

    @Transactional
    public void marcarPrincipal(long productorId, UUID id) {
        Long productoId = jdbc.query("""
                        SELECT producto_id FROM imagen
                        WHERE id = ? AND productor_id = ? AND tipo = 'PRODUCTO' AND retirada_en IS NULL""",
                        (rs, row) -> rs.getLong(1), id, productorId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("IMAGEN_NO_ENCONTRADA", "La imagen no existe."));
        jdbc.update("UPDATE imagen SET principal = false WHERE producto_id = ? AND principal", productoId);
        jdbc.update("UPDATE imagen SET principal = true WHERE id = ?", id);
    }

    public List<Imagen> deProducto(long productoId) {
        return jdbc.query(SELECT + " WHERE " + VIGENTES_DEL_PRODUCTO + " ORDER BY principal DESC, orden, subida_en",
                ImagenService::mapear, productoId);
    }

    public List<Imagen> deTienda(long productorId) {
        return jdbc.query(SELECT + " WHERE productor_id = ? AND tipo IN ('PORTADA', 'GALERIA') ORDER BY orden, subida_en",
                ImagenService::mapear, productorId);
    }

    public Optional<Imagen> buscar(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", ImagenService::mapear, id).stream().findFirst();
    }

    /** Bytes para servir la imagen publica: completa o miniatura. Una retirada se sigue sirviendo (CCPIP-389). */
    public Optional<byte[]> contenido(UUID id, boolean miniatura) {
        return jdbc.query("SELECT clave, clave_miniatura FROM imagen WHERE id = ?",
                        (rs, row) -> miniatura ? rs.getString(2) : rs.getString(1), id)
                .stream().findFirst()
                .flatMap(storage::leer);
    }

    public static String url(UUID id) {
        return "/api/imagenes/" + id;
    }

    public static String urlMiniatura(UUID id) {
        return "/api/imagenes/" + id + "?tam=min";
    }

    private static final String SELECT = "SELECT id, tipo, categoria, producto_id, ancho, alto, principal FROM imagen";
    private static final String VIGENTES_DEL_PRODUCTO = "producto_id = ? AND retirada_en IS NULL";

    private record Vigente(Long productoId, boolean principal, int orden) {
    }

    /** Una imagen propia y no retirada; cualquier otra no existe para este productor (RNF-05). */
    private Vigente vigente(long productorId, UUID id) {
        return jdbc.query("SELECT producto_id, principal, orden FROM imagen WHERE id = ? AND productor_id = ? AND retirada_en IS NULL",
                        (rs, row) -> new Vigente((Long) rs.getObject(1), rs.getBoolean(2), rs.getInt(3)), id, productorId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("IMAGEN_NO_ENCONTRADA", "La imagen no existe."));
    }

    /** Sube al bucket la foto ya procesada y la registra. */
    private UUID guardar(long productorId, long usuarioId, Tipo tipo, String categoria, Long productoId,
                         ProcesadorImagen.Procesada procesada, int orden, boolean principal) {
        UUID id = UUID.randomUUID();
        String base = "productores/" + productorId + "/" + id;
        storage.guardar(base + ".jpg", procesada.imagen(), "image/jpeg");
        storage.guardar(base + "_min.jpg", procesada.miniatura(), "image/jpeg");
        jdbc.update("""
                INSERT INTO imagen (id, productor_id, producto_id, tipo, categoria, clave, clave_miniatura,
                                    ancho, alto, bytes, orden, principal, subida_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, productorId, productoId, tipo.name(), categoria, base + ".jpg", base + "_min.jpg",
                procesada.ancho(), procesada.alto(), procesada.imagen().length, orden, principal, usuarioId);
        return id;
    }

    /** Portada y galeria: se borran del registro y del bucket. */
    private void borrar(long productorId, UUID id) {
        record Fila(String clave, String miniatura) {
        }
        Fila fila = jdbc.query("SELECT clave, clave_miniatura FROM imagen WHERE id = ? AND productor_id = ?",
                        (rs, row) -> new Fila(rs.getString(1), rs.getString(2)), id, productorId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("IMAGEN_NO_ENCONTRADA", "La imagen no existe."));
        jdbc.update("DELETE FROM imagen WHERE id = ?", id);
        try {
            storage.eliminar(fila.clave());
            storage.eliminar(fila.miniatura());
        } catch (RuntimeException e) {
            // El registro ya no existe; un archivo huerfano en el bucket no rompe nada
            log.warn("No se pudo borrar del bucket la imagen {}: {}", id, e.getMessage());
        }
    }

    private static Imagen mapear(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Imagen(id, Tipo.valueOf(rs.getString("tipo")), rs.getString("categoria"),
                (Long) rs.getObject("producto_id"), rs.getInt("ancho"), rs.getInt("alto"),
                rs.getBoolean("principal"), url(id), urlMiniatura(id));
    }

    private void validarDestino(long productorId, Tipo tipo, String categoria, Long productoId) {
        switch (tipo) {
            case PRODUCTO -> {
                if (productoId == null) {
                    throw ErrorNegocio.invalido("PRODUCTO_REQUERIDO", "Indica a qué producto pertenece la foto.");
                }
                Integer propio = jdbc.queryForObject("SELECT count(*) FROM producto WHERE id = ? AND productor_id = ?",
                        Integer.class, productoId, productorId);
                if (propio == null || propio == 0) {
                    throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe.");
                }
                if (contar(VIGENTES_DEL_PRODUCTO, productoId) >= MAX_FOTOS_PRODUCTO) {
                    throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "LIMITE_DE_FOTOS",
                            "Un producto puede tener hasta " + MAX_FOTOS_PRODUCTO + " fotos. Elimina una para agregar otra.");
                }
            }
            case GALERIA -> {
                if (categoria == null || !CATEGORIAS.contains(categoria)) {
                    throw ErrorNegocio.invalido("CATEGORIA_INVALIDA", "Elige la categoría de la foto.");
                }
                if (contar("productor_id = ? AND tipo = 'GALERIA'", productorId) >= MAX_FOTOS_GALERIA) {
                    throw ErrorNegocio.invalido("LIMITE_DE_FOTOS",
                            "La galería admite hasta " + MAX_FOTOS_GALERIA + " fotos. Elimina una para agregar otra.");
                }
            }
            case PORTADA -> {
                // Siempre se puede reemplazar la portada
            }
        }
    }

    private int contar(String condicion, Object parametro) {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM imagen WHERE " + condicion, Integer.class, parametro);
        return total == null ? 0 : total;
    }
}
