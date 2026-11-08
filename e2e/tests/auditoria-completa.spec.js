import { expect, test } from '@playwright/test'
import { api, vigilarConsola } from './ayudas.js'

// CCPIP-509: ejercita eventos de LOGIN_EXITOSO creados por el backend real (sin interceptar API).
// La traza de autenticación tiene referencia LOGIN; los campos de ubicación/decisión/contexto se
// validan cuando el evento realmente los trae, sin fabricar datos ni asumir que LOGIN los tiene.
test.describe.configure({ mode: 'serial', retries: 0 })

const ACTORES = [
  { correo: 'sudo@ccpip.test', rol: 'SUPER_ADMIN', permitido: true },
  { correo: 'consumidor@ccpip.test', rol: 'CONSUMIDOR' },
  { correo: 'productor@ccpip.test', rol: 'PRODUCTOR' },
  { correo: 'admin_acopio@ccpip.test', rol: 'ADMIN_PUNTO' },
  { correo: 'sub_admin@ccpip.test', rol: 'SUB_ADMIN' },
  { correo: 'distribuidor@ccpip.test', rol: 'DISTRIBUIDOR' },
]
const CLAVE = 'Demo1234!'
const URL_AUDITORIA = '/api/admin/auditoria'

async function ingresar(page, correo, clave = CLAVE) {
  await page.goto('/ingresar')
  await page.getByLabel(/Correo electrónico|Email address/).fill(correo)
  await page.getByLabel(/^(Contraseña|Password)$/).fill(clave)
  await page.getByRole('button', { name: /^(Iniciar sesión|Sign in)$/ }).click()
  await expect(page).not.toHaveURL(/\/ingresar/)
}

async function salir(page) {
  expect((await api(page, 'POST', '/api/auth/logout')).status).toBe(204)
}

async function cambiarIdioma(page, idioma) {
  const selector = page.getByRole('button', { name: /^(Idioma|Language):/ })
  await selector.click()
  await page.getByRole('menuitemradio', { name: idioma === 'en' ? 'English' : 'Español' }).click()
  await expect(page.locator('html')).toHaveAttribute('lang', idioma)
}

test('API: solo SUPER_ADMIN consulta el mapa global; SUB_ADMIN conserva Mi historial', async ({ page }) => {
  for (const actor of ACTORES) {
    await ingresar(page, actor.correo, CLAVE)
    const sesion = await api(page, 'GET', '/api/auth/me')
    expect(sesion.status).toBe(200)
    expect(sesion.cuerpo.user.role).toBe(actor.rol)
    const respuesta = await api(page, 'GET', URL_AUDITORIA)
    expect(respuesta.status, actor.rol).toBe(actor.permitido ? 200 : 403)
    if (actor.rol === 'SUB_ADMIN') {
      const historial = await api(page, 'GET', '/api/admin/mi-historial')
      expect(historial.status, 'SUB_ADMIN retains their own history').toBe(200)
    }
    await salir(page)
  }
})

