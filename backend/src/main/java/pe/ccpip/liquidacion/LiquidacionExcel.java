package pe.ccpip.liquidacion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import pe.ccpip.liquidacion.LiquidacionModelos.Linea;
import pe.ccpip.liquidacion.LiquidacionModelos.Liquidacion;
import pe.ccpip.liquidacion.LiquidacionModelos.PedidoLiquidado;

/**
 * CCPIP-384 (RF-276): la liquidacion del periodo como libro de Excel (.xlsx). Se arma solo con el JDK:
 * un .xlsx es un zip de XML (Office Open XML, SpreadsheetML). Dos hojas: "Lineas", una fila por linea
 * propia de cada pedido, y "Pedidos", una fila por pedido. Los importes son numeros, no texto, para que
 * el productor pueda sumarlos o filtrarlos; son los mismos de {@link LiquidacionService}.
 */
final class LiquidacionExcel {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Lima"));

    /** Estilos de styles.xml: 0 normal, 1 negrita, 2 importe, 3 importe en negrita, 4 titulo. */
    private static final int NEGRITA = 1;
    private static final int IMPORTE = 2;
    private static final int IMPORTE_NEGRITA = 3;
    private static final int TITULO = 4;

    /** Como los nombra la pantalla (productor.pedidos.estado). */
    private static final Map<String, String> ESTADOS = Map.of("CONFIRMADO", "Confirmado",
            "EN_PREPARACION", "En preparación", "LISTO_PARA_RECOJO", "Listo para recojo", "ENTREGADO", "Entregado");

    private LiquidacionExcel() {
    }

    static byte[] generar(Liquidacion l) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entrada(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>""");
            entrada(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            entrada(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="Lineas" sheetId="1" r:id="rId1"/><sheet name="Pedidos" sheetId="2" r:id="rId2"/></sheets>
                    </workbook>""");
            entrada(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                    <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>""");
            entrada(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;S/ &quot;#,##0.00"/></numFmts>
                    <fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><name val="Calibri"/></font></fonts>
                    <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
                    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                    <cellXfs count="5">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                    <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                    <xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>
                    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                    </cellXfs>
                    <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                    </styleSheet>""");
            entrada(zip, "xl/worksheets/sheet1.xml", hojaLineas(l));
            entrada(zip, "xl/worksheets/sheet2.xml", hojaPedidos(l));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String hojaLineas(Liquidacion l) {
        Hoja h = encabezado(l, new int[] {14, 18, 13, 40, 10, 14, 14, 14, 14});
        h.fila().texto("Pedido", NEGRITA).texto("Confirmado", NEGRITA).texto("Estado", NEGRITA)
                .texto("Producto", NEGRITA).texto("Cantidad", NEGRITA).texto("Precio unitario", NEGRITA)
                .texto("Bruto", NEGRITA).texto("Comisión", NEGRITA).texto("Neto", NEGRITA).fin();
        for (PedidoLiquidado p : l.pedidos()) {
            for (Linea li : p.lineas()) {
                h.fila().texto(p.numero(), 0).texto(FECHA_HORA.format(p.confirmadoEn()), 0).texto(estado(p), 0)
                        .texto(li.producto(), 0).numero(li.cantidad(), 0).numero(li.precioUnitario(), IMPORTE)
                        .numero(li.bruto(), IMPORTE).numero(li.comision(), IMPORTE).numero(li.neto(), IMPORTE).fin();
            }
        }
        total(h, l, 6);
        return h.documento();
    }

