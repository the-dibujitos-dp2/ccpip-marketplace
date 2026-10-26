package pe.ccpip.imagenes;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Donde viven los archivos de imagen. La implementacion real es {@link S3ImagenStorage};
 * cambiar de proveedor solo requiere otra implementacion.
 */
public interface ImagenStorage {

    void guardar(String clave, byte[] contenido, String contentType);

    Optional<byte[]> leer(String clave);

    void eliminar(String clave);

    /** Archivos grandes, como los videos de la tienda (CCPIP-118): se suben desde disco, sin pasar por memoria. */
    void guardar(String clave, Path archivo, String contentType);

    /** Tamano en bytes del archivo, o vacio si no existe. */
    OptionalLong tamano(String clave);

    /** Los bytes {@code desde}..{@code hasta} (inclusive) del archivo, para servirlo por partes (Range). */
    InputStream leerRango(String clave, long desde, long hasta);
}
