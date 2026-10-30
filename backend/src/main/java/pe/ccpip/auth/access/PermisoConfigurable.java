package pe.ccpip.auth.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CCPIP-296: ademas de su rol ({@link RolesPermitidos}), el endpoint exige este permiso configurable. Si
 * el superadministrador lo revoco al rol de la sesion, se niega con PERMISO_REVOCADO; si el rol no tiene
 * fila para el permiso, lo conserva.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PermisoConfigurable {
    Permiso value();
}
