package pe.ccpip.reportes.exportar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import pe.ccpip.reportes.exportar.DocumentoReporte.Seccion;

/**
 * CCPIP-335: el reporte de sus productos en Excel (XLSX). Una hoja con los filtros y una por seccion, con
 * las cifras como numeros (no como texto) para que el productor pueda sumar, filtrar o graficar en su hoja
 * de calculo. Se escribe el formato Office Open XML a mano, como el reporte de merma (CCPIP-78), sin sumar
 * una libreria al proyecto.
 */
public final class ReporteXlsx {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** Estilos de celda (cellXfs) definidos en {@link #estilos()}. */
    private static final int NEGRITA = 1;
    private static final int MONEDA = 2;
    private static final int MONEDA_NEGRITA = 3;
    private static final int CANTIDAD_NEGRITA = 4;
    private static final int TITULO = 5;

    private ReporteXlsx() {
    }

    public static byte[] generar(DocumentoReporte documento) {
        List<String> nombres = new ArrayList<>();
        List<String> hojas = new ArrayList<>();
        nombres.add(nombreHoja(documento.titulo()));
        hojas.add(hojaFiltros(documento));
        for (Seccion s : documento.secciones()) {
            nombres.add(nombreHoja(s.titulo()));
            hojas.add(hojaSeccion(s));
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            StringBuilder tipos = new StringBuilder(XML)
                    .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                    .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                    .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                    .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                    .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder libro = new StringBuilder(XML).append("<workbook xmlns=\"").append(MAIN).append("\" xmlns:r=\"")
                    .append(REL).append("\"><sheets>");
            StringBuilder relaciones = new StringBuilder(XML)
                    .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < hojas.size(); i++) {
                int n = i + 1;
                tipos.append("<Override PartName=\"/xl/worksheets/sheet").append(n)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                libro.append("<sheet name=\"").append(escapar(nombres.get(i))).append("\" sheetId=\"").append(n)
                        .append("\" r:id=\"rId").append(n).append("\"/>");
                relaciones.append("<Relationship Id=\"rId").append(n).append("\" Type=\"").append(REL)
                        .append("/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");
            }
            relaciones.append("<Relationship Id=\"rId").append(hojas.size() + 1).append("\" Type=\"").append(REL)
                    .append("/styles\" Target=\"styles.xml\"/></Relationships>");
            entrada(zip, "[Content_Types].xml", tipos.append("</Types>").toString());
            entrada(zip, "_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entrada(zip, "xl/workbook.xml", libro.append("</sheets></workbook>").toString());
            entrada(zip, "xl/_rels/workbook.xml.rels", relaciones.toString());
            entrada(zip, "xl/styles.xml", estilos());
            for (int i = 0; i < hojas.size(); i++) {
                entrada(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", hojas.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el Excel del reporte", e);
        }
        return bytes.toByteArray();
    }

    private static String hojaFiltros(DocumentoReporte documento) {
        Hoja hoja = new Hoja();
        hoja.fila(List.of(documento.titulo()), List.of(false), TITULO);
        for (String filtro : documento.filtros()) {
            hoja.fila(List.of(filtro), List.of(false), 0);
        }
        return hoja.xml(new int[] {70});
    }

    private static String hojaSeccion(Seccion s) {
        Hoja hoja = new Hoja();
        hoja.fila(List.of(s.titulo()), List.of(false), TITULO);
        hoja.fila(List.copyOf(s.encabezados()), s.moneda(), NEGRITA);
        if (s.filas().isEmpty() && s.vacio() != null) {
            hoja.fila(List.of(s.vacio()), List.of(false), 0);
        }
        for (int i = 0; i < s.filas().size(); i++) {
            boolean total = s.conTotales() && i == s.filas().size() - 1;
            hoja.fila(s.filas().get(i), s.moneda(), total ? NEGRITA : 0);
        }
        int[] anchos = new int[s.encabezados().size()];
        anchos[0] = 36;
        for (int i = 1; i < anchos.length; i++) {
            anchos[i] = 16;
        }
        return hoja.xml(anchos);
    }

    /** Las filas de una hoja, con referencias A1, B1… */
    private static final class Hoja {
        private final StringBuilder filas = new StringBuilder();
        private int numero = 0;

        void fila(List<Object> celdas, List<Boolean> moneda, int estilo) {
            numero++;
            filas.append("<row r=\"").append(numero).append("\">");
            for (int c = 0; c < celdas.size(); c++) {
                String ref = columna(c) + numero;
                Object valor = celdas.get(c);
                if (valor instanceof BigDecimal cifra) {
                    boolean esMoneda = c < moneda.size() && moneda.get(c);
                    int s = estilo == NEGRITA ? (esMoneda ? MONEDA_NEGRITA : CANTIDAD_NEGRITA) : (esMoneda ? MONEDA : 0);
                    filas.append("<c r=\"").append(ref).append("\"").append(s == 0 ? "" : " s=\"" + s + "\"")
                            .append("><v>").append(cifra.toPlainString()).append("</v></c>");
                } else {
                    filas.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(estilo == 0 ? "" : " s=\"" + estilo + "\"")
                            .append("><is><t xml:space=\"preserve\">").append(escapar(String.valueOf(valor))).append("</t></is></c>");
                }
            }
            filas.append("</row>");
        }

        String xml(int[] anchos) {
            StringBuilder cols = new StringBuilder("<cols>");
            for (int i = 0; i < anchos.length; i++) {
                cols.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                        .append(anchos[i]).append("\" customWidth=\"1\"/>");
            }
            return XML + "<worksheet xmlns=\"" + MAIN + "\">" + cols.append("</cols>") + "<sheetData>" + filas
                    + "</sheetData></worksheet>";
        }
    }

    /** Fuente normal y negrita; importes con separador de miles y dos decimales (formato 4 de Excel). */
    private static String estilos() {
        return XML + "<styleSheet xmlns=\"" + MAIN + "\">"
                + "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"14\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"6\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"4\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    /**
     * Excel limita el nombre de una hoja a 31 caracteres sin {@code []:*?/\}. Se quita la aclaracion entre
     * parentesis («(unidades)»), que ya figura en el titulo dentro de la hoja.
     */
    static String nombreHoja(String titulo) {
        String nombre = titulo.replaceAll("\\s*\\(.*\\)\\s*$", "").replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        return nombre.length() > 31 ? nombre.substring(0, 31) : nombre;
    }

    private static String columna(int indice) {
        StringBuilder sb = new StringBuilder();
        for (int i = indice; i >= 0; i = i / 26 - 1) {
            sb.insert(0, (char) ('A' + i % 26));
        }
        return sb.toString();
    }

    /** Escapa XML y quita los caracteres de control que XML 1.0 no admite. */
    private static String escapar(String valor) {
        StringBuilder salida = new StringBuilder();
        valor.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> salida.append("&amp;");
                case '<' -> salida.append("&lt;");
                case '>' -> salida.append("&gt;");
                case '"' -> salida.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        salida.appendCodePoint(c);
                    }
                }
            }
        });
        return salida.toString();
    }

    private static void entrada(ZipOutputStream zip, String nombre, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