    private static String hojaPedidos(Liquidacion l) {
        Hoja h = encabezado(l, new int[] {14, 18, 13, 12, 14, 14, 14});
        h.fila().texto("Pedido", NEGRITA).texto("Confirmado", NEGRITA).texto("Estado", NEGRITA)
                .texto("Comisión %", NEGRITA).texto("Bruto", NEGRITA).texto("Comisión", NEGRITA)
                .texto("Neto", NEGRITA).fin();
        for (PedidoLiquidado p : l.pedidos()) {
            Hoja fila = h.fila().texto(p.numero(), 0).texto(FECHA_HORA.format(p.confirmadoEn()), 0).texto(estado(p), 0);
            (p.porcentaje() == null ? fila.texto("Sin comisión", 0) : fila.numero(p.porcentaje(), 0))
                    .numero(p.bruto(), IMPORTE).numero(p.comision(), IMPORTE).numero(p.neto(), IMPORTE).fin();
        }
        total(h, l, 4);
        return h.documento();
    }

    private static String estado(PedidoLiquidado p) {
        return ESTADOS.getOrDefault(p.estado(), p.estado());
    }

    private static Hoja encabezado(Liquidacion l, int[] anchos) {
        Hoja h = new Hoja(anchos);
        h.fila().texto("Liquidación del productor", TITULO).fin();
        h.fila().texto(l.productor(), 0).fin();
        h.fila().texto("Periodo: del " + FECHA.format(l.desde()) + " al " + FECHA.format(l.hasta())
                + " (fechas de confirmación, hora de Lima)", 0).fin();
        h.fila().fin();
        return h;
    }

    /** Fila de totales con bruto, comision y neto del periodo desde la columna {@code columnaBruto} (0 = A). */
    private static void total(Hoja h, Liquidacion l, int columnaBruto) {
        Hoja fila = h.fila().texto("Total", NEGRITA);
        for (int i = 1; i < columnaBruto; i++) {
            fila.vacia();
        }
        fila.numero(l.resumen().bruto(), IMPORTE_NEGRITA).numero(l.resumen().comision(), IMPORTE_NEGRITA)
                .numero(l.resumen().neto(), IMPORTE_NEGRITA).fin();
    }

    private static void entrada(ZipOutputStream zip, String nombre, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Escapa el texto para XML y quita los caracteres de control que XML 1.0 no admite. */
    static String xml(String valor) {
        StringBuilder s = new StringBuilder();
        valor.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> s.append("&amp;");
                case '<' -> s.append("&lt;");
                case '>' -> s.append("&gt;");
                case '"' -> s.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        s.appendCodePoint(c);
                    }
                }
            }
        });
        return s.toString();
    }

    /** Una hoja en construccion: filas de celdas con referencias A1, B1... */
    private static final class Hoja {

        private final StringBuilder filas = new StringBuilder();
        private final int[] anchos;
        private int fila;
        private int columna;

        Hoja(int[] anchos) {
            this.anchos = anchos;
        }

        Hoja fila() {
            fila++;
            columna = 0;
            filas.append("<row r=\"").append(fila).append("\">");
            return this;
        }

        Hoja texto(String valor, int estilo) {
            filas.append("<c r=\"").append(referencia()).append("\" t=\"inlineStr\"").append(estilo(estilo))
                    .append("><is><t>").append(xml(valor == null ? "" : valor)).append("</t></is></c>");
            return this;
        }

        Hoja numero(BigDecimal valor, int estilo) {
            filas.append("<c r=\"").append(referencia()).append('"').append(estilo(estilo))
                    .append("><v>").append(valor.toPlainString()).append("</v></c>");
            return this;
        }

        Hoja vacia() {
            columna++;
            return this;
        }

        void fin() {
            filas.append("</row>");
        }

        private String referencia() {
            return (char) ('A' + columna++) + String.valueOf(fila);
        }

        private static String estilo(int estilo) {
            return estilo == 0 ? "" : " s=\"" + estilo + "\"";
        }

        String documento() {
            StringBuilder cols = new StringBuilder("<cols>");
            for (int i = 0; i < anchos.length; i++) {
                cols.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                        .append(anchos[i]).append("\" customWidth=\"1\"/>");
            }
            cols.append("</cols>");
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + cols + "<sheetData>" + filas + "</sheetData></worksheet>";
        }
    }
}
