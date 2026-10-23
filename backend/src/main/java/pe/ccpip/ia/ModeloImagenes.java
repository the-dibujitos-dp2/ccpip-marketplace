package pe.ccpip.ia;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Modelo propio de imagenes (CCPIP-144, RNF-48) ejecutado en Java puro, sin servicios externos ni
 * librerias nativas (la imagen del backend es Alpine). Lee el archivo que arma
 * {@code ia/modelo-imagenes/exportar.py}: un extractor de caracteristicas MobileNetV2 y una cabeza
 * propia que reparte la foto entre las lineas de CCPIP. Inmutable y seguro entre hilos.
 */
public final class ModeloImagenes {

    static final String RECURSO = "ia/modelo-imagenes.bin";
    private static final byte[] MAGIA = "CCPIPIA1".getBytes(StandardCharsets.US_ASCII);
    static final String OTRO = "OTRO";

    /** Una capa del extractor, en el orden del grafo. */
    private record Nodo(String op, String entrada, String entrada2, String salida, float[] w, int[] formaW, float[] b,
                        int paso, int relleno, int grupos, float min, float max) {
    }

    /** Mapa de activacion canales x alto x ancho, guardado por canal. */
    private record Mapa(int c, int h, int w, float[] d) {
    }

    /** Lo que el modelo ve en una foto: probabilidad por linea y la etiqueta mas probable. */
    public record Prediccion(Map<String, Double> porLinea, String etiqueta, double probabilidadEtiqueta) {
    }

    private final List<Nodo> nodos;
    private final String caracteristicas;
    private final int tamano;
    private final float[] media;
    private final float[] desviacion;
    private final float[] cabezaW;
    private final float[] cabezaB;
    private final String[] lineaDeFila;
    private final String[] etiquetaDeFila;
    private final String version;
    /** Por cada salida de capa, las activaciones que se usan por ultima vez en esa capa. */
    private final Map<String, List<String>> liberar;

    private ModeloImagenes(List<Nodo> nodos, String caracteristicas, int tamano, float[] media, float[] desviacion,
                           float[] cabezaW, float[] cabezaB, String[] lineaDeFila, String[] etiquetaDeFila, String version) {
        this.nodos = nodos;
        this.caracteristicas = caracteristicas;
        this.tamano = tamano;
        this.media = media;
        this.desviacion = desviacion;
        this.cabezaW = cabezaW;
        this.cabezaB = cabezaB;
        this.lineaDeFila = lineaDeFila;
        this.etiquetaDeFila = etiquetaDeFila;
        this.version = version;
        Map<String, String> ultimoUso = new HashMap<>();
        for (Nodo n : nodos) {
            ultimoUso.put(n.entrada(), n.salida());
            if (n.entrada2() != null) {
                ultimoUso.put(n.entrada2(), n.salida());
            }
        }
        ultimoUso.remove(caracteristicas);
        Map<String, List<String>> porCapa = new HashMap<>();
        ultimoUso.forEach((valor, capa) -> porCapa.computeIfAbsent(capa, x -> new ArrayList<>()).add(valor));
        this.liberar = Map.copyOf(porCapa);
    }

    /** Carga el modelo empaquetado en el jar. */
    public static ModeloImagenes cargar() {
        try (InputStream in = ModeloImagenes.class.getClassLoader().getResourceAsStream(RECURSO)) {
            if (in == null) {
                throw new IllegalStateException("No se encontró el modelo de imágenes " + RECURSO);
            }
            return leer(in);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el modelo de imágenes", e);
        }
    }

