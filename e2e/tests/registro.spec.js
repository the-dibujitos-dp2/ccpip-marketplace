import { expect, test } from '@playwright/test'
import { enlaceDeVerificacion } from './correos.js'

/**
 * CCPIP-26 y CCPIP-328 sobre el stack real: el backend envia el enlace por SMTP (Mailpit en e2e)
 * y solo despues de usarlo la cuenta puede ingresar.
 */
test.describe('Registro y verificacion del correo (API)', () => {
  test('la cuenta nueva ingresa solo despues de abrir el enlace, que sirve una vez', async ({ request }) => {
    const correo = `e2e.${Date.now()}@registro.test`
    const clave = 'Bosque2026'

    const registro = await request.post('/api/auth/registro', {
      data: { firstName: 'Lucía', lastName: 'Ramírez', email: correo, phone: '', password: clave, acceptTerms: true },
    })
    expect(registro.status()).toBe(201)
    expect(await registro.json()).toEqual({ email: correo, emailSent: true })

    const sinVerificar = await request.post('/api/auth/login', { data: { email: correo, password: clave } })
    expect(sinVerificar.status()).toBe(403)
    expect((await sinVerificar.json()).code).toBe('CORREO_NO_CONFIRMADO')

    const token = new URL(await enlaceDeVerificacion(request, correo)).searchParams.get('token')
    const verificacion = await request.post('/api/auth/verificar-correo', { data: { token } })
    expect(verificacion.status()).toBe(200)

    const otraVez = await request.post('/api/auth/verificar-correo', { data: { token } })
    expect(otraVez.status()).toBe(410)

    const ingreso = await request.post('/api/auth/login', { data: { email: correo, password: clave } })
    expect(ingreso.status()).toBe(200)
    expect((await ingreso.json()).user.role).toBe('CONSUMIDOR')
  })
})
