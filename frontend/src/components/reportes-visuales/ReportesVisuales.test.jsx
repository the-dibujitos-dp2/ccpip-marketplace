import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import i18n from '../../i18n/index.js'
import { BarrasComparativas, Composicion, SerieTemporal } from './index.jsx'

const serie = (puntos, extra = {}) => [{ id: 'ventas', nombre: 'Ventas', puntos, ...extra }]
const punto = (etiqueta, valor) => ({ etiqueta, valor })
const graph = (container) => container.querySelector('.rv-chart')

afterEach(async () => { await act(async () => i18n.changeLanguage('es')) })

describe('fundación de visualizaciones para reportes', () => {
  it('selecciona marcas reales con ratón y recorre puntos con teclado desde una sola parada', () => {
    const onSelect = vi.fn()
    const { container } = render(<SerieTemporal titulo="Ventas por periodo" unidad="S/" series={serie([punto('Enero', 12.5), punto('Febrero', 0)])} onSelect={onSelect} />)
    const chart = graph(container)
    expect(chart.getAttribute('role')).toBe('group')
    expect(chart.getAttribute('tabindex')).toBe('0')
    expect(chart.querySelectorAll('[tabindex="0"]')).toHaveLength(0)
    fireEvent.click(within(chart).getByRole('button', { name: /Febrero/ }))
    expect(onSelect).toHaveBeenLastCalledWith({ serieId: 'ventas', serie: 'Ventas', etiqueta: 'Febrero', valor: 0, unidad: 'S/' })
    fireEvent.keyDown(chart, { key: 'ArrowLeft' })
    expect(onSelect).toHaveBeenLastCalledWith(expect.objectContaining({ etiqueta: 'Enero', valor: 12.5 }))
    expect(container.querySelector('.rv-detail').textContent).toContain('S/ 12.5')
    fireEvent.keyDown(chart, { key: 'End' })
    expect(onSelect).toHaveBeenLastCalledWith(expect.objectContaining({ etiqueta: 'Febrero', valor: 0 }))
    fireEvent.keyDown(chart, { key: 'Home' })
    expect(onSelect).toHaveBeenLastCalledWith(expect.objectContaining({ etiqueta: 'Enero', valor: 12.5 }))
  })

  it('mantiene una alternativa HTML de valores exactos, incluyendo datos ausentes explícitos', () => {
    render(<SerieTemporal titulo="Alternativa" series={serie([punto('Enero', 12.5), punto('Febrero', null)])} />)
    const alternative = screen.getByLabelText('Puntos de la serie')
    expect(alternative.tagName).toBe('DETAILS')
    expect(within(alternative).getByRole('button', { name: /Enero/ }).textContent).toContain('12.5')
    expect(within(alternative).getByRole('button', { name: /Febrero/ }).disabled).toBe(true)
    expect(within(alternative).getByText('Sin dato')).toBeTruthy()
  })

  it('no conserva números obsoletos al refrescar la serie y elimina detalle al salir del rango', () => {
    const { container, rerender } = render(<SerieTemporal titulo="Ventas" series={serie([punto('Enero', 100)])} />)
    fireEvent.click(within(graph(container)).getByRole('button', { name: /Enero/ }))
    rerender(<SerieTemporal titulo="Ventas" series={serie([punto('Enero', 9)])} />)
    expect(container.querySelector('.rv-detail').textContent).toContain('9')
    expect(container.querySelector('.rv-detail').textContent).not.toContain('100')
    rerender(<SerieTemporal titulo="Ventas" series={serie([punto('Marzo', 3)])} />)
    expect(container.querySelector('.rv-detail')).toBeNull()
  })

  it('conserva la selección frente a un rerender equivalente, sin depender de identidad del array', () => {
    const { container, rerender } = render(<SerieTemporal titulo="Ventas" series={serie([punto('Enero', 7)])} />)
    fireEvent.keyDown(graph(container), { key: 'ArrowRight' })
    rerender(<SerieTemporal titulo="Ventas" series={serie([punto('Enero', 7)])} />)
    expect(container.querySelector('.rv-detail').textContent).toContain('Enero — 7')
  })

  it('corta la línea ante null, sin interpolar una evolución inexistente', () => {
    const { container } = render(<SerieTemporal titulo="Huecos" series={serie([punto('Enero', 1), punto('Febrero', null), punto('Marzo', 2)])} />)
    const lines = graph(container).querySelectorAll('polyline')
    expect(lines).toHaveLength(2)
    for (const line of lines) expect(line.getAttribute('points').split(' ')).toHaveLength(1)
    expect(graph(container).querySelectorAll('circle')).toHaveLength(2)
  })

  it('ordena etiquetas ISO lexicográficamente entre años y meses y alinea valores por fecha', () => {
    const onSelect = vi.fn()
    const { container } = render(<SerieTemporal titulo="Fechas ordenadas" onSelect={onSelect} series={[
      { id: 'a', nombre: 'A', puntos: [punto('2025-12-01', 12), punto('2026-02-01', 2)] },
      { id: 'b', nombre: 'B', puntos: [punto('2026-01-01', 1), punto('2025-12-01', 120)] },
    ]} />)
    const chart = graph(container)
    const labels = [...chart.querySelectorAll('text.rv-label')].filter((label) => /^\d{4}-\d{2}-\d{2}$/.test(label.textContent))
    expect(labels.map((label) => label.textContent)).toEqual(['2025-12-01', '2026-01-01', '2026-02-01'])
    const positions = Object.fromEntries(labels.map((label) => [label.textContent, Number(label.getAttribute('x'))]))
    expect(positions['2025-12-01']).toBeLessThan(positions['2026-01-01'])
    expect(positions['2026-01-01']).toBeLessThan(positions['2026-02-01'])
    for (const [serieId, serie, etiqueta, valor] of [
      ['a', 'A', '2025-12-01', 12], ['a', 'A', '2026-02-01', 2],
      ['b', 'B', '2026-01-01', 1], ['b', 'B', '2025-12-01', 120],
    ]) {
      const mark = within(chart).getByRole('button', { name: `${serie}, ${etiqueta}: ${valor}` })
      expect(Number(mark.getAttribute('cx'))).toBe(positions[etiqueta])
      fireEvent.click(mark)
      expect(onSelect).toHaveBeenLastCalledWith({ serieId, serie, etiqueta, valor, unidad: '' })
      expect(container.querySelector('.rv-detail').textContent).toBe(`${serie}: ${etiqueta} — ${valor}`)
    }
  })

  it('alinea fechas entre series y no conecta períodos omitidos', () => {
    const { container } = render(<SerieTemporal titulo="Fechas" series={[
      { id: 'a', nombre: 'A', puntos: [punto('2026-01-01', 1), punto('2026-03-01', 2)] },
      { id: 'b', nombre: 'B', puntos: [punto('2026-02-01', 3), punto('2026-03-01', 4)] },
    ]} />)
    const a = graph(container).querySelector('.rv-series')
    expect(a.querySelectorAll('polyline')).toHaveLength(2)
    const circles = graph(container).querySelectorAll('circle')
    expect(circles[1].getAttribute('cx')).toBe(circles[3].getAttribute('cx'))
  })

  it('usa toda la altura positiva y distingue los valores negativos de cero', () => {
    const { container, rerender } = render(<SerieTemporal titulo="Escala" series={serie([punto('A', 0), punto('B', 100)])} />)
    let circles = graph(container).querySelectorAll('circle')
    expect(Number(circles[0].getAttribute('cy')) - Number(circles[1].getAttribute('cy'))).toBe(175)
    rerender(<SerieTemporal titulo="Escala" series={serie([punto('A', -10), punto('B', 0), punto('C', 10)])} />)
    circles = graph(container).querySelectorAll('circle')
    expect(Number(circles[0].getAttribute('cy'))).toBeGreaterThan(Number(circles[1].getAttribute('cy')))
    expect(Number(circles[1].getAttribute('cy'))).toBeGreaterThan(Number(circles[2].getAttribute('cy')))
  })

  it('maneja vacío, todos null, punto cero único y decimales sin datos numéricos inventados', () => {
    const { container, rerender } = render(<SerieTemporal titulo="Vacío" series={[]} />)
    expect(screen.getByText('Sin datos para mostrar.')).toBeTruthy()
    rerender(<SerieTemporal titulo="Ausentes" series={serie([punto('Hoy', null), punto('Mañana', '')])} />)
    expect(graph(container)).toBeNull()
    expect(container.textContent).not.toMatch(/NaN|Infinity/)
    rerender(<SerieTemporal titulo="Cero" series={serie([punto('Hoy', 0)])} />)
    expect(within(graph(container)).getByRole('button', { name: /Hoy: 0/ })).toBeTruthy()
    expect(graph(container).querySelectorAll('circle')).toHaveLength(1)
  })

  it('ofrece series largas sin saturar la secuencia de tabulación ni producir NaN', () => {
    const puntos = Array.from({ length: 180 }, (_, index) => punto(`P${index}`, index % 7 === 0 ? null : index - 40.25))
    const { container } = render(<SerieTemporal titulo="Larga" series={serie(puntos)} />)
    expect(container.textContent).not.toMatch(/NaN|Infinity/)
    expect(graph(container).querySelectorAll('[tabindex="0"]')).toHaveLength(0)
    expect(graph(container).querySelectorAll('circle')).toHaveLength(puntos.filter((item) => item.valor !== null).length)
  })

  it('reacciona al idioma global sin necesitar un locale pasado por cada consumidor', async () => {
    const { container } = render(<SerieTemporal titulo="Trend" series={serie([punto('Jan', null)])} />)
    await act(async () => i18n.changeLanguage('en'))
    expect(container.textContent).toContain('No data to display.')
    expect(container.textContent).toContain('Data points')
    expect(container.textContent).toContain('No data')
    expect(container.textContent).not.toContain('Sin dato')
  })

  it('permite locale explícito y conserva el formato exacto de decimales', () => {
    const { container } = render(<SerieTemporal titulo="Trend" locale="en-GB" series={serie([punto('Jan', 1234.5)])} />)
    fireEvent.keyDown(graph(container), { key: 'ArrowRight' })
    expect(container.querySelector('.rv-detail').textContent).toContain('1,234.5')
    expect(container.textContent).toContain('Use the arrow keys')
  })

  it('no mezcla unidades diferentes en una escala', () => {
    expect(() => render(<SerieTemporal titulo="Inválido" series={[
      { id: 'importe', nombre: 'Soles', unidad: 'S/', puntos: [] },
      { id: 'cantidad', nombre: 'Unidades', unidad: 'ud', puntos: [] },
    ]} />)).toThrow(/unidad/i)
  })

  it('presenta barras firmadas con una línea base cero compartida', () => {
    const onSelect = vi.fn()
    const { container } = render(<BarrasComparativas titulo="Comparativa" unidad="S/" onSelect={onSelect} items={[
      { id: 'a', etiqueta: 'A', valor: -2.75 }, { id: 'b', etiqueta: 'B', valor: 2.75 }, { id: 'c', etiqueta: 'C', valor: null },
    ]} />)
    const bars = container.querySelectorAll('.rv-bar-fill')
    expect(bars[0].style.left).toBe('0%')
    expect(bars[0].style.width).toBe('50%')
    expect(bars[1].style.left).toBe('50%')
    expect(bars[1].style.width).toBe('50%')
    expect(bars[2].style.width).toBe('0%')
    fireEvent.click(screen.getByRole('button', { name: /A: -S\/\s*2.75/ }))
    expect(onSelect).toHaveBeenCalledWith({ id: 'a', etiqueta: 'A', valor: -2.75, unidad: 'S/' })
  })

  it('actualiza detalle de barras con valores vigentes y lo elimina si ya no existe el elemento', () => {
    const { container, rerender } = render(<BarrasComparativas titulo="Ranking" items={[{ id: 'a', etiqueta: 'A', valor: 100 }]} />)
    fireEvent.click(screen.getByRole('button', { name: /A: 100/ }))
    rerender(<BarrasComparativas titulo="Ranking" items={[{ id: 'a', etiqueta: 'A', valor: 9 }]} />)
    expect(container.querySelector('.rv-detail').textContent).toBe('A: 9')
    rerender(<BarrasComparativas titulo="Ranking" items={[]} />)
    expect(container.querySelector('.rv-detail')).toBeNull()
  })

  it('comparte el cero entre barras apiladas positivas y una fila firmada negativa', () => {
    const { container } = render(<BarrasComparativas titulo="Mixto" items={[
      { id: 'n', etiqueta: 'Negativo', valor: -100 },
      { id: 'p', etiqueta: 'Positivo', valor: 100, segmentos: [{ id: 'a', etiqueta: 'A', valor: 25 }, { id: 'b', etiqueta: 'B', valor: 75 }] },
    ]} />)
    const track = container.querySelectorAll('.rv-bar-track')[1]
    const parts = track.querySelectorAll('.rv-bar-fill')
    expect(track.style.getPropertyValue('--rv-zero')).toBe('50%')
    expect(parts[0].style.left).toBe('50%')
    expect(parts[1].style.left).toBe('62.5%')
    expect(parts[0].style.width).toBe('12.5%')
    expect(parts[1].style.width).toBe('37.5%')
  })

  it('deriva la leyenda visible de los segmentos recibidos, sin inventar nombres de canales', () => {
    render(<BarrasComparativas titulo="Canales" items={[{ id: 'p', etiqueta: 'P', valor: 4, segmentos: [
      { id: 'a', etiqueta: 'Minorista', valor: 1 }, { id: 'b', etiqueta: 'Mayorista', valor: 3 },
    ] }]} />)
    expect(screen.getByText('Minorista')).toBeTruthy()
    expect(screen.getByText('Mayorista')).toBeTruthy()
    expect(screen.queryByText('B2C')).toBeNull()
  })

  it('conserva el desglose incompatible accesible sin apilar contribuciones falsas', () => {
    const onSelect = vi.fn()
    const { container } = render(<BarrasComparativas titulo="Ausente" onSelect={onSelect} items={[{ id: 'p', etiqueta: 'P', valor: 10, segmentos: [
      { id: 'a', etiqueta: 'A', valor: 20 }, { id: 'b', etiqueta: 'B', valor: null },
    ] }]} />)
    expect(container.querySelectorAll('.rv-bar-fill')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: /^P:/ }))
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ segmentos: [
      { id: 'a', etiqueta: 'A', valor: 20 }, { id: 'b', etiqueta: 'B', valor: null },
    ] }))
    expect(container.querySelector('.rv-detail').textContent).toContain('B: Sin dato')
  })

  it('apila segmentos B2C/B2B dentro del total y expone valores exactos accesibles', () => {
    const onSelect = vi.fn()
    render(<BarrasComparativas titulo="Ranking" unidad="S/" onSelect={onSelect} items={[{
      id: 'p1', etiqueta: 'Productor', valor: 1300,
      segmentos: [{ id: 'b2c', etiqueta: 'B2C', valor: 400 }, { id: 'b2b', etiqueta: 'B2B', valor: 900 }],
    }]} />)
    expect(screen.getByText('B2C')).toBeInTheDocument()
    expect(screen.getByText('B2B')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Productor/ }))
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'p1', segmentos: expect.any(Array) }))
    expect(screen.getByText(/Productor: S\/ 1,300/)).toBeInTheDocument()
  })

  it('conserva cada valor de composición, con proporciones válidas y sin aporte ficticio para cero/null', () => {
    const { container } = render(<Composicion titulo="Composición" partes={[
      { id: 'a', etiqueta: 'A', valor: 25 }, { id: 'b', etiqueta: 'B', valor: 75 },
      { id: 'c', etiqueta: 'C', valor: 0 }, { id: 'd', etiqueta: 'D', valor: null },
    ]} />)
    const slices = container.querySelectorAll('.rv-composition__strip span')
    expect([...slices].map((slice) => slice.style.flexGrow)).toEqual(['25', '75', '0', '0'])
    expect(screen.getByText('Sin dato')).toBeTruthy()
    expect(screen.getByText('25')).toBeTruthy()
    expect(screen.getByText('75')).toBeTruthy()
  })

  it('no normaliza valores negativos como partes de un total positivo', () => {
    const { container } = render(<Composicion titulo="Firmados" partes={[{ id: 'a', etiqueta: 'A', valor: -2 }, { id: 'b', etiqueta: 'B', valor: 3 }]} />)
    expect(container.querySelector('.rv-composition__strip')).toBeNull()
    expect(screen.getByText('-2')).toBeTruthy()
    expect(screen.getByText(/no son partes de un total/)).toBeTruthy()
  })

  it('preserva los tres decimales de stock y el formato monetario ya definido por la aplicación', () => {
    const { rerender } = render(<BarrasComparativas titulo="Stock" unidad="und" items={[{ id: 'a', etiqueta: 'A', valor: 1.125 }]} />)
    expect(screen.getByRole('button', { name: /1\.125/ })).toBeTruthy()
    rerender(<BarrasComparativas titulo="Importe" unidad="S/" items={[{ id: 'a', etiqueta: 'A', valor: 0 }]} />)
    expect(screen.getByRole('button', { name: /S\/\s*0\.00/ })).toBeTruthy()
  })

  it('incluye movimiento reducido e impresión de gráficos sin expandir una lista redundante de controles', () => {
    const css = readFileSync('src/components/reportes-visuales/ReportesVisuales.css', 'utf8')
    expect(css).toMatch(/prefers-reduced-motion:\s*reduce/)
    expect(css).toContain('@media print')
    expect(css).toMatch(/\.rv-help, \.rv-points \{ display: none;/)
    expect(css).toContain('flex-basis: 0')
    expect(css).not.toMatch(/flex:\s*var\(--rv-share/)
  })
})
