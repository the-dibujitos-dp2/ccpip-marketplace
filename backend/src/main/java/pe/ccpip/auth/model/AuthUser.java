package pe.ccpip.auth.model;

import java.time.Instant;

import pe.ccpip.comun.Idioma;

/**
 * Usuario autenticable, leido de la tabla usuario (CCPIP-19).
 *
 * @param role      codigo del rol (CONSUMIDOR, PRODUCTOR...), el que se valida en el servidor
 * @param roleName  nombre del rol para mostrar en pantalla
 * @param lockedUntil fin del bloqueo temporal por intentos fallidos, o null si no esta bloqueado
 * @param language  idioma preferido de la cuenta (CCPIP-265)
 */
public record AuthUser(
        long id,
        String email,
        String passwordHash,
        String fullName,
        Rol role,
        String roleName,
        boolean active,
        boolean emailConfirmed,
        int failedAttempts,
        Instant lockedUntil,
        Idioma language
) {
}
