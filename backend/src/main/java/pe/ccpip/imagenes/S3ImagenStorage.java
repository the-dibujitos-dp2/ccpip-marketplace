package pe.ccpip.imagenes;

import java.net.URI;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Imagenes en un bucket S3. En el ambiente desplegado las credenciales salen del perfil de
 * la instancia EC2 (LabInstanceProfile de AWS Academy): no hay llaves en la configuracion.
 * En desarrollo, CI y pruebas apunta a S3Mock con {@code ccpip.imagenes.endpoint}.
 */
@Component
public class S3ImagenStorage implements ImagenStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ImagenStorage.class);

    private final String bucket;
    private final String endpoint;
    private final String region;
    private final boolean crearBucket;
    private S3Client s3;

    public S3ImagenStorage(@Value("${ccpip.imagenes.bucket}") String bucket,
                           @Value("${ccpip.imagenes.endpoint:}") String endpoint,
                           @Value("${ccpip.imagenes.region:us-east-1}") String region,
                           @Value("${ccpip.imagenes.crear-bucket:false}") boolean crearBucket) {
        this.bucket = bucket;
        this.endpoint = endpoint;
        this.region = region;
        this.crearBucket = crearBucket;
    }

    @PostConstruct
    void iniciar() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder());
        if (!endpoint.isBlank()) {
            // S3Mock y otros emuladores necesitan direcciones con el bucket en la ruta
            builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        s3 = builder.build();
        if (crearBucket) {
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("Bucket de imagenes {} creado", bucket);
            }
        }
    }

    @PreDestroy
    void cerrar() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Override
    public void guardar(String clave, byte[] contenido, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(clave).contentType(contentType), RequestBody.fromBytes(contenido));
    }

    @Override
    public Optional<byte[]> leer(String clave) {
        try {
            return Optional.of(s3.getObjectAsBytes(b -> b.bucket(bucket).key(clave)).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void eliminar(String clave) {
        s3.deleteObject(b -> b.bucket(bucket).key(clave));
    }
}
