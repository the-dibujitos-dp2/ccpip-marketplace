package pe.ccpip.video;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.http.HttpStatus;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-118 (RNF-25): reconoce un video MP4 o WebM por su contenido, no por el nombre ni por lo que dice el
 * navegador, y lee su duracion de la cabecera del contenedor: {@code mvhd} en MP4 y {@code Duration} de
 * {@code Info} en WebM. No decodifica el video: solo recorre las cajas o los elementos EBML, sin cargar el
 * archivo en memoria. Cada recorrido avanza siempre y tiene un tope de elementos, para que un archivo armado
 * a proposito no lo deje girando.
 */
final class FormatoVideo {

    enum Formato {
        MP4("video/mp4", "mp4"), WEBM("video/webm", "webm");

        final String contentType;
        final String extension;

        Formato(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    record Leido(Formato formato, BigDecimal duracionSegundos) {
    }

    private static final int EBML = 0x1A45DFA3;
    private static final int DOC_TYPE = 0x4282;
    private static final int SEGMENTO = 0x18538067;
    private static final int INFO = 0x1549A966;
    private static final int ESCALA = 0x2AD7B1;
    private static final int DURACION = 0x4489;
    private static final int CLUSTER = 0x1F43B675;
    /** Cuantas cajas o elementos se recorren como maximo en cada nivel: un archivo armado no hace girar sin fin. */
    private static final int MAX_ELEMENTOS = 10_000;

    private FormatoVideo() {
    }

    /** El formato y la duracion del archivo, o un error que el productor entiende. */
    static Leido leer(Path archivo) {
        try (RandomAccessFile f = new RandomAccessFile(archivo.toFile(), "r")) {
            Formato formato = f.length() >= 12 && esMp4(f) ? Formato.MP4 : esWebm(f) ? Formato.WEBM : null;
            if (formato == null) {
                throw noReconocido();
            }
            // Un WebM grabado en el navegador suele no llevar su duracion: no se puede comprobar el limite
            return (formato == Formato.MP4 ? mp4(f) : webm(f)).map(d -> new Leido(formato, d))
                    .orElseThrow(() -> new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_SIN_DURACION",
                            "No pudimos leer la duración del video. Guárdalo de nuevo como MP4 e inténtalo otra vez."));
        } catch (IOException | RuntimeException e) {
            if (e instanceof ErrorNegocio negocio) {
                throw negocio;
            }
            throw noReconocido();
        }
    }

    static ErrorNegocio noReconocido() {
        return new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "VIDEO_NO_RECONOCIDO",
                "No reconocimos el video. Sube un archivo MP4 o WebM.");
    }

    // ---------- MP4 ----------

    /** Un MP4 empieza con la caja ftyp. QuickTime (qt) no se reproduce en todos los navegadores. */
    private static boolean esMp4(RandomAccessFile f) throws IOException {
        f.seek(4);
        byte[] tipo = new byte[8];
        f.readFully(tipo);
        String caja = new String(tipo, 0, 4, StandardCharsets.ISO_8859_1);
        String marca = new String(tipo, 4, 4, StandardCharsets.ISO_8859_1);
        return "ftyp".equals(caja) && !"qt  ".equals(marca);
    }

