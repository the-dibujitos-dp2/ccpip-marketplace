package pe.ccpip.merma;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import pe.ccpip.merma.MermaModelos.ReporteMerma;

/**
 * PDF renderer of the canonical report rows. It embeds Noto's text and emoji faces instead of
 * pretending that a Standard 14 font can render Unicode through Identity-H.
 */
final class MermaPdf {
    private static final String NOTO_TEXTO = "/fonts/noto-sans-sc-variable.ttf";
    private static final String NOTO_EMOJI = "/fonts/noto-emoji-variable.ttf";
    private static final float MARGEN_IZQUIERDO = 42;
    private static final float MARGEN_INFERIOR = 42;
    private static final float Y_INICIAL = 794;
    private static final float TAMANO = 9;
    private static final float ALTO_LINEA = 14;
    private static final int CARACTERES_POR_LINEA = 104;

    private MermaPdf() { }

    static byte[] generar(ReporteMerma reporte) {
        try (PDDocument documento = new PDDocument(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            PDType0Font texto = fuente(documento, NOTO_TEXTO);
            PDType0Font emoji = fuente(documento, NOTO_EMOJI);
            Pagina pagina = new Pagina(documento, texto, emoji);
            for (ReporteMermaContenido.Fila fila : ReporteMermaContenido.filas(reporte)) {
                for (String linea : lineas(fila.texto())) {
                    pagina.escribir(linea);
                }
            }
            pagina.cerrar();
            documento.save(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF de merma", e);
        }
    }

    private static PDType0Font fuente(PDDocument documento, String recurso) throws IOException {
        try (InputStream entrada = MermaPdf.class.getResourceAsStream(recurso)) {
            if (entrada == null) {
                throw new IOException("No se encontró la fuente Unicode " + recurso);
            }
            return PDType0Font.load(documento, entrada, true);
        }
    }

    /**
     * Layout line breaks are controls, not glyphs. Split them before choosing a font so LF, CRLF,
     * CR and the other Unicode line separators survive as PDF text lines instead of reaching Tj.
     */
    private static List<String> lineas(String texto) {
        List<String> lineas = new ArrayList<>();
        for (String lineaFisica : texto.split("\\R", -1)) {
            envolver(lineaFisica, lineas);
        }
        return lineas;
    }

    private static void envolver(String texto, List<String> lineas) {
        StringBuilder actual = new StringBuilder();
        int usados = 0;
        for (String palabra : texto.split("(?<=\\s)|(?=\\s)")) {
            int longitud = palabra.codePointCount(0, palabra.length());
            if (usados > 0 && usados + longitud > CARACTERES_POR_LINEA) {
                lineas.add(actual.toString());
                actual.setLength(0);
                usados = 0;
            }
            actual.append(palabra);
            usados += longitud;
        }
        lineas.add(actual.toString());
    }

    private static final class Pagina {
        private final PDDocument documento;
        private final PDType0Font texto;
        private final PDType0Font emoji;
        private PDPageContentStream contenido;
        private float y = Y_INICIAL;

        private Pagina(PDDocument documento, PDType0Font texto, PDType0Font emoji) throws IOException {
            this.documento = documento;
            this.texto = texto;
            this.emoji = emoji;
            nueva();
        }

        private void escribir(String linea) throws IOException {
            if (y < MARGEN_INFERIOR) {
                contenido.close();
                nueva();
            }
            contenido.beginText();
            contenido.newLineAtOffset(MARGEN_IZQUIERDO, y);
            StringBuilder segmento = new StringBuilder();
            PDType0Font fuenteActual = null;
            for (int posicion = 0; posicion < linea.length();) {
                int codigo = linea.codePointAt(posicion);
                PDType0Font fuente = seleccionar(codigo);
                if (fuenteActual != null && fuente != fuenteActual) {
                    contenido.setFont(fuenteActual, TAMANO);
                    contenido.showText(segmento.toString());
                    segmento.setLength(0);
                }
                segmento.appendCodePoint(codigo);
                fuenteActual = fuente;
                posicion += Character.charCount(codigo);
            }
            if (!segmento.isEmpty()) {
                contenido.setFont(fuenteActual, TAMANO);
                contenido.showText(segmento.toString());
            }
            contenido.endText();
            y -= ALTO_LINEA;
        }

        private PDType0Font seleccionar(int codigo) {
            if (emoji.getCmapLookup().getGlyphId(codigo) != 0) {
                return emoji;
            }
            if (texto.getCmapLookup().getGlyphId(codigo) != 0) {
                return texto;
            }
            throw new IllegalArgumentException("El carácter Unicode U+%04X no tiene glifo exportable".formatted(codigo));
        }

        private void nueva() throws IOException {
            documento.addPage(new PDPage(PDRectangle.A4));
            contenido = new PDPageContentStream(documento, documento.getPage(documento.getNumberOfPages() - 1));
            y = Y_INICIAL;
        }

        private void cerrar() throws IOException {
            contenido.close();
        }
    }
}
