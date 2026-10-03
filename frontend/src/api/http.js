// Cliente HTTP comun (CCPIP-27). La sesion viaja en una cookie HttpOnly que pone el
// backend: este codigo nunca ve ni guarda el token.

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

export async function apiFetch(path, { method = 'GET', body, sessionRequired = true } = {}) {
  const res = await fetch(path, {
    method,
    credentials: 'same-origin',
    cache: 'no-store',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
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
