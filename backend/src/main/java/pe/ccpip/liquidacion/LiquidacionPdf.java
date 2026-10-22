package pe.ccpip.liquidacion;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import pe.ccpip.liquidacion.LiquidacionModelos.Linea;
import pe.ccpip.liquidacion.LiquidacionModelos.Liquidacion;
import pe.ccpip.liquidacion.LiquidacionModelos.PedidoLiquidado;

/**
 * CCPIP-326 (RF-214): la liquidacion del periodo como PDF. Se arma solo con el JDK, sin librerias: un PDF
 * 1.4 de paginas A4 con la fuente Helvetica estandar en WinAnsiEncoding, que cubre las tildes y la enie
 * del castellano. Los importes son los mismos de {@link LiquidacionService}; aqui solo se dibujan.
 */
final class LiquidacionPdf {

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(LIMA);

    /** A4 en puntos y margenes. */
    private static final float ANCHO = 595;
    private static final float ALTO = 842;
    private static final float MARGEN = 48;
    private static final float INTERLINEA = 15;

    /** Columnas: x de inicio de pedido, fecha y producto; x del borde derecho de los importes. */
    private static final float X_PEDIDO = MARGEN;
    private static final float X_FECHA = 128;
    private static final float X_PRODUCTO = 218;
    private static final float X_BRUTO = 412;
    private static final float X_COMISION = 480;
    private static final float X_NETO = ANCHO - MARGEN;
    private static final int MAX_PRODUCTO = 34;

    private final List<StringBuilder> paginas = new ArrayList<>();
    private StringBuilder pagina;
    private float y;

    private LiquidacionPdf() {
    }

    static byte[] generar(Liquidacion l) {
        LiquidacionPdf pdf = new LiquidacionPdf();
        pdf.dibujar(l);
        return pdf.documento();
    }

    /** "S/ 1,234.50", como lo muestra la pantalla (formato de Peru, RNF-43). */
    static String soles(BigDecimal monto) {
        DecimalFormat formato = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return "S/ " + formato.format(monto.setScale(2, RoundingMode.HALF_UP));
    }

    private void dibujar(Liquidacion l) {
        nuevaPagina();
        texto(MARGEN, y, 16, true, "Liquidación del productor");
        y -= 22;
        texto(MARGEN, y, 11, false, l.productor());
        y -= INTERLINEA;
        texto(MARGEN, y, 10, false, "Periodo: del " + FECHA.format(l.desde()) + " al " + FECHA.format(l.hasta())
                + " (fechas de confirmación, hora de Lima)");
        y -= 26;

        var r = l.resumen();
        fila(10, true, "Pedidos", null, null, "Bruto", "Comisión", "Neto");
        fila(10, false, String.valueOf(r.pedidos()), null, null, soles(r.bruto()), soles(r.comision()), soles(r.neto()));
        y -= 12;
        if (r.sinComision() > 0) {
            texto(MARGEN, y, 9, false, r.sinComision() == 1
                    ? "1 pedido se confirmó sin comisión configurada: se liquida sin descuento."
                    : r.sinComision() + " pedidos se confirmaron sin comisión configurada: se liquidan sin descuento.");
            y -= INTERLINEA + 4;
        }

        linea();
        fila(9, true, "Pedido", "Confirmado", "Producto", "Bruto", "Comisión", "Neto");
        linea();
        if (l.pedidos().isEmpty()) {
            texto(MARGEN, y, 10, false, "No hay ventas confirmadas en este periodo.");
            y -= INTERLINEA;
        }
        for (PedidoLiquidado p : l.pedidos()) {
            espacio(INTERLINEA * (p.lineas().size() + 1) + 6);
            boolean primera = true;
            for (Linea li : p.lineas()) {
                String producto = recortar(li.producto() + " x " + cantidad(li.cantidad()), MAX_PRODUCTO);
                fila(9, false, primera ? p.numero() : null, primera ? FECHA_HORA.format(p.confirmadoEn()) : null,
                        producto, soles(li.bruto()), soles(li.comision()), soles(li.neto()));
                primera = false;
            }
            if (p.lineas().size() > 1) {
                fila(9, true, null, null, "Total del pedido", soles(p.bruto()), soles(p.comision()), soles(p.neto()));
            }
            y -= 6;
        }
        linea();
        fila(10, true, "Total", null, null, soles(r.bruto()), soles(r.comision()), soles(r.neto()));
        y -= 14;
        espacio(INTERLINEA * 2);
        texto(MARGEN, y, 8, false, "Incluye solo tus productos. La comisión y el neto quedan fijados al confirmar cada pedido;");
        y -= 11;
        texto(MARGEN, y, 8, false, "un pedido cancelado después del pago no se liquida. Documento generado por la plataforma CCPIP.");
    }

    private void fila(float tamano, boolean negrita, String pedido, String fecha, String producto,
                      String bruto, String comision, String neto) {
        espacio(INTERLINEA);
        if (pedido != null) {
            texto(X_PEDIDO, y, tamano, negrita, pedido);
        }
        if (fecha != null) {
            texto(X_FECHA, y, tamano, negrita, fecha);
        }
        if (producto != null) {
            texto(X_PRODUCTO, y, tamano, negrita, producto);
        }
        derecha(X_BRUTO, tamano, negrita, bruto);
        derecha(X_COMISION, tamano, negrita, comision);
        derecha(X_NETO, tamano, negrita, neto);
        y -= INTERLINEA;
    }

