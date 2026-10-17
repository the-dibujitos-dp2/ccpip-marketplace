package pe.ccpip.ia;

import org.springframework.http.HttpStatus;

import pe.ccpip.comun.ErrorNegocio;

/** El servicio de IA no esta configurado o no respondio a tiempo: el productor escribe a mano. */
public class IaNoDisponible extends ErrorNegocio {

    public IaNoDisponible() {
        this("La redacción asistida no está disponible en este momento. Puedes escribir el texto con tus palabras.");
    }

    private IaNoDisponible(String mensaje) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "IA_NO_DISPONIBLE", mensaje);
    }

    /** CCPIP-168 y CCPIP-176: sin IA de imagenes se sigue con la foto original. */
    public static IaNoDisponible imagenes() {
        return new IaNoDisponible("No pudimos analizar la imagen en este momento. Puedes continuar con tu foto original.");
    }
}
