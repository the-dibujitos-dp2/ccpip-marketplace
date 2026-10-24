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

const PRODUCCION = {
  desde: '2026-01-01',
  hasta: '2026-03-18',
  agrupacion: 'MES',
  producto: null,
  totales: { unidades: 90, lotes: 2 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', unidades: 60, lotes: 1, acumulado: 60 },
    { inicio: '2026-02-01', fin: '2026-02-28', unidades: 0, lotes: 0, acumulado: 60 },
    { inicio: '2026-03-01', fin: '2026-03-18', unidades: 30, lotes: 1, acumulado: 90 },
  ],
  porProducto: [
    { productoId: 9, producto: 'Aceite de sacha inchi 250 ml', unidades: 60, lotes: 1 },
    { productoId: 10, producto: 'Harina de aguaje 500 g', unidades: 30, lotes: 1 },
  ],
  productos: PRODUCTOS,
}

const INVENTARIO = {
  desde: '2026-01-01',
  hasta: '2026-03-18',
  agrupacion: 'MES',
  producto: null,
  totales: { saldoInicial: 20, entradas: 90, vendidasB2c: 5, vendidasB2b: 4, merma: 3, otrasSalidas: 2, saldoFinal: 96 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', saldoInicial: 20, entradas: 60, vendidasB2c: 5, vendidasB2b: 0, merma: 0, otrasSalidas: 0, saldoFinal: 75 },
    { inicio: '2026-02-01', fin: '2026-02-28', saldoInicial: 75, entradas: 0, vendidasB2c: 0, vendidasB2b: 0, merma: 0, otrasSalidas: 0, saldoFinal: 75 },
    { inicio: '2026-03-01', fin: '2026-03-18', saldoInicial: 75, entradas: 30, vendidasB2c: 0, vendidasB2b: 4, merma: 3, otrasSalidas: 2, saldoFinal: 96 },
  ],
  porProducto: [
    { productoId: 9, producto: 'Aceite de sacha inchi 250 ml', saldoInicial: 20, entradas: 60, vendidas: 5, merma: 3, otrasSalidas: 0, saldoFinal: 72 },
    { productoId: 10, producto: 'Harina de aguaje 500 g', saldoInicial: 0, entradas: 30, vendidas: 4, merma: 0, otrasSalidas: 2, saldoFinal: 24 },
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

  it('CCPIP-333: la pestana Produccion consulta el mismo rango y no ofrece la modalidad', async () => {
    const fetchMock = productor({
      'GET /api/productor/reportes/produccion?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES': () => [200, copia(PRODUCCION)],
    })
    const user = userEvent.setup()
    abrirEn('/productor/reportes')
    await screen.findByRole('table', { name: 'Ventas por período' })
    fireEvent.change(screen.getByLabelText('Modalidad'), { target: { value: 'MAYORISTA' } })
    await user.click(screen.getByRole('tab', { name: 'Producción' }))

    expect(await screen.findByRole('region', { name: 'Unidades producidas' })).toHaveTextContent('90 und')
    expect(screen.getByRole('region', { name: 'Lotes nuevos' })).toHaveTextContent('2')
    expect(screen.getByRole('tab', { name: 'Producción', selected: true })).toBeInTheDocument()
    expect(screen.queryByLabelText('Modalidad')).not.toBeInTheDocument()
    // La modalidad elegida en Ventas no viaja a Produccion
    expect(llamadas(fetchMock, 'GET /api/productor/reportes/produccion?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES')).toHaveLength(1)

    const periodos = within(screen.getByRole('table', { name: 'Producción por período' })).getAllByRole('row')
    expect(within(periodos[3]).getByText('30')).toBeInTheDocument()
    expect(within(periodos[3]).getByText('90')).toBeInTheDocument()
    const productos = within(screen.getByRole('table', { name: 'Producción por producto' })).getAllByRole('row')
    expect(within(productos[1]).getByText('Aceite de sacha inchi 250 ml')).toBeInTheDocument()
    expect(screen.getByText(/Las transferencias no cuentan/)).toBeInTheDocument()
  })

  it('CCPIP-334: la pestana Inventario explica cada saldo con sus movimientos', async () => {
    productor({
      'GET /api/productor/reportes/inventario?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES': () => [200, copia(INVENTARIO)],
    })
    const user = userEvent.setup()
    abrirEn('/productor/reportes')
    await screen.findByRole('table', { name: 'Ventas por período' })
    await user.click(screen.getByRole('tab', { name: 'Inventario' }))

    expect(await screen.findByRole('region', { name: 'Saldo inicial' })).toHaveTextContent('20 und')
    expect(screen.getByRole('region', { name: 'Entradas' })).toHaveTextContent('90 und')
    expect(screen.getByRole('region', { name: 'Salidas' })).toHaveTextContent('14 und')
    expect(screen.getByRole('region', { name: 'Salidas' })).toHaveTextContent('9 vendidas · 3 de merma')
    expect(screen.getByRole('region', { name: 'Saldo final' })).toHaveTextContent('96 und')
    expect(screen.queryByLabelText('Modalidad')).not.toBeInTheDocument()

    const periodos = within(screen.getByRole('table', { name: 'Inventario por período' })).getAllByRole('row')
    expect(within(periodos[0]).getByText('Vendidas B2B')).toBeInTheDocument()
    const marzo = within(periodos[3]).getAllByRole('cell').map((c) => c.textContent)
    expect(marzo).toEqual(['Marzo de 2026', '75', '30', '0', '4', '3', '2', '96'])
    const productos = within(screen.getByRole('table', { name: 'Inventario por producto' })).getAllByRole('row')
    expect(within(productos[2]).getAllByRole('cell').map((c) => c.textContent))
      .toEqual(['Harina de aguaje 500 g', '0', '30', '4', '0', '2', '24'])
    expect(screen.getByText(/Las transferencias no cambian el total/)).toBeInTheDocument()
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
