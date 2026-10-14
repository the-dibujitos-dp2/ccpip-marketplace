// Reglas de los formularios de acceso (CW-08). Coinciden con las del servidor (RegistroController).

// Partes separadas por puntos literales: sin retroceso exponencial (Sonar S8786)
export const CORREO_VALIDO = /^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$/

/** Celular opcional: digitos y espacios, con + inicial si se quiere. */
export const CELULAR_VALIDO = /^\+?\d[\d ]{5,18}$/

/** Minimo 8 caracteres, una mayuscula y un numero; hasta 72, el limite de BCrypt. */
export function contrasenaValida(clave) {
  return clave.length >= 8 && clave.length <= 72 && /\p{Lu}/u.test(clave) && /\d/.test(clave)
}
