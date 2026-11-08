import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { BarrasComparativas, Composicion, SerieTemporal } from './index.jsx'
import { readFileSync } from 'node:fs'

describe('fundación de visualizaciones para reportes', () => {
  it('presenta serie temporal y selecciona un punto con ratón y teclado', async () => {
    const onSelect = vi.fn()
    render(<SerieTemporal titulo="Ventas por periodo" unidad="S/" series={[{ id: 'ventas', nombre: 'Ventas', puntos: [{ etiqueta: 'Enero', valor: 12.5 }, { etiqueta: 'Febrero', valor: 0 }] }]} onSelect={onSelect} />)
    expect(screen.getByRole('img', { name: /Ventas por periodo/ })).toBeTruthy()
    expect(screen.getByText('Enero')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Febrero/ }))
    expect(onSelect).toHaveBeenLastCalledWith(expect.objectContaining({ serieId: 'ventas', etiqueta: 'Febrero', valor: 0 }))
    screen.getByRole('button', { name: /Enero/ }).focus()
    await userEvent.keyboard('{Enter}')
    expect(onSelect).toHaveBeenLastCalledWith(expect.objectContaining({ serieId: 'ventas', valor: 12.5 }))
  })

  it('mantiene el detalle exacto seleccionable en comparativas y ranking', () => {
    const onSelect = vi.fn()
    render(<BarrasComparativas titulo="Ventas por productor" unidad="S/" items={[{ id: 'p1', etiqueta: 'Productor A', valor: -2.75 }]} onSelect={onSelect} />)
    fireEvent.click(screen.getByRole('button', { name: /Productor A/ }))
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'p1', valor: -2.75 }))
    expect(screen.getAllByText((_, el) => el.textContent.includes('-2.75')).length).toBeGreaterThan(0)
  })

  it('expone valores de composición sin ocultarlos en gráficos', () => {
    render(<Composicion titulo="Ventas por canal" unidad="S/" partes={[{ id: 'b2c', etiqueta: 'B2C', valor: 10.25 }, { id: 'b2b', etiqueta: 'B2B', valor: null }]} />)
    expect(screen.getByRole('region', { name: 'Ventas por canal' })).toBeTruthy()
    expect(screen.getByText('B2C')).toBeTruthy()
    expect(screen.getByText(/10\.25/)).toBeTruthy()
    expect(screen.getByText(/sin dato/i)).toBeTruthy()
  })

  it('maneja sin datos, punto único, cero, negativo y series largas sin valores no numéricos', () => {
    const { rerender, container } = render(<SerieTemporal titulo="Vacío" series={[]} />)
    expect(screen.getByText(/sin datos/i)).toBeTruthy()
    const puntos = Array.from({ length: 180 }, (_, i) => ({ etiqueta: `P${i}`, valor: i % 7 === 0 ? null : i - 40 }))
    rerender(<SerieTemporal titulo="Larga" series={[{ id: 'x', nombre: 'Cantidad', puntos }]} />)
    expect(container.textContent).not.toMatch(/NaN|Infinity/)
    rerender(<SerieTemporal titulo="Único" series={[{ id: 'x', nombre: 'Importe', puntos: [{ etiqueta: 'Hoy', valor: 0 }] }]} />)
    expect(screen.getByText('Hoy')).toBeTruthy()
  })

  it('respeta preferencias de movimiento reducido y ofrece reglas de impresión', () => {
    const estilos = readFileSync('src/components/reportes-visuales/ReportesVisuales.css', 'utf8')
    expect(estilos).toContain('prefers-reduced-motion:reduce')
    expect(estilos).toMatch(/@media print/)
    expect(estilos).toMatch(/rv-points[^}]*max-height:none/)
  })

  it('no mezcla series con distinta unidad', () => {
    expect(() => render(<SerieTemporal titulo="Inválido" series={[{ id: 'importe', nombre: 'Soles', unidad: 'S/', puntos: [] }, { id: 'cantidad', nombre: 'Unidades', unidad: 'ud', puntos: [] }]} />)).toThrow(/unidad/i)
  })
})
