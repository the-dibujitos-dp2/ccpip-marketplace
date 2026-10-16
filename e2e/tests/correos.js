import { expect } from '@playwright/test'

/** API de Mailpit del stack de e2e (e2e/docker-compose.yml), donde llegan los correos del backend. */
const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025'

/**
 * Espera el correo mas reciente para `para` y devuelve el enlace de verificacion que trae.
 * El backend envia el correo en la misma peticion, pero se reintenta por si Mailpit tarda en indexarlo.
 */
export async function enlaceDeVerificacion(request, para) {
  let texto = ''
  await expect(async () => {
    const busqueda = await request.get(`${MAILPIT}/api/v1/search`, { params: { query: `to:"${para}"` } })
    expect(busqueda.ok()).toBeTruthy()
    const { messages } = await busqueda.json()
    expect(messages.length).toBeGreaterThan(0)
    const mensaje = await request.get(`${MAILPIT}/api/v1/message/${messages[0].ID}`)
    texto = (await mensaje.json()).Text
  }).toPass({ timeout: 15_000 })
  const enlace = texto.match(/http:\/\/localhost:4173\/verificar-correo\?token=[A-Za-z0-9_-]+/)
  expect(enlace, `enlace en el correo:\n${texto}`).not.toBeNull()
  return enlace[0]
}

/**
 * CCPIP-33: espera el correo de `para` que trae un enlace a `ruta` (p. ej. /restablecer-contrasena)
 * y lo devuelve. Revisa los correos de esa direccion del mas reciente al mas antiguo.
 */
export async function enlaceDelCorreo(request, para, ruta) {
  const patron = new RegExp(`http://localhost:4173${ruta}\\?token=[A-Za-z0-9_-]+`)
  let enlace = null
  await expect(async () => {
    const busqueda = await request.get(`${MAILPIT}/api/v1/search`, { params: { query: `to:"${para}"` } })
    expect(busqueda.ok()).toBeTruthy()
    const { messages } = await busqueda.json()
    for (const m of messages) {
      const texto = (await (await request.get(`${MAILPIT}/api/v1/message/${m.ID}`)).json()).Text
      enlace = texto.match(patron)?.[0] ?? null
      if (enlace) break
    }
    expect(enlace, `correo con enlace a ${ruta} para ${para}`).not.toBeNull()
  }).toPass({ timeout: 15_000 })
  return enlace
}
