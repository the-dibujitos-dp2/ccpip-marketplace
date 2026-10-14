// Cliente HTTP comun (CCPIP-27). La sesion viaja en una cookie HttpOnly que pone el
// backend: este codigo nunca ve ni guarda el token.
import i18n from '../i18n/index.js'

const listeners = new Set()
let idleTimer = null
let idleMs = null
let onIdle = null

/** Avisa cuando el servidor rechaza la sesion: 'SESION_EXPIRADA' o 'SIN_SESION'. */
export function onSessionLost(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * Arma un temporizador de inactividad: si pasan `seconds` sin peticiones al servidor,
 * llama a `callback` (que consulta /me y recibe SESION_EXPIRADA). Asi la pantalla
 * vuelve al inicio de sesion aunque el usuario no haga nada.
 */
export function armIdleTimer(seconds, callback) {
  idleMs = seconds * 1000 + 2000
  onIdle = callback
  restartIdleTimer()
}

export function disarmIdleTimer() {
  clearTimeout(idleTimer)
  idleTimer = null
  idleMs = null
  onIdle = null
}

/** Cualquier actividad con el servidor reinicia el plazo de inactividad. */
export function restartIdle() {
  restartIdleTimer()
}

function restartIdleTimer() {
  clearTimeout(idleTimer)
  if (idleMs) idleTimer = setTimeout(() => onIdle?.(), idleMs)
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body.error || `Error ${status}`)
    this.status = status
    this.code = body.code
    this.body = body
  }
}

// Ids que pueden ir en una ruta del API: numeros (productos, tiendas) o UUID (imagenes)
const ID_VALIDO = /^(?:[1-9]\d{0,17}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i

/**
 * Valida un id que llega de la URL antes de ponerlo en la ruta de una peticion. Evita que un
 * enlace armado (p. ej. ?producto=../../auth/logout) haga llamar a otro endpoint del sitio.
 * Un id invalido se trata como inexistente (404).
 */
export function idRuta(valor) {
  const texto = String(valor ?? '')
  if (!ID_VALIDO.test(texto)) throw new ApiError(404, { code: 'NO_ENCONTRADO', error: 'No encontrado' })
  return encodeURIComponent(texto)
}

export async function apiFetch(path, { method = 'GET', body, sessionRequired = true } = {}) {
  const res = await fetch(path, {
    method,
    credentials: 'same-origin',
    cache: 'no-store',
    // CCPIP-166: el servidor responde el contenido aprobado en el idioma elegido
    headers: { 'Accept-Language': i18n.resolvedLanguage, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  restartIdleTimer()

  if (res.status === 204) return null
  const data = await res.json().catch(() => ({}))
  if (res.ok) return data

  const error = new ApiError(res.status, data)
  if (res.status === 401 && sessionRequired) {
    listeners.forEach((listener) => listener(error.code))
  }
  throw error
}
