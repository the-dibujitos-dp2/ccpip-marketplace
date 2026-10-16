import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const CONTEOS = { CONFIRMADO: 2, EN_PREPARACION: 0, LISTO_PARA_RECOJO: 1, ENTREGADO: 0, CANCELADO: 0 }

const CONFIRMADOS = {
  conteos: CONTEOS,
  items: [
    {
      numero: 'MC-00148', estado: 'CONFIRMADO', modalidad: 'RECOJO', puntoRecojo: 'Centro Nacional Lima',
      confirmadoEn: '2026-11-15T15:00:00Z', actualizadoEn: '2026-11-15T15:00:00Z',
      subtotal: 64, comision: 6.4, neto: 57.6,
      lineas: [
        {
          productoId: 9, producto: 'Aceite de sacha inchi', imagen: '/api/imagenes/a?tam=min', cantidad: 2,
          precioUnitario: 32, subtotal: 64, comision: 6.4, neto: 57.6,
          lotes: [{ codigo: 'L-0231', cantidad: 2, fechaVencimiento: '2027-01-20' }],
        },
      ],
      historial: [{ estado: 'CONFIRMADO', registradoEn: '2026-11-15T15:00:00Z' }],
    },
    {
      numero: 'MC-00147', estado: 'CONFIRMADO', modalidad: 'RECOJO', puntoRecojo: 'Centro Regional Pucallpa',
      confirmadoEn: '2026-11-14T15:00:00Z', actualizadoEn: '2026-11-14T15:00:00Z',
      subtotal: 75, lineas: [
        { productoId: 10, producto: 'Bolso de chambira', cantidad: 1, precioUnitario: 45, subtotal: 45, lotes: [] },
        { productoId: 11, producto: 'Pulsera de huayruro', cantidad: 2, precioUnitario: 15, subtotal: 30, lotes: [] },
      ],
      historial: [{ estado: 'CONFIRMADO', registradoEn: '2026-11-14T15:00:00Z' }],
    },
  ],
}

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/pedidos?estado=CONFIRMADO': () => [200, copia(CONFIRMADOS)],
    'GET /api/productor/pedidos?estado=LISTO_PARA_RECOJO': () => [200, { conteos: CONTEOS, items: [] }],
    ...rutas,
  })
}

describe('CCPIP-66 Pedidos del productor (PW-07)', () => {
  it('se llega desde el menu y muestra los confirmados con el detalle del primero', async () => {
    productor({ 'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)], 'GET /api/productor/productos': () => [200, copia(DATOS.productosProductor)] })
    const user = userEvent.setup()
    abrirEn('/productor')
    await user.click(await screen.findByRole('link', { name: 'Pedidos' }))
    expect(window.location.pathname).toBe('/productor/pedidos')

    expect(await screen.findByRole('tab', { name: 'Confirmados (2)', selected: true })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Listos para recojo (1)' })).toBeInTheDocument()
    // EN_PREPARACION aun no se usa: su pestana aparece solo si hay pedidos
    expect(screen.queryByRole('tab', { name: /En preparación/ })).not.toBeInTheDocument()

    const lista = screen.getByRole('list', { name: 'Pedidos' })
    expect(within(lista).getByText('Aceite de sacha inchi × 2')).toBeInTheDocument()
    expect(within(lista).getByText('Bolso de chambira × 1 y 1 producto más')).toBeInTheDocument()
    expect(within(lista).getByText('Recojo · Pucallpa')).toBeInTheDocument()

    const detalle = screen.getByRole('complementary', { name: 'MC-00148' })
    expect(within(detalle).getByText('Lote L-0231 · 2 und · vence 20/01/2027')).toBeInTheDocument()
    expect(within(detalle).getByText('Comisión de la Cámara')).toBeInTheDocument()
    expect(within(detalle).getByText('− S/ 6.40')).toBeInTheDocument()
    expect(within(detalle).getByText('S/ 57.60')).toBeInTheDocument()
  })

  it('elegir otro pedido cambia el detalle; sin comision lo explica', async () => {
    productor()
    const user = userEvent.setup()
    abrirEn('/productor/pedidos')
    await user.click(await screen.findByRole('button', { name: 'Ver el pedido MC-00147' }))
    const detalle = screen.getByRole('complementary', { name: 'MC-00147' })
    expect(within(detalle).getByText('Pulsera de huayruro × 2')).toBeInTheDocument()
    expect(within(detalle).getByText('S/ 75.00')).toBeInTheDocument()
    expect(within(detalle).getByText(/no tiene comisión calculada/)).toBeInTheDocument()
    expect(within(detalle).queryByText('Recibirás')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ver el pedido MC-00147' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('cambia de pestana pidiendo ese estado y muestra el vacio', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/pedidos')
    await user.click(await screen.findByRole('tab', { name: 'Listos para recojo (1)' }))
    expect(await screen.findByText('Ningún pedido espera a su comprador')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/productor/pedidos?estado=LISTO_PARA_RECOJO')).toHaveLength(1)
    expect(screen.queryByRole('complementary', { name: /MC-/ })).not.toBeInTheDocument()
  })

  it('si el servidor falla lo avisa', async () => {
    productor({ 'GET /api/productor/pedidos?estado=CONFIRMADO': [500, { error: 'falla' }] })
    abrirEn('/productor/pedidos')
    expect(await screen.findByText('No pudimos cargar tus pedidos')).toBeInTheDocument()
  })

  it('en ingles', async () => {
    productor()
    await i18n.changeLanguage('en')
    abrirEn('/productor/pedidos')
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Confirmed (2)' })).toBeInTheDocument())
    expect(screen.getByText("You'll receive")).toBeInTheDocument()
  })
})