test('la autenticación real produce una fila filtrable y visible en español e inglés', async ({ page }) => {
  // actor de prueba existente: su LOGIN_EXITOSO es la operación real que genera la fila examinada.
  await ingresar(page, 'consumidor@ccpip.test', CLAVE)
  const evento = await api(page, 'GET', URL_AUDITORIA)
  expect(evento.status).toBe(403)
  await salir(page)
  const consola = vigilarConsola(page)
  await ingresar(page, 'sudo@ccpip.test', CLAVE)
  const listado = await api(page, 'GET', URL_AUDITORIA)
  expect(listado.status).toBe(200)
  expect(listado.cuerpo.registros).toBeInstanceOf(Array)
  const login = listado.cuerpo.registros.find((r) => r.accion === 'LOGIN_EXITOSO' && r.referencia === 'LOGIN'
    && r.correo === 'sudo@ccpip.test')
  expect(login, 'successful login must be persisted by the real auth operation').toBeDefined()
  expect(login.actor).toBe('USUARIO')

  await page.goto('/admin/auditoria')
  await cambiarIdioma(page, 'es')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByText('sudo@ccpip.test').first()).toBeVisible()
  const tabla = page.getByRole('table')
  const fila = tabla.getByRole('row').filter({ hasText: 'sudo@ccpip.test' }).filter({ hasText: 'LOGIN' }).first()
  await expect(fila).toBeVisible()
  // El API transforma solo campos que existen: referencia es obligatoria para el evento de login;
  // ubicación, decisión y contexto son opcionales en este evento y se afirman si hay valor.
  await expect(fila).toContainText('LOGIN')
  if (login.ubicacionId != null) await expect(fila).toContainText(String(login.ubicacionId))
  if (login.decision) await expect(fila).toContainText(login.decision)
  if (login.correlacion) await expect(fila).toContainText(login.correlacion)

  // Filtros UI efectivos: actor/usuario/referencia se envían al buscar, y el resultado discrimina.
  await page.getByLabel(/Usuario|User/).fill('sudo@ccpip.test')
  await page.getByLabel(/Referencia|Reference/).fill('LOGIN')
  await page.getByLabel(/Actor/).selectOption('USUARIO')
  await page.getByRole('button', { name: /Buscar|Search/ }).click()
  await expect(tabla.getByRole('row').filter({ hasText: 'sudo@ccpip.test' }).filter({ hasText: 'LOGIN' }).first()).toBeVisible()
  await expect(page.getByLabel(/Actor|Actor type/)).toHaveValue('USUARIO')

  const evidencia = page.screenshot({ path: test.info().outputPath('auditoria-desktop-es.png'), fullPage: true })
  await evidencia
  await cambiarIdioma(page, 'en')
  await expect(page.locator('html')).toHaveAttribute('lang', 'en')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.screenshot({ path: test.info().outputPath('auditoria-desktop-en.png'), fullPage: true })
  expect(consola).toEqual([])
})

test('cambio real de parámetro conserva actor histórico y referencia sin duplicar', async ({ page }) => {
  await ingresar(page, 'sudo@ccpip.test', CLAVE)
  const respuesta = await api(page, 'PUT', '/api/admin/parametros/vigencia-carrito', { horas: 7 })
  expect(respuesta.status).toBe(200)
  const filtro = `${URL_AUDITORIA}?accion=PARAMETRO_CAMBIADO&rol=SUPER_ADMIN&actor=USUARIO`
  const listado = await api(page, 'GET', filtro)
  expect(listado.status).toBe(200)
  const evento = listado.cuerpo.registros.find((r) => r.correo === 'sudo@ccpip.test'
    && r.detalle.includes('CARRITO_VIGENCIA_HORAS'))
  expect(evento).toBeDefined()
  expect(evento.rolHistorico).toBe(true)
  const porReferencia = await api(page, 'GET', `${filtro}&referencia=${encodeURIComponent(evento.referencia)}`)
  expect(porReferencia.status).toBe(200)
  expect(porReferencia.cuerpo.registros.filter((r) => r.clave === evento.clave)).toHaveLength(1)
  expect(JSON.stringify(porReferencia.cuerpo)).not.toMatch(/contrasena_hash|clave_idempotencia|numero_cuenta|token_hash/)
})

test('interfaz móvil en ES/EN: captura y sin overflow horizontal de página', async ({ page }) => {
  await page.setViewportSize({ width: 393, height: 851 })
  await page.goto('/')
  await cambiarIdioma(page, 'es')
  await ingresar(page, 'sudo@ccpip.test', CLAVE)
  await page.goto('/admin/auditoria')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  const medir = () => page.evaluate(() => ({
    anchoVentana: document.documentElement.clientWidth,
    anchoDocumento: document.documentElement.scrollWidth,
    scrollX: window.scrollX,
  }))
  const dimensionesES = await medir()
  expect(dimensionesES.anchoDocumento).toBeLessThanOrEqual(dimensionesES.anchoVentana)
  expect(dimensionesES.scrollX).toBe(0)
  await page.screenshot({ path: test.info().outputPath('auditoria-mobile-es.png'), fullPage: true })
  await cambiarIdioma(page, 'en')
  const dimensionesEN = await medir()
  expect(dimensionesEN.anchoDocumento).toBeLessThanOrEqual(dimensionesEN.anchoVentana)
  expect(dimensionesEN.scrollX).toBe(0)
  await page.screenshot({ path: test.info().outputPath('auditoria-mobile-en.png'), fullPage: true })
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
})
