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
