import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import i18n from '../../../i18n/index.js'
import Ventas from './Ventas.jsx'
import Inventario from './Inventario.jsx'

const base = { agrupacion: 'MES', totales: {}, porProducto: [] }
const ventas = { ...base, totales: { total: 160, b2c: 100, b2b: 60, pedidos: 2, pedidosAtendidos: 1, pedidosPorEntregar: 1, unidades: 8 }, periodos: [
  { inicio: '2026-01-01', b2c: 100, b2b: 0, total: 100, acumulado: 100, pedidos: 1, pedidosAtendidos: 1, pedidosPorEntregar: 0, unidades: 5 },
  { inicio: '2026-02-01', b2c: 0, b2b: 60, total: 60, acumulado: 160, pedidos: 1, pedidosAtendidos: 0, pedidosPorEntregar: 1, unidades: 3 },
] }
const inventario = { ...base, totales: { saldoInicial: 20, entradas: 92, salidas: 15, saldoFinal: 97, porEntregar: 3, existenciaFisica: 100 }, periodos: [
  { inicio: '2026-01-01', saldoInicial: 20, entradas: 62, salidas: 5, saldoFinal: 77 },
  { inicio: '2026-02-01', saldoInicial: 77, entradas: 30, salidas: 10, saldoFinal: 97 },
] }
const puntos = () => within(screen.getByLabelText('Puntos de la serie'))

afterEach(() => i18n.changeLanguage('es'))

describe('interacciones locales de reportes de acopio', () => {
  it('separa desglose, total, acumulado y conteos sin mezclar unidades', () => {
    render(<Ventas datos={ventas} />)
    expect(screen.getByRole('button', { name: 'Desglose B2C/B2B' })).toHaveAttribute('aria-pressed', 'true')
    expect(puntos().getAllByRole('button')).toHaveLength(4)
    fireEvent.click(screen.getByRole('button', { name: 'Acumulado', exact: true }))
    expect(puntos().getAllByRole('button')).toHaveLength(2)
    expect(puntos().getByRole('button', { name: /160/ })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Pedidos', exact: true }))
    expect(puntos().getAllByRole('button')).toHaveLength(6)
    expect(puntos().queryByRole('button', { name: /S\// })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Atendidos', exact: true }))
    expect(puntos().getAllByRole('button')).toHaveLength(2)
    expect(puntos().getByRole('button', { name: /2026-02-01.*0/ })).toBeTruthy()
  })

  it('presenta los cuatro valores reales del período seleccionado, no el total del rango', () => {
    render(<Inventario datos={inventario} />)
    fireEvent.click(puntos().getByRole('button', { name: /2026-02-01.*77/ }))
    const bridge = within(screen.getByRole('region', { name: 'Detalle del período' }))
    for (const value of ['77 und', '30 und', '10 und', '97 und']) expect(bridge.getByText(value)).toBeTruthy()
    expect(bridge.queryByText('100 und')).toBeNull()
    expect(bridge.queryByText(/Por entregar/)).toBeNull()
  })

  it('deriva el puente del dataset vigente y elimina la selección al salir del rango', () => {
    const { rerender } = render(<Inventario datos={inventario} />)
    fireEvent.click(puntos().getByRole('button', { name: /2026-02-01.*77/ }))
    const updated = { ...inventario, periodos: [{ inicio: '2026-02-01', saldoInicial: 8, entradas: 2, salidas: 1, saldoFinal: 9 }] }
    rerender(<Inventario datos={updated} />)
    let bridge = within(screen.getByRole('region', { name: 'Detalle del período' }))
    expect(bridge.getByText('8 und')).toBeTruthy()
    expect(bridge.queryByText('77 und')).toBeNull()
    rerender(<Inventario datos={{ ...updated, periodos: [{ ...updated.periodos[0], inicio: '2026-03-01' }] }} />)
    expect(screen.queryByRole('region', { name: 'Detalle del período' })).toBeNull()
    expect(screen.queryByText(/2026-02-01/)).toBeNull()
  })

  it('conserva null como ausencia de importe sin reemplazarlo por cero', () => {
    render(<Ventas datos={{ ...ventas, periodos: [{ ...ventas.periodos[0], b2c: null }] }} />)
    expect(puntos().getByText(/Sin dato/)).toBeTruthy()
    expect(puntos().getAllByRole('button')).toHaveLength(2)
  })

  it('traduce títulos y selectores al inglés sin cambiar el formato PEN', async () => {
    await i18n.changeLanguage('en')
    const { unmount } = render(<Ventas datos={ventas} />)
    expect(screen.getByRole('heading', { name: 'Sales trend' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'B2C/B2B breakdown' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Orders', exact: true }))
    expect(screen.getByRole('button', { name: 'Fulfilled', exact: true })).toBeTruthy()
    unmount()
    render(<Inventario datos={inventario} />)
    fireEvent.click(within(screen.getByLabelText('Data points')).getByRole('button', { name: /2026-02-01.*77/ }))
    expect(screen.getByRole('region', { name: 'Period detail' })).toBeTruthy()
  })
})
