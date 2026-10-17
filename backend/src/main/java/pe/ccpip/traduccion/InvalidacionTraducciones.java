package pe.ccpip.traduccion;

import java.util.Objects;

import org.springframework.stereotype.Service;

import pe.ccpip.traduccion.Traducciones.Entidad;

/**
 * CCPIP-373 (RF-263, RNF-34): cuando el productor cambia un texto original, sus traducciones dejan de
 * publicarse. Se llama desde las ediciones del productor (producto, historia y tienda) dentro de su
 * misma transaccion: si la edicion falla, tampoco se invalida nada.
 */
@Service
public class InvalidacionTraducciones {

    private final Traducciones traducciones;

    public InvalidacionTraducciones(Traducciones traducciones) {
        this.traducciones = traducciones;
    }

    /**
     * Invalida las traducciones del campo solo si el texto realmente cambio. Vacio y nulo cuentan
     * igual, y los espacios de los extremos no son un cambio (los guardados ya se recortan).
     */
    public boolean siCambio(Entidad entidad, long clave, String campo, String antes, String despues) {
        if (Objects.equals(normal(antes), normal(despues))) {
            return false;
        }
        traducciones.invalidar(entidad, String.valueOf(clave), campo);
        return true;
    }

    private static String normal(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