    private void derecha(float x, float tamano, boolean negrita, String valor) {
        if (valor != null) {
            texto(x - ancho(valor, tamano, negrita), y, tamano, negrita, valor);
        }
    }

    private void linea() {
        espacio(8);
        pagina.append("0.6 w ").append(num(MARGEN)).append(' ').append(num(y + 10)).append(" m ")
                .append(num(ANCHO - MARGEN)).append(' ').append(num(y + 10)).append(" l S\n");
        y -= 4;
    }

    /** Pasa a una pagina nueva si no queda {@code alto} por encima del margen inferior. */
    private void espacio(float alto) {
        if (y - alto < MARGEN) {
            nuevaPagina();
        }
    }

    private void nuevaPagina() {
        pagina = new StringBuilder();
        paginas.add(pagina);
        y = ALTO - MARGEN;
    }

    private void texto(float x, float yTexto, float tamano, boolean negrita, String valor) {
        pagina.append("BT /").append(negrita ? "F2 " : "F1 ").append(num(tamano)).append(" Tf ")
                .append(num(x)).append(' ').append(num(yTexto)).append(" Td (").append(escapar(valor)).append(") Tj ET\n");
    }

    private byte[] documento() {
        int n = paginas.size();
        // 1 catalogo, 2 arbol de paginas, 3 y 4 fuentes, luego pagina y contenido de cada una
        List<byte[]> objetos = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            kids.append(5 + 2 * i).append(" 0 R ");
        }
        objetos.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
        objetos.add(ascii("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + n + " >>"));
        objetos.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objetos.add(ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
        for (int i = 0; i < n; i++) {
            String pie = "BT /F1 8 Tf " + num(ANCHO - MARGEN - 60) + " " + num(MARGEN - 20) + " Td (Página " + (i + 1)
                    + " de " + n + ") Tj ET\n";
            byte[] contenido = (paginas.get(i) + pie).getBytes(WIN_ANSI);
            objetos.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(ANCHO) + " " + num(ALTO)
                    + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (6 + 2 * i) + " 0 R >>"));
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            stream.writeBytes(ascii("<< /Length " + contenido.length + " >>\nstream\n"));
            stream.writeBytes(contenido);
            stream.writeBytes(ascii("\nendstream"));
            objetos.add(stream.toByteArray());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("%PDF-1.4\n"));
        // Marca binaria recomendada para que las herramientas traten el archivo como binario
        out.writeBytes(new byte[] {'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
        List<Integer> posiciones = new ArrayList<>();
        for (int i = 0; i < objetos.size(); i++) {
            posiciones.add(out.size());
            out.writeBytes(ascii((i + 1) + " 0 obj\n"));
            out.writeBytes(objetos.get(i));
            out.writeBytes(ascii("\nendobj\n"));
        }
        int xref = out.size();
        StringBuilder tabla = new StringBuilder("xref\n0 " + (objetos.size() + 1) + "\n0000000000 65535 f \n");
        for (int posicion : posiciones) {
            tabla.append("%010d 00000 n \n".formatted(posicion));
        }
        tabla.append("trailer\n<< /Size ").append(objetos.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        out.writeBytes(ascii(tabla.toString()));
        return out.toByteArray();
    }

    /** Ancho aproximado en puntos con las metricas de Helvetica; alcanza para alinear importes a la derecha. */
    static float ancho(String valor, float tamano, boolean negrita) {
        float total = 0;
        for (char c : valor.toCharArray()) {
            total += anchoCaracter(c, negrita);
        }
        return total * tamano / 1000;
    }

    private static int anchoCaracter(char c, boolean negrita) {
        if (c >= '0' && c <= '9') {
            return 556;
        }
        return switch (c) {
            case ' ', '.', ',', '/' -> 278;
            case '-' -> 333;
            case 'S' -> 667;
            case '%' -> 889;
            default -> negrita ? 611 : 556;
        };
    }

    /** Escapa lo que el PDF reserva en un texto y quita lo que WinAnsi no puede representar. */
    static String escapar(String valor) {
        StringBuilder s = new StringBuilder();
        var encoder = WIN_ANSI.newEncoder();
        valor.codePoints().forEach(c -> {
            if (c == '\\' || c == '(' || c == ')') {
                s.append('\\').append((char) c);
            } else if (c < ' ') {
                s.append(' ');
            } else if (Character.isBmpCodePoint(c) && encoder.canEncode((char) c)) {
                s.append((char) c);
            } else {
                s.append('?');
            }
        });
        return s.toString();
    }

    private static String recortar(String valor, int max) {
        return valor.length() <= max ? valor : valor.substring(0, max - 3) + "...";
    }

    private static String cantidad(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString();
    }

    private static String num(float valor) {
        return valor == Math.rint(valor) ? String.valueOf((int) valor) : String.format(Locale.US, "%.2f", valor);
    }

    private static byte[] ascii(String valor) {
        return valor.getBytes(StandardCharsets.US_ASCII);
    }
}
