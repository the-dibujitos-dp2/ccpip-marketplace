package pe.ccpip.auth.model;

/** Lo que el filtro deja en la peticion cuando la sesion es valida. */
public record AuthenticatedSession(long userId, Rol role) {
}