    private static Optional<BigDecimal> mp4(RandomAccessFile f) throws IOException {
        Optional<long[]> moov = caja(f, 0, f.length(), "moov");
        if (moov.isEmpty()) {
            return Optional.empty();
        }
        Optional<long[]> mvhd = caja(f, moov.get()[0], moov.get()[1], "mvhd");
        if (mvhd.isEmpty()) {
            return Optional.empty();
        }
        f.seek(mvhd.get()[0]);
        int version = f.readUnsignedByte();
        f.skipBytes(3);
        long escala;
        long duracion;
        if (version == 1) {
            f.skipBytes(16);
            escala = f.readInt() & 0xFFFFFFFFL;
            duracion = f.readLong();
        } else {
            f.skipBytes(8);
            escala = f.readInt() & 0xFFFFFFFFL;
            duracion = f.readInt() & 0xFFFFFFFFL;
        }
        if (escala == 0 || duracion <= 0) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(duracion).divide(BigDecimal.valueOf(escala), 2, RoundingMode.HALF_UP));
    }

    /** Busca una caja entre {@code inicio} y {@code fin}; devuelve el inicio y el fin de su contenido. */
    private static Optional<long[]> caja(RandomAccessFile f, long inicio, long fin, String buscada) throws IOException {
        long pos = inicio;
        for (int paso = 0; pos + 8 <= fin; paso++) {
            limitar(paso);
            f.seek(pos);
            long tamano = f.readInt() & 0xFFFFFFFFL;
            byte[] tipo = new byte[4];
            f.readFully(tipo);
            long cabecera = 8;
            if (tamano == 1) {
                tamano = f.readLong();
                cabecera = 16;
            } else if (tamano == 0) {
                tamano = fin - pos;
            }
            if (tamano < cabecera) {
                return Optional.empty();
            }
            if (buscada.equals(new String(tipo, StandardCharsets.ISO_8859_1))) {
                return Optional.of(new long[] {pos + cabecera, Math.min(pos + tamano, fin)});
            }
            pos += tamano;
        }
        return Optional.empty();
    }

    // ---------- WebM (EBML) ----------

    private static boolean esWebm(RandomAccessFile f) throws IOException {
        f.seek(0);
        if (f.length() < 4 || f.readInt() != EBML) {
            return false;
        }
        f.seek(0);
        long[] cabecera = elemento(f);
        long fin = cabecera[1] + cabecera[2];
        long pos = cabecera[1];
        for (int paso = 0; pos < fin; paso++) {
            limitar(paso);
            f.seek(pos);
            long[] e = elemento(f);
            if (e[0] == DOC_TYPE && e[2] >= 0) {
                byte[] tipo = new byte[(int) Math.min(e[2], 16)];
                f.seek(e[1]);
                f.readFully(tipo);
                return "webm".equals(new String(tipo, StandardCharsets.US_ASCII).trim());
            }
            pos = siguiente(e, pos);
        }
        return false;
    }

    private static Optional<BigDecimal> webm(RandomAccessFile f) throws IOException {
        f.seek(0);
        long[] cabecera = elemento(f);
        f.seek(cabecera[1] + cabecera[2]);
        long[] segmento = elemento(f);
        if (segmento[0] != SEGMENTO) {
            return Optional.empty();
        }
        long fin = segmento[2] < 0 ? f.length() : Math.min(f.length(), segmento[1] + segmento[2]);
        long pos = segmento[1];
        for (int paso = 0; pos < fin; paso++) {
            limitar(paso);
            f.seek(pos);
            long[] e = elemento(f);
            if (e[0] == CLUSTER || e[2] < 0) {
                // La informacion va antes de los datos del video
                return Optional.empty();
            }
            if (e[0] == INFO) {
                return duracionWebm(f, e[1], Math.min(fin, e[1] + e[2]));
            }
            pos = siguiente(e, pos);
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> duracionWebm(RandomAccessFile f, long inicio, long fin) throws IOException {
        long escalaNs = 1_000_000;
        Double duracion = null;
        long pos = inicio;
        for (int paso = 0; pos < fin; paso++) {
            limitar(paso);
            f.seek(pos);
            long[] e = elemento(f);
            f.seek(e[1]);
            if (e[0] == ESCALA) {
                escalaNs = entero(f, e[2]);
            } else if (e[0] == DURACION) {
                duracion = flotante(f, e[2]);
            }
            pos = siguiente(e, pos);
        }
        if (duracion == null || !Double.isFinite(duracion) || duracion <= 0 || escalaNs <= 0) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(duracion * escalaNs / 1e9).setScale(2, RoundingMode.HALF_UP));
    }

    /** Donde empieza el elemento que sigue; un tamano desconocido o que no avanza hace invalido el archivo. */
    private static long siguiente(long[] e, long pos) throws IOException {
        long siguiente = e[1] + e[2];
        if (e[2] < 0 || siguiente <= pos) {
            throw new IOException("EBML invalido");
        }
        return siguiente;
    }

    private static void limitar(int paso) throws IOException {
        if (paso >= MAX_ELEMENTOS) {
            throw new IOException("Demasiados elementos");
        }
    }

    /** Lee un elemento EBML: su id, donde empieza su contenido y su tamano (-1 si es desconocido). */
    private static double flotante(RandomAccessFile f, long bytes) throws IOException {
        if (bytes == 4) {
            return f.readFloat();
        }
        if (bytes == 8) {
            return f.readDouble();
        }
        throw new IOException("Duracion EBML invalida");
    }

    private static long[] elemento(RandomAccessFile f) throws IOException {
        long id = vint(f, true);
        long tamano = vint(f, false);
        return new long[] {id, f.getFilePointer(), tamano};
    }

    /** Entero de longitud variable de EBML; el id conserva su marca de longitud, el tamano no. */
    private static long vint(RandomAccessFile f, boolean conMarca) throws IOException {
        int primero = f.readUnsignedByte();
        int largo = Integer.numberOfLeadingZeros(primero) - 23;
        if (largo < 1 || largo > 8) {
            throw new IOException("EBML invalido");
        }
        long valor = conMarca ? primero : primero & (0xFF >> largo);
        boolean desconocido = valor == (0xFF >> largo);
        for (int i = 1; i < largo; i++) {
            int b = f.readUnsignedByte();
            desconocido &= b == 0xFF;
            valor = (valor << 8) | b;
        }
        return !conMarca && desconocido ? -1 : valor;
    }

    private static long entero(RandomAccessFile f, long bytes) throws IOException {
        if (bytes < 1 || bytes > 8) {
            throw new IOException("Entero EBML invalido");
        }
        long valor = 0;
        for (int i = 0; i < bytes; i++) {
            valor = (valor << 8) | f.readUnsignedByte();
        }
        return valor;
    }
}
