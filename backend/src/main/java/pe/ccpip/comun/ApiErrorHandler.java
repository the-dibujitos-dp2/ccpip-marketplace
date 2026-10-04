package pe.ccpip.comun;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Convierte los errores esperados en respuestas JSON consistentes para la pantalla. */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(ErrorNegocio.class)
    public ResponseEntity<Map<String, Object>> negocio(ErrorNegocio e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("error", e.getMessage());
        if (!e.detalles().isEmpty()) {
            body.put("detalles", e.detalles());
        }
        return ResponseEntity.status(e.status()).body(body);
    }

    /** Errores de validacion de un formulario: un mensaje por campo. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of(
                "code", "DATOS_INVALIDOS",
                "error", "Revisa los datos del formulario",
                "campos", campos));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> archivoPesado(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "code", "ARCHIVO_MUY_PESADO",
                "error", "El archivo pesa más de 10 MB. Elige una foto más liviana."));
    }
}
