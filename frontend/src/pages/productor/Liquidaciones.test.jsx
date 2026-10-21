import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const LIQUIDACION = {
  desde: '2026-11-01',
  hasta: '2026-11-18',
  productor: 'Asoc. Nii Joi',
  resumen: { pedidos: 2, bruto: 104, comision: 6.4, neto: 97.6, sinComision: 1 },
  pedidos: [
    {
      id: 148, numero: 'MC-00148', estado: 'CONFIRMADO', confirmadoEn: '2026-11-15T15:00:00Z', porcentaje: 10,
      bruto: 64, comision: 6.4, neto: 57.6,
      lineas: [{ productoId: 9, producto: 'Aceite de sacha inchi', cantidad: 2, precioUnitario: 32, bruto: 64, comision: 6.4, neto: 57.6 }],
    },
    {
      id: 120, numero: 'MC-00120', estado: 'ENTREGADO', confirmadoEn: '2026-11-02T15:00:00Z', porcentaje: null,
      bruto: 40, comision: 0, neto: 40,
      lineas: [{ productoId: 10, producto: 'Bolso de chambira', cantidad: 1, precioUnitario: 40, bruto: 40, comision: 0, neto: 40 }],
    },
  ],
}

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/liquidaciones': () => [200, copia(LIQUIDACION)],
    ...rutas,
  })
}

describe('CCPIP-92 Liquidaciones del productor por periodo', () => {
  it('se llega desde el menu y muestra bruto, comision y neto del mes en curso', async () => {
    productor({ 'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)], 'GET /api/productor/productos': () => [200, copia(DATOS.productosProductor)] })
    const user = userEvent.setup()
    abrirEn('/productor')
    await user.click(await screen.findByRole('link', { name: 'Liquidaciones' }))
    expect(window.location.pathname).toBe('/productor/liquidaciones')

    expect(await screen.findByRole('region', { name: 'Ventas brutas' })).toHaveTextContent('S/ 104.00')
    expect(screen.getByRole('region', { name: 'Ventas brutas' })).toHaveTextContent('2 pedidos')
    expect(screen.getByRole('region', { name: 'Comisión de la Cámara' })).toHaveTextContent('S/ 6.40')
    expect(screen.getByRole('region', { name: 'Neto a recibir' })).toHaveTextContent('S/ 97.60')
    expect(screen.getByLabelText('Desde')).toHaveValue('2026-11-01')
    expect(screen.getByLabelText('Hasta')).toHaveValue('2026-11-18')

    const tabla = screen.getByRole('table', { name: 'Pedidos del periodo' })
    const [, conComision, sinComision] = within(tabla).getAllByRole('row')
    expect(within(conComision).getByText('MC-00148')).toBeInTheDocument()
    expect(within(conComision).getByText('Aceite de sacha inchi × 2 a S/ 32.00')).toBeInTheDocument()
    expect(within(conComision).getByText('S/ 6.40 (10 %)')).toBeInTheDocument()
    expect(within(conComision).getByText('S/ 57.60')).toBeInTheDocument()
    expect(within(sinComision).getByText('Sin comisión')).toBeInTheDocument()
    expect(screen.getByText('1 pedido se confirmó sin comisión configurada')).toBeInTheDocument()
  })

  it('cambiar las fechas consulta ese periodo', async () => {
    const fetchMock = productor({
      'GET /api/productor/liquidaciones?desde=2026-10-01&hasta=2026-11-18': () => [200, { ...copia(LIQUIDACION), desde: '2026-10-01' }],
    })
    abrirEn('/productor/liquidaciones')
    await screen.findByDisplayValue('2026-11-01')
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '2026-10-01' } })
    await screen.findByDisplayValue('2026-10-01')
    expect(llamadas(fetchMock, 'GET /api/productor/liquidaciones?desde=2026-10-01&hasta=2026-11-18')).not.toHaveLength(0)
  })

  it('sin ventas en el periodo lo dice', async () => {
    productor({
      'GET /api/productor/liquidaciones': () => [200, { ...copia(LIQUIDACION), resumen: { pedidos: 0, bruto: 0, comision: 0, neto: 0, sinComision: 0 }, pedidos: [] }],
    })
    abrirEn('/productor/liquidaciones')
    expect(await screen.findByText('No tienes ventas confirmadas en estas fechas')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('un periodo invalido muestra el mensaje del servidor', async () => {
    productor({
      'GET /api/productor/liquidaciones': () => [422, { code: 'RANGO_MUY_LARGO', error: 'Consulta como máximo un año a la vez.' }],
    })
    abrirEn('/productor/liquidaciones')
    expect(await screen.findByText('No pudimos cargar tu liquidación')).toBeInTheDocument()
  })

  it('CCPIP-326: descarga en PDF el periodo consultado', async () => {
    productor()
    abrirEn('/productor/liquidaciones')
    const pdf = await screen.findByRole('link', { name: 'Descargar PDF' })
    expect(pdf).toHaveAttribute('href', '/api/productor/liquidaciones/pdf?desde=2026-11-01&hasta=2026-11-18')
    expect(pdf).toHaveAttribute('download')
  })
})
