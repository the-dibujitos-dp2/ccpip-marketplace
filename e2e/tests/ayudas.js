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
