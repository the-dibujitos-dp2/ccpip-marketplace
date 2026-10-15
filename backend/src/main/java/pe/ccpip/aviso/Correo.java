package pe.ccpip.aviso;

/**
 * Un correo a una sola persona, en texto plano. El texto plano llega igual a cualquier cliente de
 * correo y no depende de imagenes externas que el cliente bloquea.
 */
public record Correo(String para, String asunto, String texto) {
}
