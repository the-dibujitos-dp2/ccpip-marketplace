import { expect, test } from '@playwright/test'
import { sinViolaciones } from './ayudas.js'
// enlaceDeVerificacion y Mailpit en el stack de e2e llegan con el backend del registro (#61)
import { enlaceDeVerificacion } from './correos.js'

/** CCPIP-26 y CCPIP-328 por la pantalla: crear la cuenta, abrir el enlace del correo e ingresar. */
test.describe('Registro y verificacion del correo (pantalla)', () => {
  test('una persona crea su cuenta, la verifica desde el correo e ingresa', async ({ page, request }) => {
    const correo = `pantalla.${Date.now()}@registro.test`
    const clave = 'Bosque2026'

    await page.goto('/ingresar')
    await page.getByRole('link', { name: 'Crear cuenta' }).click()
    await expect(page.getByRole('heading', { name: 'Crea tu cuenta' })).toBeVisible()
    await sinViolaciones(page)

    await page.getByLabel('Nombres').fill('Lucía')
    await page.getByLabel('Apellidos').fill('Ramírez Torres')
    await page.getByLabel('Correo electrónico').fill(correo)
    await page.getByLabel('Contraseña', { exact: true }).fill(clave)
    await page.getByLabel('Acepto los términos y la política de privacidad').check()
    await page.getByRole('button', { name: 'Crear cuenta' }).click()
    await expect(page.getByRole('heading', { name: 'Revisa tu correo' })).toBeVisible()
    await expect(page.getByText(correo)).toBeVisible()

    // Antes de abrir el enlace no puede ingresar
    await page.getByRole('button', { name: 'Ya verifiqué · iniciar sesión' }).click()
    await expect(page.getByLabel('Correo electrónico')).toHaveValue(correo)
    await page.getByLabel('Contraseña', { exact: true }).fill(clave)
    await page.getByRole('button', { name: 'Iniciar sesión' }).click()
    await expect(page.getByText('Confirma tu correo')).toBeVisible()

    await page.goto(await enlaceDeVerificacion(request, correo))
    await expect(page.getByText('Correo verificado')).toBeVisible()
    await expect(page).toHaveURL(/\/verificar-correo$/)

    await page.getByRole('button', { name: 'Iniciar sesión' }).click()
    await expect(page.getByLabel('Correo electrónico')).toHaveValue(correo)
    await page.getByLabel('Contraseña', { exact: true }).fill(clave)
    await page.getByRole('button', { name: 'Iniciar sesión' }).click()
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByRole('button', { name: 'Mi cuenta' })).toBeVisible()
  })
})
