import { test } from '@playwright/test'
import { capturar, ingresar } from './ayudas.js'

/**
 * Capturas de cada pantalla a 1440 px para compararlas con su par del Figma (02 · Consumidor
 * final y 03 · Productor). Se guardan en e2e/capturas y en CI como artefacto.
 */
test.describe('capturas para comparar con el Figma', () => {
  test('consumidor', async ({ page }) => {
    await page.goto('/')
    await capturar(page, 'CW-01-inicio')
    await page.goto('/buscar?q=cacao')
    await capturar(page, 'CW-02-catalogo')
    await page.getByRole('link', { name: /Cacao en grano/ }).first().click()
    await capturar(page, 'CW-03-ficha')
    await page.getByRole('link', { name: 'C.N. Yarina' }).first().click()
    await capturar(page, 'CW-04-tienda')
    await page.goto('/comunidades')
    await capturar(page, 'CW-comunidades')
    await page.goto('/ingresar')
    await capturar(page, 'CW-08-ingresar')
  })

  test('productor', async ({ page }) => {
    await ingresar(page, 'productor@ccpip.test')
    await capturar(page, 'PW-01-panel')
    await page.goto('/productor/productos')
    await capturar(page, 'PW-03-productos')
    await page.goto('/productor/productos/nuevo')
    await capturar(page, 'PW-04-datos')
    await page.goto('/productor/tienda')
    await capturar(page, 'PW-08-tienda')
    await page.goto('/productor/productos')
    await page.getByRole('button', { name: /Más acciones para/ }).first().click()
    await page.getByRole('menuitem', { name: 'Registrar lote' }).click()
    await page.getByRole('heading', { name: 'Lotes de este producto' }).waitFor()
    await capturar(page, 'PW-06-lote')
  })
})
