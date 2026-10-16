import { expect, test } from '@playwright/test'
import { enlaceDelCorreo } from './correos.js'

/**
 * CCPIP-33 sobre el stack real: el backend envia el enlace por SMTP (Mailpit en e2e), la persona
 * elige una clave nueva desde el navegador e ingresa con ella. Usa una cuenta nueva para no cambiar
 * la clave de las cuentas de demostracion que usan las demas pruebas.
 */
test('recupera la contraseña con el enlace del correo e ingresa con la nueva', async ({ page, request }) => {
  const correo = `e2e.${Date.now()}@recuperar.test`
  const registro = await request.post('/api/auth/registro', {
    data: { firstName: 'Rosa', lastName: 'Olvido', email: correo, phone: '', password: 'Bosque2026', acceptTerms: true },
  })
  expect(registro.status()).toBe(201)

  await page.goto('/ingresar')
  await page.getByLabel('Correo electrónico').fill(correo)
  await page.getByRole('link', { name: '¿Olvidaste tu contraseña?' }).click()
  await expect(page.getByRole('heading', { name: 'Recupera tu contraseña' })).toBeVisible()
  await page.getByRole('button', { name: 'Enviar enlace' }).click()
  await expect(page.getByText('Revisa tu correo')).toBeVisible()

  await page.goto(await enlaceDelCorreo(request, correo, '/restablecer-contrasena'))
  await page.getByLabel('Contraseña nueva', { exact: true }).fill('Selva2026')
  await page.getByLabel('Repite la contraseña nueva').fill('Selva2026')
  await page.getByRole('button', { name: 'Guardar contraseña' }).click()
  await expect(page.getByText('Contraseña actualizada')).toBeVisible()

  // La clave anterior ya no sirve; la nueva si (abrir el enlace tambien confirma el correo)
  const anterior = await request.post('/api/auth/login', { data: { email: correo, password: 'Bosque2026' } })
  expect(anterior.status()).toBe(401)
  await page.getByRole('button', { name: 'Iniciar sesión' }).click()
  await expect(page.getByLabel('Correo electrónico')).toHaveValue(correo)
  await page.getByLabel('Contraseña', { exact: true }).fill('Selva2026')
  await page.getByRole('button', { name: 'Iniciar sesión' }).click()
  await expect(page).not.toHaveURL(/\/ingresar/)
})
