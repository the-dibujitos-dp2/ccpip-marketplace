import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const dir = path.dirname(fileURLToPath(import.meta.url))
const css = readFileSync(path.join(dir, 'RutasEditorial.css'), 'utf8')
const layout = readFileSync(path.join(dir, '../../components/layout/LayoutTienda.jsx'), 'utf8')

describe('RutasEditorial (presentational scope)', () => {
  it('is imported only through the consumer storefront layout', () => {
    expect(layout).toContain("import '../../pages/consumidor/RutasEditorial.css'")
  })

  it('skins actual catalog, product, community, cart, checkout, order and account selectors', () => {
    for (const selector of [
      '.buscar__cuerpo', '.buscar__grilla', '.filtros', '.casilla', '.ficha__principal', '.ficha__foto',
      '.ficha__productor', '.ficha__cita', '.historia', '.tienda-publica__contenido', '.tienda-publica__historia',
      '.carrito__grupo', '.carrito__resumen', '.compra__seccion', '.compra__resumen', '.pedido__tarjeta',
      '.mis-pedidos__lateral', '.mis-pedidos__lista', '.mis-datos', '.mis-direcciones__tarjeta', '.mis-favoritos__item',
      '.tarjeta-producto__media', '.tarjeta-tienda', '.boton--primario', '.campo__control',
    ]) expect(css).toMatch(new RegExp(`${selector.replace(/[.*+?^${}()|[\\]\\]/g, '\\$&')}[^{}]*\\{`))
  })

  it('does not style header, footer, hero, access or home surfaces', () => {
    expect(css).not.toMatch(/(?:cabecera-tienda|\.pie\b|\.hero\b|\.ingresar\b|\.inicio-tienda)/)
  })

  it('keeps two product columns and one-column checkout on narrow screens', () => {
    expect(css).toMatch(/@media\s*\(max-width:\s*600px\)[\s\S]*?\.buscar__grilla[\s\S]*?repeat\(2,/)
    expect(css).toMatch(/@media\s*\(max-width:\s*960px\)[\s\S]*?\.compra__cuerpo[\s\S]*?minmax\(0,\s*1fr\)/)
  })
})
