import { deflateSync } from 'node:zlib'
import AxeBuilder from '@axe-core/playwright'
import { expect } from '@playwright/test'

export const CLAVE = 'Demo1234!'

/** Inicia sesion por la pantalla, como una persona. */
export async function ingresar(page, correo, clave = CLAVE) {
  await page.goto('/ingresar')
  await page.getByLabel('Correo electrónico').fill(correo)
  await page.getByLabel('Contraseña').fill(clave)
  await page.getByRole('button', { name: 'Iniciar sesión' }).click()
  await expect(page).not.toHaveURL(/\/ingresar/)
}

/** Nombre unico por corrida para no chocar con datos de otras pruebas. */
export function unico(base) {
  return `${base} ${Date.now().toString(36).slice(-5)}`
}

/** Guarda una captura de pantalla completa para compararla con el Figma. */
export async function capturar(page, nombre) {
  await page.waitForLoadState('networkidle')
  // Tras navegar dentro de la app la red queda quieta antes de pedir los datos: esperar contenido
  await page.locator('main[aria-busy="true"]').waitFor({ state: 'detached', timeout: 10_000 }).catch(() => {})
  await page.locator('h1').first().waitFor()
  await page.waitForLoadState('networkidle')
  await page.screenshot({ path: `capturas/${nombre}.png`, fullPage: true })
}

/**
 * Foto PNG valida generada en memoria (degradado de un color), para subirla como lo haria el
 * productor. Asi no se versionan binarios y cada prueba puede pedir el tamano que necesita.
 */
export function fotoPng(nombre, { ancho = 640, alto = 480, color = [31, 90, 60] } = {}) {
  const fila = Buffer.alloc(1 + ancho * 3)
  const filas = []
  for (let y = 0; y < alto; y++) {
    fila[0] = 0
    for (let x = 0; x < ancho; x++) {
      const k = (x + y) / (ancho + alto)
      fila[1 + x * 3] = Math.round(color[0] * (1 - k) + 230 * k)
      fila[2 + x * 3] = Math.round(color[1] * (1 - k) + 200 * k)
      fila[3 + x * 3] = Math.round(color[2] * (1 - k) + 150 * k)
    }
    filas.push(Buffer.from(fila))
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(ancho, 0)
  ihdr.writeUInt32BE(alto, 4)
  ihdr.set([8, 2, 0, 0, 0], 8)
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    trozo('IHDR', ihdr),
    trozo('IDAT', deflateSync(Buffer.concat(filas))),
    trozo('IEND', Buffer.alloc(0)),
  ])
  return { name: nombre, mimeType: 'image/png', buffer: png }
}

function trozo(tipo, datos) {
  const largo = Buffer.alloc(4)
  largo.writeUInt32BE(datos.length)
  const cuerpo = Buffer.concat([Buffer.from(tipo, 'ascii'), datos])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(cuerpo))
  return Buffer.concat([largo, cuerpo, crc])
}

function crc32(buf) {
  let c = ~0
  for (const b of buf) {
    c ^= b
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1
  }
  return ~c >>> 0
}

/** Fecha ISO (yyyy-mm-dd) a n dias de hoy en Lima. */
export function enDias(n) {
  const d = new Date(Date.now() + n * 86_400_000)
  return d.toLocaleDateString('en-CA', { timeZone: 'America/Lima' })
}

/**
 * Revisa accesibilidad (WCAG 2.1 AA) con axe: no se aceptan violaciones serias ni criticas.
 */
export async function sinViolaciones(page) {
  const { violations } = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
  const graves = violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
  expect(graves.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([])
}

/** Junta los errores de consola de la pagina (los 401 esperados de "sin sesion" no cuentan). */
export function vigilarConsola(page) {
  const errores = []
  page.on('console', (m) => {
    const texto = m.text()
    // axe vuelve a descargar por fetch las hojas de estilo de otro origen para analizarlas; la CSP
    // (connect-src) lo bloquea. La hoja de Google Fonts si carga: la permite style-src.
    const deAxe = /Connecting to 'https:\/\/fonts\.googleapis\.com/.test(texto)
    if (m.type() === 'error' && !/401|Unauthorized/.test(texto) && !deAxe) errores.push(`${texto} ${m.location().url}`)
  })
  page.on('pageerror', (e) => errores.push(e.message))
  return errores
}

/**
 * Llama al API desde la pagina, con la cookie de sesion del navegador: es lo que podria hacer
 * cualquier usuario con las herramientas del navegador, sin pasar por la interfaz.
 */
export async function api(page, metodo, url, cuerpo) {
  return page.evaluate(
    async ([m, u, c]) => {
      const r = await fetch(u, {
        method: m,
        credentials: 'same-origin',
        headers: c ? { 'Content-Type': 'application/json' } : undefined,
        body: c ? JSON.stringify(c) : undefined,
      })
      return { status: r.status, cuerpo: await r.json().catch(() => null) }
    },
    [metodo, url, cuerpo],
  )
}
