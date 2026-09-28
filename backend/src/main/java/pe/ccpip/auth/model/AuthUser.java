package pe.ccpip.auth.model;

/**
 * Usuario autenticable. Temporal hasta que CCPIP-19 defina el modelo de datos real
 * (usuario y rol); entonces se mapea desde la entidad persistida.
 */
public record AuthUser(
        String id,
        String email,
        String passwordHash,
        String fullName,
        String role
) {
}
