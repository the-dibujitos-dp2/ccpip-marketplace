package pe.ccpip.auth.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import pe.ccpip.auth.model.Rol;

/**
 * CCPIP-151: roles que pueden invocar el endpoint. Se valida en el servidor en cada peticion
 * (RNF-04); esconder un boton en la pantalla no es control de acceso.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RolesPermitidos {
    Rol[] value();
}
