package pe.ccpip.imagenes;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-44: valida formato y peso, corrige la orientacion de las fotos de celular y las
 * comprime y redimensiona para conexiones lentas. Volver a codificar la imagen ademas
 * descarta los metadatos EXIF, incluida la ubicacion GPS de la comunidad.
 */
@Component
public class ProcesadorImagen {

    public static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int LADO_MAXIMO = 1600;
    static final int LADO_MINIATURA = 480;
    /** Evita que una imagen pequena en bytes pero gigante en pixeles agote la memoria. */
    static final long MAX_PIXELES = 40_000_000L;
    private static final Set<String> FORMATOS = Set.of("jpeg", "png", "webp");

    public record Procesada(byte[] imagen, byte[] miniatura, int ancho, int alto) {

        // Un record compara los arreglos por referencia: se compara su contenido
        @Override
        public boolean equals(Object o) {
            return o instanceof Procesada p && ancho == p.ancho && alto == p.alto
                    && Arrays.equals(imagen, p.imagen) && Arrays.equals(miniatura, p.miniatura);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(imagen), Arrays.hashCode(miniatura), ancho, alto);
        }

        @Override
        public String toString() {
            return "Procesada[" + ancho + "x" + alto + ", " + imagen.length + " bytes, miniatura " + miniatura.length + " bytes]";
        }
    }

    public Procesada procesar(byte[] archivo) {
        if (archivo == null || archivo.length == 0) {
            throw ErrorNegocio.invalido("ARCHIVO_VACIO", "El archivo está vacío.");
        }
        if (archivo.length > MAX_BYTES) {
            throw ErrorNegocio.invalido("ARCHIVO_MUY_PESADO", "El archivo pesa más de 10 MB. Elige una foto más liviana.");
        }
        BufferedImage original = orientar(leer(archivo), orientacionExif(archivo));
        BufferedImage imagen = redimensionar(original, LADO_MAXIMO);
        BufferedImage miniatura = redimensionar(original, LADO_MINIATURA);
        return new Procesada(jpeg(imagen, 0.85f), jpeg(miniatura, 0.8f), imagen.getWidth(), imagen.getHeight());
    }

    /** CCPIP-176: comprime y genera la miniatura de una imagen ya decodificada (la variante mejorada). */
    public Procesada desde(BufferedImage original) {
        BufferedImage imagen = redimensionar(original, LADO_MAXIMO);
        BufferedImage miniatura = redimensionar(original, LADO_MINIATURA);
        return new Procesada(jpeg(imagen, 0.85f), jpeg(miniatura, 0.8f), imagen.getWidth(), imagen.getHeight());
    }

    /** Decodifica una foto que ya paso por {@link #procesar}, leida del bucket. */
    public static BufferedImage leerGuardada(byte[] jpeg) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) {
                throw new IllegalStateException("La imagen guardada no se pudo leer");
            }
            return img;
        } catch (IOException e) {
            throw new IllegalStateException("La imagen guardada no se pudo leer", e);
        }
    }

    private static BufferedImage leer(byte[] archivo) {
        try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(archivo))) {
            Iterator<ImageReader> lectores = ImageIO.getImageReaders(entrada);
            if (!lectores.hasNext()) {
                throw formatoInvalido();
            }
            ImageReader lector = lectores.next();
            try {
                if (!FORMATOS.contains(lector.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw formatoInvalido();
                }
                lector.setInput(entrada, true, true);
                if ((long) lector.getWidth(0) * lector.getHeight(0) > MAX_PIXELES) {
                    throw ErrorNegocio.invalido("IMAGEN_MUY_GRANDE", "La imagen tiene demasiados píxeles. Usa una de menos de 40 megapíxeles.");
                }
                return lector.read(0);
            } finally {
                lector.dispose();
            }
        } catch (IOException e) {
            throw ErrorNegocio.invalido("IMAGEN_DANADA", "No pudimos leer la imagen. Puede estar dañada; prueba con otra.");
        }
    }

    private static ErrorNegocio formatoInvalido() {
        return ErrorNegocio.invalido("FORMATO_NO_PERMITIDO", "Solo se aceptan fotos JPG, PNG o WebP.");
    }

    private static int orientacionExif(byte[] archivo) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(archivo));
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            // Sin EXIF legible se usa la imagen tal cual
        }
        return 1;
    }

    /** Aplica la orientacion EXIF (1 a 8) para que la foto no se vea girada. */
    static BufferedImage orientar(BufferedImage img, int orientacion) {
        if (orientacion <= 1 || orientacion > 8) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientacion) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { return img; }
        }
        boolean gira = orientacion >= 5;
        BufferedImage destino = new BufferedImage(gira ? h : w, gira ? w : h, BufferedImage.TYPE_INT_RGB);
        return new AffineTransformOp(t, AffineTransformOp.TYPE_BICUBIC).filter(rgb(img), destino);
    }

    private static BufferedImage redimensionar(BufferedImage img, int ladoMaximo) {
        int w = img.getWidth();
        int h = img.getHeight();
        double escala = Math.min(1.0, (double) ladoMaximo / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * escala));
        int nh = Math.max(1, (int) Math.round(h * escala));
        BufferedImage destino = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = destino.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // Fondo blanco: un PNG con transparencia no queda negro al pasar a JPEG
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(img, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return destino;
    }

    private static BufferedImage rgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        return redimensionar(img, Math.max(img.getWidth(), img.getHeight()));
    }

    private static byte[] jpeg(BufferedImage img, float calidad) {
        ImageWriter escritor = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(salida)) {
            escritor.setOutput(out);
            ImageWriteParam param = escritor.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(calidad);
            escritor.write(null, new IIOImage(img, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo codificar la imagen", e);
        } finally {
            escritor.dispose();
        }
        return salida.toByteArray();
    }
}
