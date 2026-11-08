import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import TablaReporte from './TablaReporte.jsx'

const source = (path) => readFileSync(resolve(import.meta.dirname, '..', path), 'utf8')

describe('CCPIP-127 report presentation', () => {
  it('preserves named table semantics and keyboard focus for horizontal scrolling', async () => {
    const user = userEvent.setup()
    render(<TablaReporte id="entradas" titulo="Entradas" columnas={[{ clave: 'cantidad', etiqueta: 'Cantidad', valor: (f) => f.cantidad }]}
      filas={[{ id: 1, cantidad: 4 }]} clave={(f) => f.id} vacio="Sin entradas" />)
    const table = screen.getByRole('table', { name: 'Entradas' })
    expect(table).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('columnheader', { name: 'Cantidad' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '4' })).toBeInTheDocument()
    await user.tab()
    expect(table).toHaveFocus()
  })

  it('does not insert a focus stop for an empty report', () => {
    render(<TablaReporte id="sin-entradas" titulo="Entradas" columnas={[]} filas={[]} clave={(f) => f.id} vacio="Sin entradas" />)
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByText('Sin entradas')).toBeInTheDocument()
  })

  it('keeps both bar implementations on the same shared palette without legacy pill overrides', () => {
    for (const path of ['components/TablaReporte.css', 'pages/productor/ReportesPage.css']) {
      const css = source(path)
      expect(css).toContain('var(--editorial-forest)')
      expect(css).toContain('var(--editorial-seed)')
      expect(css).not.toMatch(/#[0-9a-f]{3,8}\b/i)
      expect(css).not.toContain('999px')
      expect(css).not.toContain('var(--color-bg-accent)')
    }
  })

  it('styles the actual KPI value and acopio strip, and provides print layout rules', () => {
    const reports = source('pages/productor/ReportesPage.css')
    expect(reports).toContain('.reportes-acopio__filtros ~ .productor__kpis')
    expect(reports).toContain('.tarjeta-kpi__valor')
    expect(reports).toContain('@media print')
    expect(reports).toContain(".layout-productor .layout-productor__contenido .tabla[role='table'] { overflow: visible; }")
    expect(source('pages/admin/reportes/Indicadores.css')).toContain("[aria-labelledby^='indicadores-'] > .productor__kpis > .tarjeta-kpi")
    const panel = source('components/layout/OperationalEditorial.css')
    expect(panel).toContain('@media print')
    expect(panel).toContain('.layout-productor__sidebar')
  })
})
