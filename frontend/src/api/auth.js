// Cliente de los endpoints de sesion del backend (CCPIP-27)

const TOKEN_KEY = 'ccpip.token'
// El backend emite tokens opacos UUID; cualquier otra cosa no se guarda
const TOKEN_FORMAT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY)
}

function setToken(token) {
  if (typeof token === 'string' && TOKEN_FORMAT.test(token)) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}

export async function login(email, password) {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  const body = await res.json().catch(() => ({}))

  if (res.ok) {
    setToken(body.token)
    return body.user
  }
  if (res.status === 423) {
    const hasta = new Date(body.lockedUntil).toLocaleTimeString()
    throw new Error(`Cuenta bloqueada por intentos fallidos hasta las ${hasta}.`)
  }
  if (res.status === 400) {
    throw new Error('Revisa el formato del correo y la contrasena.')
  }
  throw new Error(body.error || 'No se pudo iniciar sesion.')
}

/** Devuelve el usuario de la sesion actual, o null si no hay sesion o expiro. */
export async function fetchMe() {
  const token = getToken()
  if (!token) return null
  const res = await fetch('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } })
  if (!res.ok) {
    setToken(null)
    return null
  }
  return res.json()
}

export async function logout() {
  const token = getToken()
  setToken(null)
  if (token) {
    await fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${token}` } })
  }
}
