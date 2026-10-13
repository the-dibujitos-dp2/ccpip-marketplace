import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_ACOPIO, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/acopio/movimientos'

/** Una venta confirmada como la devuelve el servidor: con lote y pedido, sin datos del comprador. */
function conVenta() {
  const datos = copia(DATOS.movimientos)
  datos.items.unshift({
    ...datos.items[0],
    id: 50,
    registradoEn: '2026-11-16T14:12:00Z',
    tipo: 'SALIDA_VENTA',
    lote: 'L-0010',
    cantidad: 2.0,
    pedido: 'MC-00148',
    observacion: 'Venta del pedido MC-00148',
  })
  datos.total += 1
  return datos
}

const comoAcopio = (rutas = {}) => ({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], ...rutas })

describe('A-03 Movimientos del punto de acopio (CCPIP-158)', () => {
  it('muestra cada movimiento con su tipo, producto, lote, pedido y cantidad con signo', async () => {
    servidor(comoAcopio({ [`GET ${RUTA}?pagina=0`]: [200, conVenta()] }))
    abrirEn('/acopio/movimientos')

    expect(await screen.findByRole('heading', { level: 1, name: 'Movimientos' })).toBeInTheDocument()
    // El nombre del punto llega con los movimientos
    expect(await screen.findByText('Centro Nacional Lima / Movimientos')).toBeInTheDocument()
    const filas = within(screen.getByRole('list', { name: 'Movimientos del punto' })).getAllByRole('listitem')
    expect(filas).toHaveLength(4)

    // La venta: salida con su lote y numero de pedido, hora de Lima, y sin el nombre del comprador
    expect(within(filas[0]).getByText('16/11 09:12')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Salida')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Cafe tostado en grano 500 g · L-0010 · MC-00148')).toBeInTheDocument()
    expect(within(filas[0]).getByText('−2')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Compra en línea')).toBeInTheDocument()

    // La carga inicial sembrada: entrada sin lote
    expect(within(filas[1]).getByText('Entrada')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Cafe tostado en grano 500 g')).toBeInTheDocument()
    expect(within(filas[1]).getByText('+100')).toBeInTheDocument()
  })

  it('cada pestaña pide solo ese grupo de movimientos', async () => {
    const soloVenta = conVenta()
    soloVenta.items = soloVenta.items.slice(0, 1)
    const fetchMock = servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0`]: [200, conVenta()],
      [`GET ${RUTA}?pagina=0&tipo=SALIDAS`]: [200, soloVenta],
      [`GET ${RUTA}?pagina=0&tipo=AJUSTES`]: [200, { ...soloVenta, items: [], total: 0, totalPaginas: 0 }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')
    await screen.findByText('Cafe tostado en grano 500 g · L-0010 · MC-00148')

    await user.click(screen.getByRole('tab', { name: 'Salidas' }))
    expect(screen.getByRole('tab', { name: 'Salidas' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(screen.queryByText('+100')).not.toBeInTheDocument())
    expect(llamadas(fetchMock, `GET ${RUTA}?pagina=0&tipo=SALIDAS`)).toHaveLength(1)

    await user.click(screen.getByRole('tab', { name: 'Ajustes' }))
    expect(await screen.findByText('Aún no hay movimientos')).toBeInTheDocument()
  })

  it('busca el movimiento que genero un pedido por su numero', async () => {
    const delPedido = conVenta()
    delPedido.items = delPedido.items.slice(0, 1)
    delPedido.total = 1
    servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0`]: [200, copia(DATOS.movimientos)],
      [`GET ${RUTA}?pagina=0&pedido=mc-00148`]: [200, delPedido],
      [`GET ${RUTA}?pagina=0&pedido=MC-99999`]: [200, { ...delPedido, items: [], total: 0, totalPaginas: 0 }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')
    await screen.findByText('Camu camu en polvo 150 g')

    await user.type(screen.getByLabelText('Número de pedido'), ' mc-00148 {Enter}')
    expect(await screen.findByText('Movimientos del pedido MC-00148')).toBeInTheDocument()
    expect(screen.getByText('Cafe tostado en grano 500 g · L-0010 · MC-00148')).toBeInTheDocument()
    expect(screen.queryByText('Camu camu en polvo 150 g')).not.toBeInTheDocument()

    await user.clear(screen.getByLabelText('Número de pedido'))
    await user.type(screen.getByLabelText('Número de pedido'), 'MC-99999{Enter}')
    expect(await screen.findByText('Ese pedido no tiene movimientos en tu punto.')).toBeInTheDocument()

    // Cerrar el aviso quita el filtro
    await user.click(screen.getByRole('button', { name: 'Cerrar aviso' }))
    expect(await screen.findByText('Camu camu en polvo 150 g')).toBeInTheDocument()
    expect(screen.getByLabelText('Número de pedido')).toHaveValue('')
  })

  it('si falla lo dice y permite reintentar', async () => {
    let intentos = 0
    servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0`]: () => (++intentos === 1 ? new Error('sin red') : [200, copia(DATOS.movimientos)]),
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')

    expect(await screen.findByText('No pudimos cargar los movimientos')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Camu camu en polvo 150 g')).toBeInTheDocument()
  })

  it('el administrador de acopio llega desde su inicio', async () => {
    servidor(comoAcopio({
      'GET /api/panel/acopio': [200, { titulo: 'Tu punto', descripcion: 'Panel del punto' }],
      [`GET ${RUTA}?pagina=0`]: [200, copia(DATOS.movimientos)],
    }))
    const user = userEvent.setup()
    abrirEn('/inicio')
    await user.click(await screen.findByRole('link', { name: 'Ver movimientos del punto' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Movimientos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/acopio/movimientos')
  })

  it('solo el administrador de punto entra; sin sesion pide ingresar', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION],
      'GET /api/panel/consumidor': [200, { titulo: 'Tu mercado', descripcion: 'Panel' }],
    })
    const { unmount } = abrirEn('/acopio/movimientos')
    await waitFor(() => expect(window.location.pathname).toBe('/inicio'))
    expect(llamadas(fetchMock, `GET ${RUTA}?pagina=0`)).toHaveLength(0)
    unmount()

    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/acopio/movimientos')
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'))
    expect(window.location.search).toBe(`?volver=${encodeURIComponent('/acopio/movimientos')}`)
  })
})
