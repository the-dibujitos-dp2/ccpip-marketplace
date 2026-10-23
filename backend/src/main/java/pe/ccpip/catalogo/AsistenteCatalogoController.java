package pe.ccpip.catalogo;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.access.Publico;
import pe.ccpip.comun.Idioma;

/** CCPIP-51 y CCPIP-175: asistente de catalogo, sin sesion como el resto del catalogo. */
@Publico
@RestController
public class AsistenteCatalogoController {

    public record Pregunta(
            @NotBlank(message = "Escribe tu pregunta")
            @Size(min = 3, max = 300, message = "Escribe entre 3 y 300 caracteres")
            String pregunta) {
    }

    private final AsistenteCatalogoService asistente;
    private final LimiteAsistente limite;

    public AsistenteCatalogoController(AsistenteCatalogoService asistente, LimiteAsistente limite) {
        this.asistente = asistente;
        this.limite = limite;
    }

    @PostMapping("/api/asistente/catalogo")
    public AsistenteCatalogoService.Respuesta preguntar(@Valid @RequestBody Pregunta pregunta,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            HttpServletRequest request, HttpServletResponse response) {
        limite.registrar(request.getRemoteAddr());
        Idioma idioma = Idioma.deCabecera(acceptLanguage);
        response.setHeader(HttpHeaders.CONTENT_LANGUAGE, idioma.codigo());
        return asistente.consultar(pregunta.pregunta().strip(), idioma);
    }
}
