import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

// CCPIP-435: jsdom no calcula el layout, así que se verifican las reglas que garantizan la aceptación visual
const css = readFileSync('src/components/layout/DecoracionBotanica.css', 'utf8')
const editorial = readFileSync('src/pages/consumidor/ConsumerEditorial.css', 'utf8')
const regla = (selector) => css.match(new RegExp(`${selector.replace(/[.]/g, '\\.')}\\s*\\{([^}]*)\\}`))?.[1] ?? ''

describe('Herbario en los márgenes de la tienda (CCPIP-435)', () => {
  it('cada rama cabe en el margen que deja el contenido de 1440px, con 24px de aire', () => {
    // Si el ancho del contenido cambia, esta prueba avisa que el margen de las ramas también debe cambiar
    expect(editorial).toMatch(/\.layout-tienda \.contenedor,[^{]*\{[^}]*max-width:\s*1440px/)
    // 100% de la capa fija (sin la barra de scroll): 12px al borde + 24px de aire hasta el contenido
    expect(regla('.decoracion-botanica__rama')).toMatch(/width:\s*min\(240px,\s*calc\(\(100% - 1440px\) \/ 2 - 36px\)\)/)
    expect(regla('.decoracion-botanica__rama--izquierda')).toMatch(/left:\s*12px/)
    expect(regla('.decoracion-botanica__rama--derecha')).toMatch(/right:\s*12px/)
    expect(regla('.decoracion-botanica__rama')).toMatch(/max-height:\s*calc\(100vh - 48px\)/)
  })

  it('va detrás de todo y no recibe el puntero ni suma scroll', () => {
    expect(regla('.layout-tienda')).toMatch(/isolation:\s*isolate/)
    const capa = regla('.decoracion-botanica')
    expect(capa).toMatch(/position:\s*fixed/)
    expect(capa).toMatch(/z-index:\s*-1/)
    expect(capa).toMatch(/overflow:\s*hidden/)
    expect(capa).toMatch(/pointer-events:\s*none/)
  })

  it('se oculta en laptops, tablet y móvil, donde las ramas no llegarían a ~96px, y no se anima', () => {
    expect(css).toMatch(/@media \(max-width: 1719px\)\s*\{\s*\.decoracion-botanica\s*\{\s*display:\s*none;/)
    expect(css).not.toMatch(/animation|transition/)
  })
})
