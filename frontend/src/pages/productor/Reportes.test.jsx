import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const PRODUCTOS = [
  { id: 9, nombre: 'Aceite de sacha inchi 250 ml' },
  { id: 10, nombre: 'Harina de aguaje 500 g' },
]

const VENTAS = {
  desde: '2026-01-01',
  hasta: '2026-03-18',
  agrupacion: 'MES',
  producto: null,
  modalidad: null,
  totales: { unidades: 23, b2c: 180, b2b: 140, total: 320 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', unidades: 14, b2c: 80, b2b: 100, total: 180, acumulado: 180 },
    { inicio: '2026-02-01', fin: '2026-02-28', unidades: 0, b2c: 0, b2b: 0, total: 0, acumulado: 180 },
    { inicio: '2026-03-01', fin: '2026-03-18', unidades: 9, b2c: 100, b2b: 40, total: 140, acumulado: 320 },
  ],
  porProducto: [
    { productoId: 10, producto: 'Harina de aguaje 500 g', unidades: 14, b2c: 0, b2b: 140, total: 140 },
    { productoId: 9, producto: 'Aceite de sacha inchi 250 ml', unidades: 9, b2c: 180, b2b: 0, total: 180 },
  ],
  productos: PRODUCTOS,
}

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/reportes/ventas': () => [200, copia(VENTAS)],
    ...rutas,
  })
}

describe('Reportes del productor', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('CCPIP-114: se llega desde el menu y muestra las ventas del año con su desglose B2C y B2B', async () => {
    productor({ 'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)], 'GET /api/productor/productos': () => [200, copia(DATOS.productosProductor)] })
    const user = userEvent.setup()
    abrirEn('/productor')
    await user.click(await screen.findByRole('link', { name: 'Reportes' }))
    expect(window.location.pathname).toBe('/productor/reportes')

    expect(await screen.findByRole('region', { name: 'Total vendido' })).toHaveTextContent('S/ 320.00')
    expect(screen.getByRole('region', { name: 'Total vendido' })).toHaveTextContent('23 unidades vendidas')
    expect(screen.getByRole('region', { name: 'B2C (minorista)' })).toHaveTextContent('S/ 180.00')
    expect(screen.getByRole('region', { name: 'B2B (mayorista)' })).toHaveTextContent('S/ 140.00')
    expect(screen.getByLabelText('Desde')).toHaveValue('2026-01-01')
    expect(screen.getByLabelText('Hasta')).toHaveValue('2026-03-18')
    expect(screen.getByLabelText('Agrupar por')).toHaveValue('MES')
    expect(screen.getByLabelText('Producto')).toHaveValue('')

    const periodos = within(screen.getByRole('table', { name: 'Ventas por período' })).getAllByRole('row')
    expect(periodos).toHaveLength(4)
    expect(within(periodos[1]).getByText('Enero de 2026')).toBeInTheDocument()
    // Un mes sin ventas aparece en cero y conserva el acumulado
    expect(within(periodos[2]).getAllByText('S/ 0.00')).toHaveLength(3)
    expect(within(periodos[2]).getByText('S/ 180.00')).toBeInTheDocument()
    expect(within(periodos[3]).getByText('S/ 320.00')).toBeInTheDocument()

    const productos = within(screen.getByRole('table', { name: 'Ventas por producto' })).getAllByRole('row')
    expect(within(productos[1]).getByText('Harina de aguaje 500 g')).toBeInTheDocument()
    expect(within(productos[1]).getByText('14')).toBeInTheDocument()
  })

  it('CCPIP-114: filtra por producto, modalidad y agrupacion con el rango que se ve', async () => {
    const fetchMock = productor({
      'GET /api/productor/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES&producto=10': () => [200, {
        ...copia(VENTAS), producto: 10, totales: { unidades: 14, b2c: 0, b2b: 140, total: 140 }, porProducto: [copia(VENTAS.porProducto[0])],
      }],
      'GET /api/productor/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES&producto=10&modalidad=MAYORISTA': () => [200, {
        ...copia(VENTAS), producto: 10, modalidad: 'MAYORISTA', totales: { unidades: 14, b2c: 0, b2b: 140, total: 140 },
      }],
      'GET /api/productor/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=SEMANA&producto=10&modalidad=MAYORISTA': () => [200, {
        ...copia(VENTAS), producto: 10, modalidad: 'MAYORISTA', agrupacion: 'SEMANA',
        periodos: [{ inicio: '2026-03-09', fin: '2026-03-15', unidades: 4, b2c: 0, b2b: 40, total: 40, acumulado: 40 }],
      }],
    })
    abrirEn('/productor/reportes')
    await screen.findByRole('table', { name: 'Ventas por período' })

    fireEvent.change(screen.getByLabelText('Producto'), { target: { value: '10' } })
    expect(await screen.findByRole('region', { name: 'Total vendido' })).toHaveTextContent('S/ 140.00')
    expect(screen.getByLabelText('Producto')).toHaveValue('10')

    fireEvent.change(screen.getByLabelText('Modalidad'), { target: { value: 'MAYORISTA' } })
    fireEvent.change(screen.getByLabelText('Agrupar por'), { target: { value: 'SEMANA' } })
    expect(await screen.findByText('9 mar 2026 al 15 mar 2026')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/productor/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=SEMANA&producto=10&modalidad=MAYORISTA')).toHaveLength(1)
  })

  it('CCPIP-114: sin ventas lo dice y un rango invalido muestra el error', async () => {
    productor({
      'GET /api/productor/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=DIA': () => [422, { code: 'RANGO_MUY_LARGO', error: 'Consulta como máximo un año a la vez.' }],
      'GET /api/productor/reportes/ventas': () => [200, { ...copia(VENTAS), porProducto: [] }],
    })
    abrirEn('/productor/reportes')
    expect(await screen.findByText('No hubo ventas en este período')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Agrupar por'), { target: { value: 'DIA' } })
    expect(await screen.findByText('No pudimos cargar el reporte')).toBeInTheDocument()
    expect(screen.getByText('Consulta como máximo un año a la vez.')).toBeInTheDocument()
  })

  it('sale en ingles', async () => {
    await i18n.changeLanguage('en')
    productor()
    abrirEn('/productor/reportes')
    expect(await screen.findByRole('region', { name: 'Total sold' })).toHaveTextContent('S/ 320.00')
    expect(screen.getByRole('table', { name: 'Sales by period' })).toBeInTheDocument()
    expect(screen.getByLabelText('Purchase mode')).toBeInTheDocument()
  })
})
