// Endpoints de sesion del backend (CCPIP-27)
import { apiFetch, ApiError } from './http.js'

/** Inicia sesion. Devuelve { user, expiresInSeconds }; la cookie la guarda el navegador. */
export function login(email, password) {
  return apiFetch('/api/auth/login', {
    method: 'POST',
    body: { email, password },
    sessionRequired: false,
  })
}

/**
 * Pregunta si hay sesion sin exigirla: en paginas publicas (catalogo, ficha) no tener
 * sesion es normal y no debe mandar al inicio de sesion.
 */
export async function consultarSesion() {
  try {
    return await apiFetch('/api/auth/me', { sessionRequired: false })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}

/** Devuelve { user, expiresInSeconds } o null si no hay sesion. Si expiro, avisa a la app. */
export async function fetchMe() {
  try {
    return await apiFetch('/api/auth/me')
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}

/** Cierra la sesion en el servidor y borra la cookie. */
export function logout() {
  return apiFetch('/api/auth/logout', { method: 'POST', sessionRequired: false })
}

/** CCPIP-265: guarda en la cuenta el idioma elegido ("es" o "en"). */
export function guardarIdiomaCuenta(language) {
  return apiFetch('/api/auth/me/language', { method: 'PUT', body: { language } })
}

/**
 * CCPIP-26: crea la cuenta de consumidor. Devuelve { email, emailSent }; emailSent en false
 * indica que la cuenta existe pero el correo con el enlace no salio.
 */
export function registrar({ nombres, apellidos, correo, celular, contrasena, aceptaTerminos }) {
  return apiFetch('/api/auth/registro', {
    method: 'POST',
    body: { firstName: nombres, lastName: apellidos, email: correo, phone: celular, password: contrasena, acceptTerms: aceptaTerminos },
    sessionRequired: false,
  })
}

/** CCPIP-328: usa el enlace de un solo uso. Devuelve { email } del correo confirmado. */
export function verificarCorreo(token) {
  return apiFetch('/api/auth/verificar-correo', { method: 'POST', body: { token }, sessionRequired: false })
}

/** CCPIP-328: pide otro enlace. El servidor responde igual exista o no la cuenta. */
export function reenviarVerificacion(email) {
  return apiFetch('/api/auth/verificar-correo/reenviar', { method: 'POST', body: { email }, sessionRequired: false })
}

/** CCPIP-33: pide el enlace para restablecer la contrasena. El servidor responde igual exista o no la cuenta. */
export function solicitarRecuperacion(email) {
  return apiFetch('/api/auth/recuperar-contrasena', { method: 'POST', body: { email }, sessionRequired: false })
}

/** CCPIP-33: cambia la contrasena con el enlace de un solo uso. Devuelve { email } para ingresar. */
export function restablecerContrasena(token, password) {
  return apiFetch('/api/auth/restablecer-contrasena', { method: 'POST', body: { token, password }, sessionRequired: false })
}
