import { expect, test } from '@playwright/test'
import { ingresar, sinViolaciones } from './ayudas.js'

/** Regresiones de CCPIP-27 sobre el stack real: ingreso, cierre, "atras" y expiracion. */
test.describe('Sesion (CCPIP-27)', () => {
  test('credenciales incorrectas no dejan entrar y explican por que', async ({ page }) => {
    await page.goto('/ingresar')
    await sinViolaciones(page)
    await page.getByLabel('Correo electrónico').fill('productor@ccpip.test')
    await page.getByLabel('Contraseña', { exact: true }).fill('no-es-la-clave')
    await page.getByRole('button', { name: 'Iniciar sesión' }).click()
    await expect(page.getByText('No pudimos iniciar sesión')).toBeVisible()
    await expect(page.getByLabel('Contraseña', { exact: true })).toHaveValue('')
    await expect(page).toHaveURL(/\/ingresar/)
  })

  test('una pantalla protegida pide ingresar y luego vuelve a ella', async ({ page }) => {
    await page.goto('/productor/productos')
    await expect(page).toHaveURL(/\/ingresar\?volver=%2Fproductor%2Fproductos/)
    await page.getByLabel('Correo electrónico').fill('productor@ccpip.test')
    await page.getByLabel('Contraseña', { exact: true }).fill('Demo1234!')
    await page.getByRole('button', { name: 'Iniciar sesión' }).click()
    await expect(page).toHaveURL(/\/productor\/productos$/)
  })

  test('tras cerrar sesion, "atras" no muestra el panel', async ({ page }) => {
    await ingresar(page, 'productor@ccpip.test')
    await page.getByRole('link', { name: 'Productos' }).click()
    await expect(page.getByRole('heading', { name: 'Mis productos' })).toBeVisible()
    await page.getByRole('button', { name: 'Cerrar sesión' }).click()
    await expect(page.getByText('Cerraste sesión')).toBeVisible()
    // "Atras" vuelve al panel en el historial, pero la app consulta al servidor y no lo muestra
    await page.goBack()
    await expect(page).toHaveURL(/\/ingresar/)
    await expect(page.getByRole('heading', { name: /Hola,/ })).toHaveCount(0)
  })

  test('el consumidor cierra sesion desde el menu de la cabecera', async ({ page }) => {
    await ingresar(page, 'consumidor@ccpip.test')
    await expect(page).toHaveURL(/\/$/)
    await page.getByRole('button', { name: 'Mi cuenta' }).click()
    await page.getByRole('menuitem', { name: 'Cerrar sesión' }).click()
    await expect(page.getByText('Cerraste sesión')).toBeVisible()
  })

  test('expira tras el plazo sin actividad (2 min en este stack)', async ({ page }) => {
    test.setTimeout(240_000)
    await ingresar(page, 'productor@ccpip.test')
    await expect(page.getByRole('heading', { name: /Hola,/ })).toBeVisible()
    await expect(page.getByText('Tu sesión expiró')).toBeVisible({ timeout: 180_000 })
    await expect(page).toHaveURL(/\/ingresar/)
  })
})
