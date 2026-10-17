package pe.ccpip.traduccion;

import org.springframework.http.HttpStatus;

import pe.ccpip.comun.ErrorNegocio;

/**
 * CCPIP-147 y RNF-34: no hay proveedor de traduccion o no respondio. No se toca nada: el original
 * y las traducciones que ya existian se conservan.
 */
public class TraduccionNoDisponible extends ErrorNegocio {

    public TraduccionNoDisponible() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "TRADUCCION_NO_DISPONIBLE",
                "La traducción automática no está disponible en este momento. Tu contenido se conserva en su idioma original.");
    }
}
