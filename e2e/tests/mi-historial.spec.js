import { expect, test } from '@playwright/test'
import { enlaceDelCorreo } from './correos.js'
import { api, enDias, ingresar, sinViolaciones, unico, vigilarConsola } from './ayudas.js'

/**
 * CCPIP-126 (RF-85, Figma S-05): el sub administrador consulta sus propias acciones de gobernanza sobre
 * el stack real. Cubre el aislamiento entre sub administradores, los filtros de periodo y tipo, los
 * enlaces de "Sobre" y que los demas roles no tengan acceso. Cada accion se deshace al final, para no
 * cambiar los datos que usan otras pruebas.
 */
test.describe.configure({ mode: 'serial' })

const SUB = 'sub_admin@ccpip.test'
const OTRO = `otro.sub.${Date.now().toString(36)}@ccpip.test`
const CLAVE_OTRO = 'Selva2026'
const BLOQUEO_PROPIO = unico('Bloqueo propio por denuncias')
const DESBLOQUEO_PROPIO = unico('Desbloqueo propio tras descargo')
const BLOQUEO_AJENO = unico('Bloqueo de otro sub administrador')

/** Id del productor por el nombre de su tienda, visto por el sub administrador de la sesion. */
async function productor(page, organizacion) {
  const lista = (await api(page, 'GET', '/api/admin/productores')).cuerpo
  const fila = lista.find((p) => p.organizacion === organizacion)
  expect(fila, `productor ${organizacion}`).toBeDefined()
  return fila.id
}

async function salir(page) {
  expect((await api(page, 'POST', '/api/auth/logout')).status).toBe(204)
}

const fila = (page, motivo) => page.getByRole('row').filter({ hasText: motivo })

test.describe('Mi historial del sub administrador (CCPIP-126)', () => {
  test('cada sub administrador ve solo sus acciones, con filtros y enlaces', async ({ page, request }) => {
    // Otro sub administrador real: lo crea la Camara y elige su clave desde el correo de bienvenida
    await ingresar(page, 'sudo@ccpip.test')
    const creada = await api(page, 'POST', '/api/admin/cuentas', { nombre: 'Otro Sub Administrador', correo: OTRO, rol: 'SUB_ADMIN' })
    expect(creada.status).toBe(201)
    await salir(page)
    await page.goto(await enlaceDelCorreo(request, OTRO, '/restablecer-contrasena'))
    await page.getByLabel('Contraseña nueva', { exact: true }).fill(CLAVE_OTRO)
    await page.getByLabel('Repite la contraseña nueva').fill(CLAVE_OTRO)
    await page.getByRole('button', { name: 'Guardar contraseña' }).click()
    await expect(page.getByText('Contraseña actualizada')).toBeVisible()

    // El otro sub administrador bloquea y desbloquea a un productor
    await ingresar(page, OTRO, CLAVE_OTRO)
    const pampaMichi = await productor(page, 'Asháninka de Pampa Michi')
    expect((await api(page, 'POST', `/api/admin/productores/${pampaMichi}/bloquear`, { motivo: BLOQUEO_AJENO })).status).toBe(200)
    expect((await api(page, 'POST', `/api/admin/productores/${pampaMichi}/desbloquear`, { motivo: BLOQUEO_AJENO })).status).toBe(200)
    await salir(page)

    // El sub administrador de prueba hace sus propias acciones
    await ingresar(page, SUB)
    const umukai = await productor(page, 'Cacaoteros de Umukai')
    expect((await api(page, 'POST', `/api/admin/productores/${umukai}/bloquear`, { motivo: BLOQUEO_PROPIO })).status).toBe(200)
    expect((await api(page, 'POST', `/api/admin/productores/${umukai}/desbloquear`, { motivo: DESBLOQUEO_PROPIO })).status).toBe(200)

    // Llega por el menu y ve sus acciones, no las del otro
    const errores = vigilarConsola(page)
    await page.getByRole('navigation', { name: 'Sub administrador' }).getByRole('link', { name: 'Mi historial' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Mi historial' })).toBeVisible()
    const bloqueo = fila(page, BLOQUEO_PROPIO)
    await expect(bloqueo.getByText('Bloqueó productor')).toBeVisible()
    await expect(bloqueo.getByRole('link', { name: 'Cacaoteros de Umukai' })).toHaveAttribute('href', `/admin/productores/${umukai}`)
    await expect(fila(page, DESBLOQUEO_PROPIO).getByText('Desbloqueó productor')).toBeVisible()
    await expect(page.getByText(BLOQUEO_AJENO)).toHaveCount(0)
    await expect(page.getByText('Registro inalterable. Las correcciones se registran como nuevas acciones.')).toBeVisible()
    await sinViolaciones(page)

    // Filtro por tipo: sus decisiones sobre productores no son de cuentas
    await page.getByLabel('Tipo de acción').selectOption({ label: 'Cuentas' })
    await expect(page.getByText(BLOQUEO_PROPIO)).toHaveCount(0)
    await page.getByLabel('Tipo de acción').selectOption({ label: 'Productores' })
    await expect(fila(page, BLOQUEO_PROPIO)).toBeVisible()

    // Filtro por periodo: un mes que termino hace 30 dias no las incluye
    await page.getByLabel('Desde').fill(enDias(-60))
    await page.getByLabel('Hasta').fill(enDias(-31))
    await expect(page.getByText(BLOQUEO_PROPIO)).toHaveCount(0)
    await page.getByLabel('Hasta').fill(enDias(0))
    await expect(fila(page, BLOQUEO_PROPIO)).toBeVisible()

    // "Sobre" lleva a la ficha del productor
    await fila(page, BLOQUEO_PROPIO).getByRole('link', { name: 'Cacaoteros de Umukai' }).click()
    await expect(page).toHaveURL(new RegExp(`/admin/productores/${umukai}$`))
    await expect(page.getByRole('heading', { level: 1, name: 'Cacaoteros de Umukai' })).toBeVisible()
    expect(errores).toEqual([])
    await salir(page)

    // Y el otro sub administrador ve la suya, no las del sub administrador de prueba
    await ingresar(page, OTRO, CLAVE_OTRO)
    await page.goto('/admin/mi-historial')
    await expect(page.getByText(BLOQUEO_AJENO).first()).toBeVisible()
    await expect(page.getByText(BLOQUEO_PROPIO)).toHaveCount(0)
  })

  test('los demas roles no tienen Mi historial', async ({ page }) => {
    for (const correo of ['sudo@ccpip.test', 'productor@ccpip.test', 'consumidor@ccpip.test', 'admin_acopio@ccpip.test']) {
      await ingresar(page, correo)
      expect((await api(page, 'GET', '/api/admin/mi-historial')).status, correo).toBe(403)
      await salir(page)
    }
    // El super administrador no lo ve en su menu y, si entra por la ruta, vuelve a su tablero
    await ingresar(page, 'sudo@ccpip.test')
    await page.goto('/admin/mi-historial')
    await expect(page).toHaveURL(/\/admin$/)
    await expect(page.getByRole('link', { name: 'Mi historial' })).toHaveCount(0)
  })
})
