import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const VENTAS = {
  metrica: 'VENTAS',
  desde: '2026-01-01',
  hasta: '2026-03-18',
  agrupacion: 'MES',
  totales: { b2c: 1300, b2b: 900, total: 2200 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', b2c: 500, b2b: 0, total: 500, acumulado: 500 },
    { inicio: '2026-02-01', fin: '2026-02-28', b2c: 0, b2b: 0, total: 0, acumulado: 500 },
    { inicio: '2026-03-01', fin: '2026-03-18', b2c: 800, b2b: 900, total: 1700, acumulado: 2200 },
  ],
}

const SEMANAS = {
  ...VENTAS,
  desde: '2026-03-04',
  hasta: '2026-03-18',
  agrupacion: 'SEMANA',
  periodos: [
    { inicio: '2026-03-04', fin: '2026-03-08', b2c: 0, b2b: 0, total: 0, acumulado: 0 },
    { inicio: '2026-03-09', fin: '2026-03-15', b2c: 800, b2b: 900, total: 1700, acumulado: 1700 },
    { inicio: '2026-03-16', fin: '2026-03-18', b2c: 0, b2b: 0, total: 0, acumulado: 1700 },
  ],
}

function comoSuper(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUPER],
    'GET /api/admin/reportes/ventas': () => [200, copia(VENTAS)],
    ...rutas,
  })
}

describe('CCPIP-345 Ventas acumuladas de la plataforma (SA-05)', () => {
  it('se llega desde el menu y muestra el total con su desglose B2C y B2B', async () => {
    comoSuper({ 'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)] })
    const user = userEvent.setup()
    abrirEn('/admin')
    await user.click(await screen.findByRole('link', { name: 'Reportes' }))
    expect(window.location.pathname).toBe('/admin/reportes')

    expect(await screen.findByRole('region', { name: 'Ventas acumuladas' })).toHaveTextContent('S/ 2,200.00')
    expect(screen.getByRole('region', { name: 'B2C (minorista)' })).toHaveTextContent('S/ 1,300.00')
    expect(screen.getByRole('region', { name: 'B2B (mayorista)' })).toHaveTextContent('S/ 900.00')
    expect(screen.getByLabelText('Agrupar por')).toHaveValue('MES')

    const tabla = screen.getByRole('table', { name: 'Ventas por periodo' })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(4)
    expect(within(filas[1]).getByText('Enero de 2026')).toBeInTheDocument()
    // Un mes sin ventas aparece en cero y conserva el acumulado
    expect(within(filas[2]).getAllByText('S/ 500.00')).toHaveLength(1)
    expect(within(filas[3]).getByText('S/ 1,700.00')).toBeInTheDocument()
    expect(within(filas[3]).getByText('S/ 2,200.00')).toBeInTheDocument()
  })

  it('agrupar por semana consulta el rango y nombra cada semana', async () => {
    const fetchMock = comoSuper({
      'GET /api/admin/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=SEMANA': () => [200, copia(SEMANAS)],
    })
    abrirEn('/admin/reportes')
    await screen.findByRole('table', { name: 'Ventas por periodo' })
    fireEvent.change(screen.getByLabelText('Agrupar por'), { target: { value: 'SEMANA' } })
    expect(await screen.findByText('9 mar 2026 al 15 mar 2026')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/admin/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=SEMANA')).toHaveLength(1)
  })

  it('un rango invalido muestra el error', async () => {
    comoSuper({ 'GET /api/admin/reportes/ventas': () => [422, { code: 'RANGO_MUY_LARGO', error: 'Consulta como máximo un año a la vez.' }] })
    abrirEn('/admin/reportes')
    expect(await screen.findByText('No pudimos cargar el reporte')).toBeInTheDocument()
  })

  it('el sub administrador no ve los reportes', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUB], 'GET /api/admin/autorizaciones': () => [200, copia(DATOS.autorizaciones ?? { contadores: {}, solicitudes: [] })] })
    abrirEn('/admin/reportes')
    await screen.findByRole('navigation', { name: 'Sub administrador' })
    expect(window.location.pathname).toBe('/admin/autorizaciones')
    expect(screen.queryByRole('link', { name: 'Reportes' })).not.toBeInTheDocument()
  })

  it('CCPIP-346: la pestana Comisiones consulta las comisiones del mismo rango', async () => {
    const fetchMock = comoSuper({
      'GET /api/admin/reportes/comisiones?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES': () => [200, {
        ...copia(VENTAS), metrica: 'COMISIONES', totales: { b2c: 130, b2b: 90, total: 220 },
      }],
    })
    const user = userEvent.setup()
    abrirEn('/admin/reportes')
    await screen.findByRole('table', { name: 'Ventas por periodo' })
    await user.click(screen.getByRole('tab', { name: 'Comisiones' }))
    expect(await screen.findByRole('region', { name: 'Comisiones acumuladas' })).toHaveTextContent('S/ 220.00')
    expect(screen.getByRole('tab', { name: 'Comisiones', selected: true })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Comisiones por periodo' })).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/admin/reportes/comisiones?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES')).toHaveLength(1)
  })

  it('CCPIP-93: la pestana Ingresos muestra lo cobrado del mismo rango', async () => {
    comoSuper({
      'GET /api/admin/reportes/ingresos?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES': () => [200, {
        ...copia(VENTAS), metrica: 'INGRESOS', totales: { b2c: 1250, b2b: 900, total: 2150 },
      }],
    })
    const user = userEvent.setup()
    abrirEn('/admin/reportes')
    await screen.findByRole('table', { name: 'Ventas por periodo' })
    await user.click(screen.getByRole('tab', { name: 'Ingresos' }))
    expect(await screen.findByRole('region', { name: 'Ingresos acumulados' })).toHaveTextContent('S/ 2,150.00')
    expect(screen.getByRole('table', { name: 'Ingresos por periodo' })).toBeInTheDocument()
    expect(screen.getByText(/pagos aprobados/)).toBeInTheDocument()
  })
})
