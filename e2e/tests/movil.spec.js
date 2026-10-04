import { expect, test } from '@playwright/test'
import { ingresar, sinViolaciones } from './ayudas.js'

/** A 375 px nada se desborda (sin scroll horizontal) y la navegacion sigue siendo usable. */
test.use({ viewport: { width: 375, height: 812 } })

async function sinScrollHorizontal(page) {
  await page.locator('main[aria-busy="true"]').waitFor({ state: 'detached' }).catch(() => {})
  await page.waitForLoadState('networkidle')
  const desborde = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(desborde, `scroll horizontal en ${page.url()}`).toBeLessThanOrEqual(0)
}

test('pantallas del consumidor', async ({ page }) => {
  for (const ruta of ['/', '/buscar', '/buscar?q=cacao', '/productos/1', '/tiendas/2', '/comunidades', '/ingresar']) {
    await page.goto(ruta)
    await expect(page.locator('h1').first()).toBeVisible()
    await sinScrollHorizontal(page)
  }
  await page.goto('/buscar')
  await sinViolaciones(page)
})

test('pantallas del productor y su menu', async ({ page }) => {
  await ingresar(page, 'productor@ccpip.test')
  for (const ruta of ['/productor', '/productor/productos', '/productor/productos/nuevo', '/productor/productos/9?paso=historia', '/productor/productos/9?paso=lote', '/productor/tienda', '/productor/lotes?producto=9']) {
    await page.goto(ruta)
    await expect(page.locator('h1').first()).toBeVisible()
    await sinScrollHorizontal(page)
  }
  await page.getByRole('button', { name: 'Abrir menú' }).click()
  await page.getByRole('link', { name: 'Productos' }).click()
  await expect(page).toHaveURL(/\/productor\/productos$/)
  await sinViolaciones(page)
})