    static ModeloImagenes leer(InputStream origen) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(origen, 1 << 16))) {
            return leer(in);
        }
    }

    private static ModeloImagenes leer(DataInputStream in) throws IOException {
        byte[] magia = in.readNBytes(MAGIA.length);
        if (!Arrays.equals(magia, MAGIA)) {
            throw new IOException("Formato de modelo desconocido");
        }
        byte[] js = in.readNBytes(in.readInt());
        JsonNode cab = new ObjectMapper().readTree(js);
        List<float[]> tensores = new ArrayList<>();
        List<int[]> formas = new ArrayList<>();
        for (JsonNode t : cab.path("tensores")) {
            int[] forma = new int[t.path("forma").size()];
            int n = 1;
            for (int i = 0; i < forma.length; i++) {
                forma[i] = t.path("forma").get(i).asInt();
                n *= forma[i];
            }
            byte[] crudo = in.readNBytes(n * 2);
            if (crudo.length != n * 2) {
                throw new IOException("Modelo de imágenes incompleto");
            }
            float[] v = new float[n];
            for (int i = 0; i < n; i++) {
                v[i] = Float.float16ToFloat((short) (((crudo[2 * i] & 0xff) << 8) | (crudo[2 * i + 1] & 0xff)));
            }
            tensores.add(v);
            formas.add(forma);
        }
        List<Nodo> nodos = new ArrayList<>();
        for (JsonNode n : cab.path("nodos")) {
            String op = n.path("op").asText();
            int iw = n.path("w").asInt(-1);
            int ib = n.path("b").asInt(-1);
            nodos.add(new Nodo(op, n.path("entrada").asText(), n.path("entrada2").asText(null), n.path("salida").asText(),
                    iw < 0 ? null : tensores.get(iw), iw < 0 ? null : formas.get(iw), ib < 0 ? null : tensores.get(ib),
                    n.path("paso").asInt(1), n.path("relleno").asInt(0), n.path("grupos").asInt(1),
                    (float) n.path("min").asDouble(0), (float) n.path("max").asDouble(0)));
        }
        JsonNode entrada = cab.path("entrada");
        JsonNode cabeza = cab.path("cabeza");
        String[] lineas = new String[cabeza.path("lineas").size()];
        String[] etiquetas = new String[lineas.length];
        for (int i = 0; i < lineas.length; i++) {
            lineas[i] = cabeza.path("lineas").get(i).asText();
            etiquetas[i] = cabeza.path("etiquetas").path(i).asText(lineas[i]);
        }
        return new ModeloImagenes(List.copyOf(nodos), cab.path("caracteristicas").asText(), entrada.path("tamano").asInt(),
                floats(entrada.path("media")), floats(entrada.path("desviacion")),
                tensores.get(cabeza.path("w").asInt()), tensores.get(cabeza.path("b").asInt()), lineas, etiquetas,
                cabeza.path("version").asText());
    }

    public String version() {
        return version;
    }

    /** Clasifica una foto: redimensiona, recorta al centro, normaliza y ejecuta la red. */
    public Prediccion predecir(BufferedImage imagen) {
        return clasificar(caracteristicas(entrada(imagen)));
    }

    /** Las probabilidades de cada fila de la cabeza agrupadas por linea; OTRO junta lo que no es de ninguna. */
    Prediccion clasificar(float[] f) {
        int filas = cabezaB.length;
        double[] logit = new double[filas];
        double maximo = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < filas; r++) {
            double s = cabezaB[r];
            int base = r * f.length;
            for (int i = 0; i < f.length; i++) {
                s += cabezaW[base + i] * f[i];
            }
            logit[r] = s;
            maximo = Math.max(maximo, s);
        }
        double suma = 0;
        for (int r = 0; r < filas; r++) {
            logit[r] = Math.exp(logit[r] - maximo);
            suma += logit[r];
        }
        Map<String, Double> porLinea = new LinkedHashMap<>();
        int mejor = 0;
        for (int r = 0; r < filas; r++) {
            double p = logit[r] / suma;
            porLinea.merge(lineaDeFila[r], p, Double::sum);
            if (p > logit[mejor] / suma) {
                mejor = r;
            }
        }
        return new Prediccion(porLinea, etiquetaDeFila[mejor], logit[mejor] / suma);
    }

    /** Tensor de entrada 3 x tamano x tamano, como en el entrenamiento de ImageNet. */
    float[] entrada(BufferedImage imagen) {
        int w = imagen.getWidth();
        int h = imagen.getHeight();
        // Lado corto a tamano*256/224 y recorte central, como torchvision
        double escala = Math.ceil(tamano * 256.0 / 224) / Math.min(w, h);
        int nw = Math.max(tamano, (int) Math.round(w * escala));
        int nh = Math.max(tamano, (int) Math.round(h * escala));
        BufferedImage lienzo = new BufferedImage(tamano, tamano, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = lienzo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(imagen, -(nw - tamano) / 2, -(nh - tamano) / 2, nw, nh, null);
        g.dispose();
        int[] px = lienzo.getRGB(0, 0, tamano, tamano, null, 0, tamano);
        int plano = tamano * tamano;
        float[] x = new float[3 * plano];
        for (int i = 0; i < plano; i++) {
            int p = px[i];
            x[i] = (((p >> 16) & 0xff) / 255f - media[0]) / desviacion[0];
            x[plano + i] = (((p >> 8) & 0xff) / 255f - media[1]) / desviacion[1];
            x[2 * plano + i] = ((p & 0xff) / 255f - media[2]) / desviacion[2];
        }
        return x;
    }

    /** Ejecuta el extractor y devuelve el vector de caracteristicas (1280 en MobileNetV2). */
    float[] caracteristicas(float[] x) {
        Map<String, Mapa> valores = new HashMap<>();
        String primera = nodos.get(0).entrada();
        valores.put(primera, new Mapa(3, tamano, tamano, x));
        for (Nodo n : nodos) {
            Mapa a = valores.get(n.entrada());
            Mapa r = switch (n.op()) {
                case "Conv" -> conv(a, n);
                case "Clip" -> clip(a, n.min(), n.max());
                case "Add" -> sumar(a, valores.get(n.entrada2()));
                case "GlobalAveragePool" -> promedio(a);
                default -> throw new IllegalStateException("Operación no soportada: " + n.op());
            };
            valores.put(n.salida(), r);
            // Libera lo que ya no se usa: sin esto una foto retendria todas las activaciones
            List<String> libres = liberar.get(n.salida());
            if (libres != null) {
                libres.forEach(valores::remove);
            }
        }
        return valores.get(caracteristicas).d();
    }

    private static Mapa conv(Mapa a, Nodo n) {
        int m = n.formaW()[0];
        int cg = n.formaW()[1];
        int k = n.formaW()[2];
        int s = n.paso();
        int p = n.relleno();
        int ho = (a.h() + 2 * p - k) / s + 1;
        int wo = (a.w() + 2 * p - k) / s + 1;
        float[] out = new float[m * ho * wo];
        float[] w = n.w();
        float[] in = a.d();
        int planoIn = a.h() * a.w();
        int planoOut = ho * wo;
        if (k == 1 && s == 1 && p == 0 && n.grupos() == 1) {
            // Convolucion 1x1: producto de matrices por canal, el bucle interno recorre memoria contigua
            for (int o = 0; o < m; o++) {
                int baseOut = o * planoOut;
                Arrays.fill(out, baseOut, baseOut + planoOut, n.b()[o]);
                for (int c = 0; c < cg; c++) {
                    float peso = w[o * cg + c];
                    int baseIn = c * planoIn;
                    for (int i = 0; i < planoOut; i++) {
                        out[baseOut + i] += peso * in[baseIn + i];
                    }
                }
            }
            return new Mapa(m, ho, wo, out);
        }
        int mPorGrupo = m / n.grupos();
        for (int o = 0; o < m; o++) {
            int grupo = o / mPorGrupo;
            float sesgo = n.b()[o];
            for (int y = 0; y < ho; y++) {
                for (int xo = 0; xo < wo; xo++) {
                    float suma = sesgo;
                    for (int c = 0; c < cg; c++) {
                        int canal = grupo * cg + c;
                        int baseIn = canal * planoIn;
                        int baseW = ((o * cg + c) * k) * k;
                        for (int ky = 0; ky < k; ky++) {
                            int iy = y * s - p + ky;
                            if (iy < 0 || iy >= a.h()) {
                                continue;
                            }
                            int fila = baseIn + iy * a.w();
                            for (int kx = 0; kx < k; kx++) {
                                int ix = xo * s - p + kx;
                                if (ix >= 0 && ix < a.w()) {
                                    suma += w[baseW + ky * k + kx] * in[fila + ix];
                                }
                            }
                        }
                    }
                    out[o * planoOut + y * wo + xo] = suma;
                }
            }
        }
        return new Mapa(m, ho, wo, out);
    }

    private static Mapa clip(Mapa a, float min, float max) {
        float[] r = new float[a.d().length];
        for (int i = 0; i < r.length; i++) {
            r[i] = Math.min(max, Math.max(min, a.d()[i]));
        }
        return new Mapa(a.c(), a.h(), a.w(), r);
    }

    private static Mapa sumar(Mapa a, Mapa b) {
        float[] r = new float[a.d().length];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.d()[i] + b.d()[i];
        }
        return new Mapa(a.c(), a.h(), a.w(), r);
    }

    private static Mapa promedio(Mapa a) {
        int plano = a.h() * a.w();
        float[] r = new float[a.c()];
        for (int c = 0; c < a.c(); c++) {
            double s = 0;
            for (int i = 0; i < plano; i++) {
                s += a.d()[c * plano + i];
            }
            r[c] = (float) (s / plano);
        }
        return new Mapa(a.c(), 1, 1, r);
    }

    private static float[] floats(JsonNode arr) {
        float[] r = new float[arr.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = (float) arr.get(i).asDouble();
        }
        return r;
    }
}
