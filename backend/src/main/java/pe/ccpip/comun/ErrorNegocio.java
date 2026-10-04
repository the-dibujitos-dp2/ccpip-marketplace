package pe.ccpip.comun;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Error esperado de una regla de negocio. Llega al cliente como JSON {code, error, detalles}
 * con el estado indicado, para que la pantalla muestre un mensaje claro.
 */
public class ErrorNegocio extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> detalles;

    public ErrorNegocio(HttpStatus status, String code, String mensaje) {
        this(status, code, mensaje, List.of());
    }

    public ErrorNegocio(HttpStatus status, String code, String mensaje, List<String> detalles) {
        super(mensaje);
        this.status = status;
        this.code = code;
        this.detalles = List.copyOf(detalles);
    }

    public static ErrorNegocio noEncontrado(String code, String mensaje) {
        return new ErrorNegocio(HttpStatus.NOT_FOUND, code, mensaje);
    }

    public static ErrorNegocio invalido(String code, String mensaje) {
        return new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, code, mensaje);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> detalles() {
        return detalles;
    }
}
