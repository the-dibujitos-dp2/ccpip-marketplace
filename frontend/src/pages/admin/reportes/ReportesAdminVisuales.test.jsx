import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import i18n from '../../../i18n/index.js'
import { GraficaAdmin } from './GraficaAdmin.jsx'

const finanzas = {
  clave: 'ventas', metrica: 'VENTAS', desde: '2026-01-01', hasta: '2026-03-18', agrupacion: 'MES',
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', b2c: 500, b2b: 0, total: 500, acumulado: 500 },
    { inicio: '2026-02-01', fin: '2026-02-28', b2c: 0, b2b: 0, total: 0, acumulado: 500 },
  ],
}
const indicadores = { clave: 'indicadores', agrupacion: 'MES', periodos: [
  { inicio: '2026-01-01', ventas: 500, pedidos: 1, productoresConVentas: 1, productosNuevos: 2, compradores: 1 },
  { inicio: '2026-02-01', ventas: 0, pedidos: 0, productoresConVentas: 0, productosNuevos: 0, compradores: 0 },
] }
const productores = { clave: 'productores', productores: [
  { productorId: 7, productor: 'Tienda Asháninka', pedidos: 3, unidades: 40, b2c: 400, b2b: 900, total: 1300 },
  { productorId: 9, productor: 'Café Yanesha', pedidos: 2, unidades: 12.5, b2c: 900, b2b: 0, total: 900 },
] }
const puntos = { clave: 'puntos', puntos: [
  { puntoId: 1, punto: 'Satipo', pedidos: 5, b2c: 1300, b2b: 900, total: 2200 },
  { puntoId: 2, punto: 'Oxapampa', pedidos: 0, b2c: 0, b2b: 0, total: 0 },
] }
const calificaciones = { clave: 'calificaciones', minimo: 3,
  resumen: { promedio: 4.2, resenas: 10, distribucion: [
    { estrellas: 5, cantidad: 5 }, { estrellas: 4, cantidad: 3 }, { estrellas: 3, cantidad: 1 },
    { estrellas: 2, cantidad: 0 }, { estrellas: 1, cantidad: 1 },
  ] },
  productos: [
    { productoId: 11, producto: 'Café de altura', productor: 'Café Yanesha', promedio: 4.8, resenas: 6 },
    { productoId: 13, producto: 'Miel', productor: 'Tienda Asháninka', promedio: null, resenas: 1 },
  ],
}
const origenDestino = { clave: 'origen-destino',
  porPunto: [{ nombre: 'Satipo', detalle: 'Junín', pedidos: 4, total: 1900, participacion: 86.4 }],
  porProductor: [{ nombre: 'Tienda Asháninka', detalle: 'Asháninka', pedidos: 3, total: 1300, participacion: 59.1 }],
  porDestino: [{ departamento: 'Lima', pedidos: 3, envios: 3, recojos: 0, total: 1500 }, { departamento: null, pedidos: 1, total: 300 }],
  rutas: [{ punto: 'Satipo', destino: 'Lima', pedidos: 3, total: 1500 }, { punto: 'Oxapampa', destino: null, pedidos: 1, total: 300 }],
}

afterEach(() => i18n.changeLanguage('es'))

describe('gráficas administrativas con respuestas reales de pruebas', () => {
  it('finanzas mapea B2C/B2B/total por periodo, sin segundo selector acumulado', () => {
    render(<GraficaAdmin tipo="finanzas" datos={finanzas} titulo="Ventas" />)
    expect(screen.queryByRole('button', { name: 'Acumulado' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'B2C' }))
    expect(within(screen.getByRole('group', { name: 'Ventas — Gráfico interactivo' })).getByRole('button', { name: /S\/ 500/ })).toBeInTheDocument()
  })
  it('indicadores mapea productoresConVentas y representa observaciones', () => {
    render(<GraficaAdmin tipo="indicadores" datos={indicadores} titulo="Por periodo" />)
    fireEvent.click(screen.getByRole('button', { name: 'Productores con ventas' }))
    const grafica = screen.getByRole('group', { name: 'Por periodo — Gráfico interactivo' })
    expect(within(grafica).getByRole('button', { name: /1$/ })).toBeInTheDocument()
    expect(grafica).toBeInTheDocument()
  })
  it.each([['productores', productores, 'Tienda Asháninka'], ['puntos', puntos, 'Satipo']])(
    '%s representa ventas reales en el ranking', (tipo, datos, etiqueta) => {
      render(<GraficaAdmin tipo={tipo} datos={datos} titulo="Ranking" />)
      expect(screen.getByRole('button', { name: new RegExp(etiqueta) })).toBeInTheDocument()
      expect(screen.queryByText(/Sin datos para mostrar/)).not.toBeInTheDocument()
    },
  )
  it('calificaciones presenta distribución y ranking por promedio suprimiendo null', () => {
    render(<GraficaAdmin tipo="calificaciones" datos={calificaciones} titulo="Calificaciones" />)
    expect(screen.getByRole('button', { name: /5 estrellas/ })).toBeInTheDocument()
    const ranking = screen.getByRole('region', { name: 'Calificaciones por producto' })
    expect(within(ranking).getByRole('button', { name: /Café de altura/ })).toBeInTheDocument()
    expect(within(ranking).queryByRole('button', { name: /Miel/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })
  it('origen-destino usa campos reales de origen/destino/ruta y sin labels undefined', () => {
    render(<GraficaAdmin tipo="origen-destino" datos={origenDestino} titulo="Destinos" />)
    expect(screen.getByText('Lima')).toBeInTheDocument()
    for (const [dimension, nombres] of [
      ['Por punto de acopio', ['Satipo']],
      ['Por productor', ['Tienda Asháninka']],
      ['Rutas', ['Satipo — Lima', 'Oxapampa — Sin dirección registrada']],
    ]) {
      fireEvent.click(screen.getByRole('button', { name: dimension, exact: true }))
      for (const nombre of nombres) expect(screen.getByText(nombre)).toBeInTheDocument()
    }
    expect(screen.queryByText('undefined')).not.toBeInTheDocument()
    expect(screen.queryByText(/Sin datos para mostrar/)).not.toBeInTheDocument()
  })
  it('usa labels EN sin suffix hardcoded de gráfico', async () => {
    await i18n.changeLanguage('en')
    render(<GraficaAdmin tipo="finanzas" datos={finanzas} titulo="Sales over time" />)
    expect(screen.getByRole('button', { name: 'Total' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Sales over time' })).toBeInTheDocument()
    expect(screen.queryByText(/— gráfico/)).not.toBeInTheDocument()
  })
  it('dataset/rango nuevo reinicia la serie seleccionada y elimina datos viejos', () => {
    const { rerender } = render(<GraficaAdmin tipo="indicadores" datos={indicadores} titulo="Por periodo" />)
    fireEvent.click(screen.getByRole('button', { name: 'Pedidos' }))
    const nuevos = { ...indicadores, desde: '2026-03-01', hasta: '2026-03-31', periodos: [
      { inicio: '2026-03-01', ventas: 1700, pedidos: 4, productoresConVentas: 2, productosNuevos: 4, compradores: 3 },
    ] }
    rerender(<GraficaAdmin tipo="indicadores" datos={nuevos} titulo="Por periodo" />)
    expect(screen.getByRole('button', { name: 'Ventas' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(screen.getByRole('group', { name: 'Por periodo — Gráfico interactivo' })).getByRole('button', { name: /S\/ 1,700/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /S\/ 500/ })).not.toBeInTheDocument()
  })
})
