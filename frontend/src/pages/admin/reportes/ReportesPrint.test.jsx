import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('impresión de reportes administrativos', () => {
  it('oculta la navegación de pestañas solo al imprimir la página de reportes', () => {
    const css = readFileSync('src/pages/admin/ReportesPage.css', 'utf8')
    expect(css).toMatch(/@media\s+print\s*\{\s*\.pestanas:has\(\+\s*\.reportes__filtros\)\s*\{\s*display:\s*none/)
  })
})
