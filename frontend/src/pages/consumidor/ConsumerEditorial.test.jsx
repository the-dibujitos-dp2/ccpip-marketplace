import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('consumer editorial shell', () => {
  it('loads the shared editorial stylesheet from both consumer shells', () => {
    const root = resolve(import.meta.dirname, '../..')
    const tienda = readFileSync(resolve(root, 'components/layout/LayoutTienda.jsx'), 'utf8')
    const acceso = readFileSync(resolve(root, 'pages/consumidor/LayoutAcceso.jsx'), 'utf8')
    expect(tienda).toContain("ConsumerEditorial.css")
    expect(acceso).toContain("ConsumerEditorial.css")
  })
  it('uses the approved documentary portrait in the access layout', () => {
    const root = resolve(import.meta.dirname, '../..')
    const acceso = readFileSync(resolve(root, 'pages/consumidor/LayoutAcceso.jsx'), 'utf8')
    expect(acceso).toContain('retrato.jpg')
    expect(acceso).toContain('ingresar__retrato')
  })
})
