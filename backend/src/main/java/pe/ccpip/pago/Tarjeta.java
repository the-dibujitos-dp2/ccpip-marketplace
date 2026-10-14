package pe.ccpip.pago;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import pe.ccpip.comun.ErrorNegocio;

/**
 * Tarjeta del comprador mientras se cobra (CCPIP-62). Vive solo en memoria: no se guarda, y
 * {@link #toString()} la enmascara para que un log nunca la muestre completa.
 */
public record Tarjeta(String numero, String titular, int mes, int anio, String cvv) {

    private static final Pattern NO_DIGITOS = Pattern.compile("\\D");
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    /** Marcas aceptadas; coinciden con {@code pago_marca_valida}. */
    public enum Marca {
        VISA, MASTERCARD, AMEX, DINERS
    }

    /** Solo los digitos, para que "4111 1111-1111 1111" sea la misma tarjeta. */
    public String digitos() {
        return numero == null ? "" : NO_DIGITOS.matcher(numero).replaceAll("");
    }

    /**
     * Valida numero (Luhn), marca, vencimiento y CVV (4 digitos en Amex, 3 en las demas) y devuelve la
     * marca. Junta todos los problemas en un solo error para que el formulario los muestre a la vez.
     */
    public Marca validar() {
        List<String> problemas = new ArrayList<>();
        String digitos = digitos();
        Marca marca = marca(digitos);
        if (marca == null || !longitudValida(marca, digitos.length()) || !luhn(digitos)) {
            problemas.add("El número de tarjeta no es válido.");
        }
        if (titular == null || titular.isBlank()) {
            problemas.add("Indica el nombre del titular.");
        }
        YearMonth hoy = YearMonth.now(LIMA);
        if (mes < 1 || mes > 12 || anio < 2000 || YearMonth.of(anio, Math.max(1, Math.min(12, mes))).isBefore(hoy)) {
            problemas.add("La tarjeta está vencida o la fecha no es válida.");
        }
        int largoCvv = marca == Marca.AMEX ? 4 : 3;
        if (cvv == null || !cvv.matches("\\d{" + largoCvv + "}")) {
            problemas.add("El código de seguridad debe tener " + largoCvv + " dígitos.");
        }
        if (!problemas.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "TARJETA_INVALIDA",
                    "Revisa los datos de tu tarjeta.", problemas);
        }
        return marca;
    }

    static Marca marca(String d) {
        if (d.startsWith("4")) {
            return Marca.VISA;
        }
        if (d.startsWith("34") || d.startsWith("37")) {
            return Marca.AMEX;
        }
        if (d.startsWith("36") || d.startsWith("38") || d.matches("30[0-5].*")) {
            return Marca.DINERS;
        }
        if (d.length() >= 4) {
            int dos = Integer.parseInt(d.substring(0, 2));
            int cuatro = Integer.parseInt(d.substring(0, 4));
            if ((dos >= 51 && dos <= 55) || (cuatro >= 2221 && cuatro <= 2720)) {
                return Marca.MASTERCARD;
            }
        }
        return null;
    }

    private static boolean longitudValida(Marca marca, int largo) {
        return switch (marca) {
            case VISA -> largo == 13 || largo == 16 || largo == 19;
            case MASTERCARD -> largo == 16;
            case AMEX -> largo == 15;
            case DINERS -> largo == 14 || largo == 16;
        };
    }

    static boolean luhn(String d) {
        if (d.isEmpty()) {
            return false;
        }
        int suma = 0;
        boolean doblar = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (doblar) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            suma += n;
            doblar = !doblar;
        }
        return suma % 10 == 0;
    }

    @Override
    public String toString() {
        String d = digitos();
        return "Tarjeta[" + (d.length() >= 4 ? "****" + d.substring(d.length() - 4) : "****") + "]";
    }
}
