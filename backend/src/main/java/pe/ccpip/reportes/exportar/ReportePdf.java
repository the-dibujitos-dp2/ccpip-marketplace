package pe.ccpip.reportes.exportar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

import pe.ccpip.reportes.exportar.DocumentoReporte.Seccion;

/**
 * CCPIP-115: el reporte de sus productos en PDF, A4 horizontal para que quepan las ocho columnas del
 * inventario. Cada seccion es una tabla con su encabezado; si no cabe, sigue en otra pagina y repite el
 * encabezado. Usa la fuente Noto incrustada que ya usa el reporte de merma (CCPIP-78), asi que se ven
 * bien las tildes, la ñ y los nombres en otros alfabetos.
 */
public final class ReportePdf {

    private static final String FUENTE = "/fonts/noto-sans-sc-variable.ttf";
    private static final PDRectangle HOJA = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGEN = 40;
    private static final float ANCHO_UTIL = HOJA.getWidth() - 2 * MARGEN;
    private static final float ALTO_FILA = 16;
    private static final float TEXTO = 8.5f;
    private static final float SUBTITULO = 11;
    private static final float TITULO = 15;
    /** La primera columna (periodo o producto) es mas ancha que las de cifras. */
    private static final float PESO_PRIMERA = 2.4f;

    private ReportePdf() {
    }

