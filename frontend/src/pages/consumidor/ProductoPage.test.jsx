import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

// Vitest no procesa el CSS: se leen los archivos tal cual y se resuelven los tokens a mano
const css = (archivo) => readFileSync(new URL(archivo, import.meta.url), 'utf8')
const tokens = Object.fromEntries([...css('../../styles/tokens.css').matchAll(/(--[\w-]+):\s*(#[0-9a-f]{6})/gi)].map(([, k, v]) => [k, v]))
const ficha = css('./ProductoPage.css').replace(/\/\*[\s\S]*?\*\//g, '')

/** Valor de una propiedad en la regla con ese selector exacto, con el token ya resuelto. */
function propiedad(selector, nombre) {
  const regla = ficha.split('}').find((r) => r.split('{')[0].trim() === selector)
  const valor = regla?.match(new RegExp(`[{;\\s]${nombre}\\s*:\\s*([^;]+);`))?.[1].trim()
  return valor?.replace(/var\((--[\w-]+)\)/, (_, token) => tokens[token])
}

// WCAG 2.x: luminancia relativa y razon de contraste
function luminancia(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
function contraste(a, b) {
  const [claro, oscuro] = [luminancia(a), luminancia(b)].sort((x, y) => y - x)
  return (claro + 0.05) / (oscuro + 0.05)
}

describe('CCPIP-407 Fondos mas claros en la cosecha y la confianza', () => {
  const textos = ['--color-text-primary', '--color-text-secondary', '--color-text-brand', '--color-text-accent']

  it('"Sobre esta cosecha" usa un fondo mas claro que el arena de antes, con contraste AA', () => {
    const fondo = propiedad('.ficha__cosecha', 'background')
    expect(fondo).toBe(tokens['--color-bg-surface'])
    expect(luminancia(fondo)).toBeGreaterThan(luminancia(tokens['--color-bg-sand']))
    textos.forEach((t) => expect(contraste(tokens[t], fondo)).toBeGreaterThanOrEqual(4.5))
  })

  it('el sello de "Por que puedes confiar" usa un fondo mas claro que el verde suave de antes, con contraste AA', () => {
    const fondo = propiedad('.ficha__confianza .sello--completo', 'background')
    expect(fondo).toBe(tokens['--color-bg-surface'])
    expect(luminancia(fondo)).toBeGreaterThan(luminancia(tokens['--color-bg-brand-subtle']))
    textos.forEach((t) => expect(contraste(tokens[t], fondo)).toBeGreaterThanOrEqual(4.5))
  })
})
