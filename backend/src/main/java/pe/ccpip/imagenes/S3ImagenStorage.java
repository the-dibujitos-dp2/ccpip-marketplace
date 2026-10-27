package pe.ccpip.imagenes;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
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
            // S3Mock y otros emuladores: direcciones con el bucket en la ruta y peticiones sin firmar
            // (el emulador no valida credenciales). Asi nadie necesita AWS configurado en su
            // maquina. En prod no hay endpoint y se usan las credenciales del rol de la EC2.
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true)
                    .credentialsProvider(AnonymousCredentialsProvider.create());
        }
        s3 = builder.build();
        if (crearBucket) {
            prepararBucket();
        }
    }

    private void prepararBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
            log.info("Bucket de imagenes {} creado", bucket);
        } catch (SdkClientException e) {
            throw new IllegalStateException("No se pudo conectar al almacenamiento de imagenes en " + endpoint
                    + ". En desarrollo, levanta S3Mock con 'docker compose up -d' desde la raiz del repo.", e);
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

    @Override
    public void guardar(String clave, Path archivo, String contentType) {
        s3.putObject(b -> b.bucket(bucket).key(clave).contentType(contentType), RequestBody.fromFile(archivo));
    }

    @Override
    public OptionalLong tamano(String clave) {
        try {
            return OptionalLong.of(s3.headObject(b -> b.bucket(bucket).key(clave)).contentLength());
        } catch (NoSuchKeyException e) {
            return OptionalLong.empty();
        }
    }

    @Override
    public InputStream leerRango(String clave, long desde, long hasta) {
        return s3.getObject(b -> b.bucket(bucket).key(clave).range("bytes=" + desde + "-" + hasta));
    }
}
