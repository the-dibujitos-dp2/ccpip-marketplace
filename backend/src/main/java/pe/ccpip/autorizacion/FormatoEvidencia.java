package pe.ccpip.autorizacion;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.http.HttpStatus;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-36: una evidencia es PDF, JPG o PNG. El formato se reconoce por los primeros bytes del
 * archivo, nunca por la extension ni por el tipo que declara el navegador.
 */
public enum FormatoEvidencia {

    PDF("application/pdf", "%PDF-".getBytes(StandardCharsets.US_ASCII)),
    JPG("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    PNG("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private final String contentType;
    private final byte[] firma;

    FormatoEvidencia(String contentType, byte[] firma) {
        this.contentType = contentType;
        this.firma = firma;
    }

    public String contentType() {
        return contentType;
    }

    public static FormatoEvidencia detectar(byte[] archivo) {
        if (archivo == null || archivo.length == 0) {
            throw ErrorNegocio.invalido("ARCHIVO_VACIO", "El archivo está vacío.");
        }
        if (archivo.length > MAX_BYTES) {
            throw new ErrorNegocio(HttpStatus.PAYLOAD_TOO_LARGE, "ARCHIVO_MUY_PESADO", "El archivo pesa más de 10 MB.");
        }
        for (FormatoEvidencia f : values()) {
            if (archivo.length >= f.firma.length && Arrays.equals(archivo, 0, f.firma.length, f.firma, 0, f.firma.length)) {
                return f;
            }
        }
        throw ErrorNegocio.invalido("FORMATO_NO_PERMITIDO", "Solo se aceptan archivos PDF, JPG o PNG.");
    }

    public static FormatoEvidencia deContentType(String contentType) {
        for (FormatoEvidencia f : values()) {
            if (f.contentType.equals(contentType)) {
                return f;
            }
        }
        throw new IllegalStateException("Formato de evidencia desconocido: " + contentType);
    }
}
