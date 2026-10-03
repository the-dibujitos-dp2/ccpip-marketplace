package pe.ccpip.auth.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Endpoint para cualquier usuario con sesion, sea cual sea su rol (p. ej. ver su propio perfil). */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Autenticado {
}
