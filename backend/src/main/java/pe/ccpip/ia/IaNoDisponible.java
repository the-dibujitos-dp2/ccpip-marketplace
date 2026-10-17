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

    /** CCPIP-50: sin analisis asistido la solicitud se revisa a mano; la decision sigue siendo posible. */
    public static IaNoDisponible paraRevision() {
        return new IaNoDisponible("El análisis asistido no está disponible. La solicitud pasa a revisión manual: "
                + "revisa las evidencias y decide con tu motivo.");
    }
}
