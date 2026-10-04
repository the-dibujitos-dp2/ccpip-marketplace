package pe.ccpip.ia;

import org.springframework.http.HttpStatus;

import pe.ccpip.comun.ErrorNegocio;

/** El servicio de IA no esta configurado o no respondio a tiempo: el productor escribe a mano. */
public class IaNoDisponible extends ErrorNegocio {

    public IaNoDisponible() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "IA_NO_DISPONIBLE",
                "La redacción asistida no está disponible en este momento. Puedes escribir el texto con tus palabras.");
    }
}