    public static byte[] generar(DocumentoReporte documento) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            PDType0Font fuente;
            try (InputStream entrada = ReportePdf.class.getResourceAsStream(FUENTE)) {
                if (entrada == null) {
                    throw new IOException("No se encontró la fuente " + FUENTE);
                }
                fuente = PDType0Font.load(pdf, entrada, true);
            }
            Lienzo lienzo = new Lienzo(pdf, fuente);
            lienzo.texto(documento.titulo(), TITULO, MARGEN);
            lienzo.bajar(TITULO + 6);
            for (String filtro : documento.filtros()) {
                lienzo.texto(filtro, TEXTO + 0.5f, MARGEN);
                lienzo.bajar(ALTO_FILA - 2);
            }
            for (Seccion seccion : documento.secciones()) {
                lienzo.seccion(seccion);
            }
            lienzo.cerrar();
            pdf.save(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF del reporte", e);
        }
    }

    private static final class Lienzo {
        private final PDDocument pdf;
        private final PDType0Font fuente;
        private PDPageContentStream contenido;
        private float y;

        Lienzo(PDDocument pdf, PDType0Font fuente) throws IOException {
            this.pdf = pdf;
            this.fuente = fuente;
            nuevaPagina();
        }

        void seccion(Seccion s) throws IOException {
            // Titulo, encabezado y al menos una fila juntos
            asegurar(SUBTITULO + 10 + ALTO_FILA * 2);
            bajar(10);
            texto(s.titulo(), SUBTITULO, MARGEN);
            bajar(SUBTITULO + 4);
            float[] anchos = anchos(s.encabezados().size());
            encabezado(s, anchos);
            if (s.filas().isEmpty()) {
                texto(s.vacio() == null ? "" : s.vacio(), TEXTO, MARGEN + 4);
                bajar(ALTO_FILA);
                return;
            }
            for (int i = 0; i < s.filas().size(); i++) {
                if (y - ALTO_FILA < MARGEN) {
                    nuevaPagina();
                    encabezado(s, anchos);
                }
                boolean total = s.conTotales() && i == s.filas().size() - 1;
                fila(s.filas().get(i), s.moneda(), anchos, total);
            }
        }

        private void encabezado(Seccion s, float[] anchos) throws IOException {
            contenido.setNonStrokingColor(0.93f, 0.91f, 0.87f);
            contenido.addRect(MARGEN, y - 5, ANCHO_UTIL, ALTO_FILA);
            contenido.fill();
            contenido.setNonStrokingColor(0f, 0f, 0f);
            List<Object> titulos = List.copyOf(s.encabezados());
            fila(titulos, s.moneda(), anchos, false);
        }

        private void fila(List<Object> celdas, List<Boolean> moneda, float[] anchos, boolean total) throws IOException {
            if (total) {
                contenido.setLineWidth(0.6f);
                contenido.moveTo(MARGEN, y + ALTO_FILA - 5);
                contenido.lineTo(MARGEN + ANCHO_UTIL, y + ALTO_FILA - 5);
                contenido.stroke();
            }
            float x = MARGEN;
            for (int c = 0; c < celdas.size(); c++) {
                Object celda = celdas.get(c);
                float ancho = anchos[c];
                if (celda instanceof BigDecimal cifra) {
                    String txt = DocumentoReporte.cifra(cifra, moneda.get(c));
                    float w = medir(txt, TEXTO);
                    texto(txt, TEXTO, x + ancho - 6 - w);
                } else {
                    String txt = recortar(String.valueOf(celda), ancho - 10);
                    // Los encabezados de las columnas de cifras van a la derecha, como sus cifras
                    boolean derecha = c > 0 && esEncabezado(celdas);
                    float pos = derecha ? x + ancho - 6 - medir(txt, TEXTO) : x + 4;
                    texto(txt, TEXTO, pos);
                }
                x += ancho;
            }
            bajar(ALTO_FILA);
        }

        /** Un encabezado solo trae textos. */
        private static boolean esEncabezado(List<Object> celdas) {
            return celdas.stream().noneMatch(BigDecimal.class::isInstance);
        }

        private float[] anchos(int columnas) {
            float unidad = ANCHO_UTIL / (PESO_PRIMERA + columnas - 1);
            float[] anchos = new float[columnas];
            anchos[0] = unidad * PESO_PRIMERA;
            for (int i = 1; i < columnas; i++) {
                anchos[i] = unidad;
            }
            return anchos;
        }

        /** Corta un texto largo con «…» para que no invada la columna siguiente. */
        private String recortar(String texto, float ancho) throws IOException {
            String limpio = imprimible(texto);
            if (medir(limpio, TEXTO) <= ancho) {
                return limpio;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limpio.length();) {
                int cp = limpio.codePointAt(i);
                if (medir(sb + new String(Character.toChars(cp)) + "…", TEXTO) > ancho) {
                    break;
                }
                sb.appendCodePoint(cp);
                i += Character.charCount(cp);
            }
            return sb + "…";
        }

        void texto(String texto, float tamano, float x) throws IOException {
            contenido.beginText();
            contenido.setFont(fuente, tamano);
            // La fuente variable se incrusta en su peso mas fino; un trazo del color del texto le da cuerpo
            // sin sumar otra fuente al proyecto. Los titulos, un poco mas gruesos.
            contenido.setRenderingMode(RenderingMode.FILL_STROKE);
            contenido.setLineWidth(tamano >= SUBTITULO ? 0.55f : 0.32f);
            contenido.newLineAtOffset(x, y);
            contenido.showText(imprimible(texto));
            contenido.endText();
        }

        /** Saltos de linea y caracteres sin glifo en la fuente se reemplazan, en vez de romper el PDF. */
        private String imprimible(String texto) throws IOException {
            StringBuilder sb = new StringBuilder();
            texto.codePoints().forEach(cp -> {
                if (Character.isISOControl(cp) || Character.getType(cp) == Character.LINE_SEPARATOR
                        || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR) {
                    sb.append(' ');
                } else if (fuente.getCmapLookup().getGlyphId(cp) == 0) {
                    sb.append('?');
                } else {
                    sb.appendCodePoint(cp);
                }
            });
            return sb.toString();
        }

        private float medir(String texto, float tamano) throws IOException {
            return fuente.getStringWidth(imprimible(texto)) / 1000 * tamano;
        }

        void bajar(float cuanto) {
            y -= cuanto;
        }

        private void asegurar(float alto) throws IOException {
            if (y - alto < MARGEN) {
                nuevaPagina();
            }
        }

        private void nuevaPagina() throws IOException {
            if (contenido != null) {
                contenido.close();
            }
            PDPage pagina = new PDPage(HOJA);
            pdf.addPage(pagina);
            contenido = new PDPageContentStream(pdf, pagina);
            y = HOJA.getHeight() - MARGEN - TITULO;
        }

        void cerrar() throws IOException {
            contenido.close();
        }
    }
}
