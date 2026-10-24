package pe.ccpip.merma;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import pe.ccpip.merma.MermaModelos.ReporteMerma;

/** XLSX renderer of exactly the canonical rows also used by the PDF. */
final class MermaExcel {
    private MermaExcel() { }
    static byte[] generar(ReporteMerma reporte) { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(bytes)) { entrada(zip,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"); entrada(zip,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"); entrada(zip,"xl/workbook.xml","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Merma\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"); entrada(zip,"xl/_rels/workbook.xml.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"); entrada(zip,"xl/worksheets/sheet1.xml",hoja(reporte)); } catch (IOException e) { throw new UncheckedIOException(e); } return bytes.toByteArray(); }
    private static String hoja(ReporteMerma reporte) { StringBuilder filas = new StringBuilder(); int n=1; for (ReporteMermaContenido.Fila fila : ReporteMermaContenido.filas(reporte)) filas.append(fila(n++, fila.celdas().toArray(String[]::new))); return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"+filas+"</sheetData></worksheet>"; }
    private static String fila(int n, String... valores) { StringBuilder fila = new StringBuilder("<row r=\"").append(n).append("\">"); for(int i=0;i<valores.length;i++) fila.append(texto(String.valueOf((char)('A'+i)),n,valores[i])); return fila.append("</row>").toString(); }
    private static String texto(String columna,int fila,String valor) { return "<c r=\""+columna+fila+"\" t=\"inlineStr\"><is><t>"+xml(valor)+"</t></is></c>"; }
    static String xml(String valor) { StringBuilder salida=new StringBuilder(); valor.codePoints().forEach(c->{ switch(c){case '&'->salida.append("&amp;");case '<'->salida.append("&lt;");case '>'->salida.append("&gt;");case '"'->salida.append("&quot;");default->{if(c>=0x20||c=='\t'||c=='\n'||c=='\r')salida.appendCodePoint(c);}}});return salida.toString(); }
    private static void entrada(ZipOutputStream zip,String nombre,String xml)throws IOException{zip.putNextEntry(new ZipEntry(nombre));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
