package pe.ccpip.imagenes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;

/**
 * Portada, galeria y fotos de producto. Cada archivo se sube por separado: si la conexion se
 * corta, se reintenta ese archivo sin perder los ya subidos (CCPIP-44).
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

    public ImagenService(JdbcTemplate jdbc, ImagenStorage storage, ProcesadorImagen procesador) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.procesador = procesador;
    }

    @Transactional
    public Imagen subir(long productorId, long usuarioId, Tipo tipo, String categoria, Long productoId, byte[] archivo) {
        validarDestino(productorId, tipo, categoria, productoId);
        ProcesadorImagen.Procesada procesada = procesador.procesar(archivo);

        UUID id = UUID.randomUUID();
        String base = "productores/" + productorId + "/" + id;
        storage.guardar(base + ".jpg", procesada.imagen(), "image/jpeg");
        storage.guardar(base + "_min.jpg", procesada.miniatura(), "image/jpeg");

        if (tipo == Tipo.PORTADA) {
            // Una sola portada: la nueva reemplaza a la anterior
            jdbc.query("SELECT id FROM imagen WHERE productor_id = ? AND tipo = 'PORTADA'",
                    (rs, row) -> rs.getObject("id", UUID.class), productorId).forEach(vieja -> eliminar(productorId, vieja));
        }
        boolean principal = tipo == Tipo.PRODUCTO && contar("producto_id = ?", productoId) == 0;
        int orden = tipo == Tipo.PRODUCTO ? contar("producto_id = ?", productoId) : contar("productor_id = ? AND tipo = 'GALERIA'", productorId);
        jdbc.update("""
                INSERT INTO imagen (id, productor_id, producto_id, tipo, categoria, clave, clave_miniatura,
                                    ancho, alto, bytes, orden, principal, subida_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, productorId, productoId, tipo.name(), tipo == Tipo.GALERIA ? categoria : null,
                base + ".jpg", base + "_min.jpg", procesada.ancho(), procesada.alto(), procesada.imagen().length,
                orden, principal, usuarioId);
        return buscar(id).orElseThrow();
    }

    /** El productor solo elimina sus propias imagenes (RNF-05). Una foto de producto que era principal cede el lugar. */
    @Transactional
    public void eliminar(long productorId, UUID id) {
        record Fila(String clave, String miniatura, Long productoId, boolean principal) {
        }
        Fila fila = jdbc.query("SELECT clave, clave_miniatura, producto_id, principal FROM imagen WHERE id = ? AND productor_id = ?",
                        (rs, row) -> new Fila(rs.getString(1), rs.getString(2), (Long) rs.getObject(3), rs.getBoolean(4)),
                        id, productorId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("IMAGEN_NO_ENCONTRADA", "La imagen no existe."));
        jdbc.update("DELETE FROM imagen WHERE id = ?", id);
        if (fila.principal() && fila.productoId() != null) {
            jdbc.update("""
                    UPDATE imagen SET principal = true
                    WHERE id = (SELECT id FROM imagen WHERE producto_id = ? ORDER BY orden, subida_en LIMIT 1)""",
                    fila.productoId());
        }
        try {
            storage.eliminar(fila.clave());
            storage.eliminar(fila.miniatura());
        } catch (RuntimeException e) {
            // El registro ya no existe; un archivo huerfano en el bucket no rompe nada
            log.warn("No se pudo borrar del bucket la imagen {}: {}", id, e.getMessage());
        }
    }

    @Transactional
    public void marcarPrincipal(long productorId, UUID id) {
        Long productoId = jdbc.query("SELECT producto_id FROM imagen WHERE id = ? AND productor_id = ? AND tipo = 'PRODUCTO'",
                        (rs, row) -> rs.getLong(1), id, productorId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("IMAGEN_NO_ENCONTRADA", "La imagen no existe."));
        jdbc.update("UPDATE imagen SET principal = false WHERE producto_id = ? AND principal", productoId);
        jdbc.update("UPDATE imagen SET principal = true WHERE id = ?", id);
    }

    public List<Imagen> deProducto(long productoId) {
        return jdbc.query(SELECT + " WHERE producto_id = ? ORDER BY principal DESC, orden, subida_en",
                ImagenService::mapear, productoId);
    }

    public List<Imagen> deTienda(long productorId) {
        return jdbc.query(SELECT + " WHERE productor_id = ? AND tipo IN ('PORTADA', 'GALERIA') ORDER BY orden, subida_en",
                ImagenService::mapear, productorId);
    }

    public Optional<Imagen> buscar(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", ImagenService::mapear, id).stream().findFirst();
    }

    /** Bytes para servir la imagen publica: completa o miniatura. */
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
                if (contar("producto_id = ?", productoId) >= MAX_FOTOS_PRODUCTO) {
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
