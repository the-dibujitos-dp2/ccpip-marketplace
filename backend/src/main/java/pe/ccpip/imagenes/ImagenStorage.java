package pe.ccpip.imagenes;

import java.util.Optional;

/**
 * Donde viven los archivos de imagen. La implementacion real es {@link S3ImagenStorage};
 * cambiar de proveedor solo requiere otra implementacion.
 */
public interface ImagenStorage {

    void guardar(String clave, byte[] contenido, String contentType);

    Optional<byte[]> leer(String clave);

    void eliminar(String clave);
}
