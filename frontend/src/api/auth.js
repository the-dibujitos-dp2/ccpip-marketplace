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

/** Devuelve { user, expiresInSeconds } o null si no hay sesion. */
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
